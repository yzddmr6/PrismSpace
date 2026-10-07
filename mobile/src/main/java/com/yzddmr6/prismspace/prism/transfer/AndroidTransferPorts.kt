package com.yzddmr6.prismspace.prism.transfer

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.yzddmr6.prismspace.bridge.AbortWriteSession
import com.yzddmr6.prismspace.bridge.BridgeFileStore
import com.yzddmr6.prismspace.bridge.BridgeTarget
import com.yzddmr6.prismspace.bridge.BridgeTargets
import com.yzddmr6.prismspace.bridge.FinishWriteSession
import com.yzddmr6.prismspace.bridge.OpenWriteSession
import com.yzddmr6.prismspace.bridge.PublishedFileDto
import com.yzddmr6.prismspace.bridge.RecordTransfer
import com.yzddmr6.prismspace.bridge.TransferLedgerDto
import com.yzddmr6.prismspace.prism.service.ProfileBridgeResult
import com.yzddmr6.prismspace.prism.service.TransferSource
import com.yzddmr6.prismspace.prism.service.asFailureResult
import com.yzddmr6.prismspace.prism.service.runDestinationBridgeOperation
import com.yzddmr6.prismspace.util.Users

/**
 * Bridge target for a user id: the parent user maps to [BridgeTargets.parent], anything else must be
 * a PrismSpace-managed profile. When the id is the current user the bridge dispatches locally, which
 * is how "write in this space" stays the degenerate case of the same pipeline.
 */
internal fun bridgeTargetFor(userId: Int): BridgeTarget? =
    if (runCatching { Users.isParentProfile(userId) }.getOrDefault(false)) BridgeTargets.parent()
    else BridgeTargets.profile(userId)

internal class AndroidTransferPorts(private val context: Context) : TransferPorts {

    override fun openSession(
        targetUserId: Int,
        store: BridgeFileStore,
        name: String,
        mime: String,
        relativePath: String,
    ): ProfileBridgeResult<PendingWrite> {
        val target = bridgeTargetFor(targetUserId) ?: return ProfileBridgeResult.SpaceMissing
        return when (val result = runDestinationBridgeOperation(
            context,
            TAG,
            "xfer open target=$targetUserId",
            target,
            command = OpenWriteSession(store, name, mime, relativePath),
        )) {
            is ProfileBridgeResult.Value -> ProfileBridgeResult.Value(
                result.value?.let { PendingWrite(it.uri, ParcelFileDescriptor.AutoCloseOutputStream(it.descriptor)) },
            )
            else -> result.asFailureResult()
        }
    }

    override fun finish(
        targetUserId: Int,
        store: BridgeFileStore,
        uri: String,
        dto: TransferLedgerDto,
    ): ProfileBridgeResult<PublishedFileDto> {
        val target = bridgeTargetFor(targetUserId) ?: return ProfileBridgeResult.SpaceMissing
        return runDestinationBridgeOperation(
            context,
            TAG,
            "xfer finish target=$targetUserId",
            target,
            command = FinishWriteSession(store, uri, dto),
        )
    }

    override fun abort(targetUserId: Int, store: BridgeFileStore, uri: String, transferId: String) {
        val target = bridgeTargetFor(targetUserId) ?: return
        runCatching {
            runDestinationBridgeOperation(
                context,
                TAG,
                "xfer abort target=$targetUserId",
                target,
                command = AbortWriteSession(store, uri, transferId),
            )
        }
    }

    override fun source(item: TransferItem): TransferSource = TransferSource.fromUriCandidates(
        context.contentResolver,
        listOf(Uri.parse(item.source.readableUri)),
        item.source.displayName,
        item.source.mime,
        item.source.declaredSize,
    )

    override fun recordSent(sourceUserId: Int, dto: TransferLedgerDto, contentUri: String): Boolean {
        if (sourceUserId == Users.currentId()) {
            TransferLedger.record(context, dto.toLedgerRecord(contentUri))
            return true
        }
        val target = bridgeTargetFor(sourceUserId) ?: return false
        val result = runDestinationBridgeOperation(
            context,
            TAG,
            "xfer record-sent source=$sourceUserId",
            target,
            command = RecordTransfer(dto, contentUri),
        )
        return result is ProfileBridgeResult.Value && result.value == true
    }

    private companion object {
        private const val TAG = "Prism.FileBridge"
    }
}
