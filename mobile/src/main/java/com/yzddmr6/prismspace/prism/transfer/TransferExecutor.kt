package com.yzddmr6.prismspace.prism.transfer

import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.bridge.BridgeFileStore
import com.yzddmr6.prismspace.bridge.BridgeTransferRole
import com.yzddmr6.prismspace.bridge.TransferLedgerDto
import com.yzddmr6.prismspace.prism.service.CrossSpaceFileTransferPolicy
import com.yzddmr6.prismspace.prism.service.FileTransferFailureReason
import com.yzddmr6.prismspace.prism.service.FileTransferPolicy
import com.yzddmr6.prismspace.prism.service.ProfileBridgeResult
import com.yzddmr6.prismspace.prism.service.SingleCopyTransferResult
import com.yzddmr6.prismspace.prism.service.TransferCancellationSignal
import com.yzddmr6.prismspace.prism.service.TransferDirection
import com.yzddmr6.prismspace.prism.service.TransferSource
import com.yzddmr6.prismspace.prism.service.crossSpaceFailureReason
import com.yzddmr6.prismspace.prism.service.transferSingleCopy
import java.io.OutputStream

/** An open MediaStore write session in the target user; [output] wraps the bridged descriptor. */
internal data class PendingWrite(val uri: String, val output: OutputStream)

/**
 * Everything the executor needs from Android and the bridge. The Android implementation wraps
 * `runDestinationBridgeOperation` (local dispatch when the target is the current user); tests fake it.
 */
internal interface TransferPorts {
    fun openSession(
        targetUserId: Int,
        store: BridgeFileStore,
        name: String,
        mime: String,
        relativePath: String,
    ): ProfileBridgeResult<PendingWrite>
    fun finish(targetUserId: Int, store: BridgeFileStore, uri: String, dto: TransferLedgerDto): ProfileBridgeResult<String>
    fun abort(targetUserId: Int, store: BridgeFileStore, uri: String, transferId: String)
    fun source(item: TransferItem): TransferSource
    /** Writes the "Sent" row in the source user; false when that write failed. */
    fun recordSent(sourceUserId: Int, dto: TransferLedgerDto, contentUri: String): Boolean
}

internal sealed interface ExecutorEvent {
    data class ItemStarted(val index: Int, val total: Int, val item: TransferItem) : ExecutorEvent
    data class ItemProgress(val index: Int, val written: Long) : ExecutorEvent
    data class ItemFinished(val index: Int, val outcome: TransferOutcome) : ExecutorEvent
}

/** Log sink; JVM tests record lines instead of hitting android.util.Log. */
internal interface TransferLog {
    fun info(message: String)
    fun warn(message: String, error: Throwable? = null)

    object Diagnostic : TransferLog {
        private const val TAG = "Prism.FileBridge"
        override fun info(message: String) = DiagnosticLog.i(TAG, message)
        override fun warn(message: String, error: Throwable?) = DiagnosticLog.w(TAG, message, error)
    }
}

/**
 * Serial executor for one [TransferRequest]: every item ends with exactly one [TransferOutcome] and
 * exactly one `xfer.item.result` log line paired with its `xfer.item.queued` line.
 */
internal class TransferExecutor(
    private val ports: TransferPorts,
    private val log: TransferLog = TransferLog.Diagnostic,
) {

    fun run(
        request: TransferRequest,
        cancellation: TransferCancellationSignal,
        onEvent: (ExecutorEvent) -> Unit = {},
    ): List<TransferOutcome> {
        val destination = request.destination
        val sourceRole = if (destination.target == SpaceRole.Dual) SpaceRole.Main else SpaceRole.Dual
        request.items.forEachIndexed { index, item ->
            log.info(
                "xfer.item.queued batch=${request.batchId} id=${item.transferId} idx=$index mime=${item.source.mime} " +
                    "declared=${item.source.declaredSize ?: "unknown"} source=${destination.sourceUserId} " +
                    "target=${destination.targetUserId ?: "none"}",
            )
        }
        val outcomes = ArrayList<TransferOutcome>(request.items.size)
        request.items.forEachIndexed { index, item ->
            val outcome = if (cancellation.isCancelled()) {
                TransferOutcome.Cancelled(item.transferId, started = false)
            } else {
                onEvent(ExecutorEvent.ItemStarted(index, request.items.size, item))
                runCatching { transferOne(index, item, destination, sourceRole, cancellation, onEvent) }
                    .getOrElse { error ->
                        log.warn("xfer.item.crashed id=${item.transferId}", error)
                        TransferOutcome.Failed(item.transferId, FileTransferFailureReason.TargetWriteFailed, destination.target)
                    }
            }
            logResult(outcome)
            outcomes += outcome
            onEvent(ExecutorEvent.ItemFinished(index, outcome))
        }
        return outcomes
    }

    private fun transferOne(
        index: Int,
        item: TransferItem,
        destination: TransferDestination.OtherSpace,
        sourceRole: SpaceRole,
        cancellation: TransferCancellationSignal,
        onEvent: (ExecutorEvent) -> Unit,
    ): TransferOutcome {
        val id = item.transferId
        val targetUserId = destination.targetUserId
            ?: return TransferOutcome.Failed(id, FileTransferFailureReason.SpaceUnavailable, destination.target)
        val mime = item.source.mime
        val safeName = FileTransferPolicy.safeDisplayName(item.source.displayName)
        val placement = CrossSpaceFileTransferPolicy.destination(mime)
        val store = if (placement.isImage) BridgeFileStore.Media else BridgeFileStore.Downloads
        val session = when (val opened = ports.openSession(targetUserId, store, safeName, mime, placement.relativePath)) {
            is ProfileBridgeResult.Value -> opened.value
                ?: return TransferOutcome.Failed(id, FileTransferFailureReason.TargetWriteFailed, destination.target)
            else -> return failure(id, crossSpaceFailureReason(opened), destination.target, sourceRole)
        }
        val write = transferSingleCopy(
            source = ports.source(item),
            output = session.output,
            cancellation = cancellation,
            onProgress = { written -> onEvent(ExecutorEvent.ItemProgress(index, written)) },
            abort = { ports.abort(targetUserId, store, session.uri, id) },
        )
        val bytes = when (write) {
            is SingleCopyTransferResult.Written -> write.bytes
            SingleCopyTransferResult.Cancelled -> return TransferOutcome.Cancelled(id, started = true)
            SingleCopyTransferResult.SourceUnreadable ->
                return failure(id, FileTransferFailureReason.SourceUnreadable, destination.target, sourceRole)
            SingleCopyTransferResult.TargetWriteFailed ->
                return failure(id, FileTransferFailureReason.TargetWriteFailed, destination.target, sourceRole)
        }
        val received = TransferLedgerDto(
            transferId = id,
            displayName = safeName,
            mime = mime,
            sizeBytes = bytes,
            relativePath = placement.displayLocation,
            direction = (if (destination.target == SpaceRole.Dual) TransferDirection.ToProfile else TransferDirection.ToMain)
                .toBridgeDirection(),
            role = BridgeTransferRole.Received,
        )
        val finished = runCatching { ports.finish(targetUserId, store, session.uri, received) }
            .getOrElse { ProfileBridgeResult.Failed(it) }
        if (finished !is ProfileBridgeResult.Value) {
            // The bytes may be complete but the row is still pending: not a usable transfer. The
            // abort also removes the target's "Received" row (abort carries the transferId).
            log.warn("xfer.item.finish_failed id=$id cause=${finished.javaClass.simpleName}")
            runCatching { ports.abort(targetUserId, store, session.uri, id) }
                .onFailure { log.warn("xfer.item.abort_failed id=$id", it) }
            return TransferOutcome.Failed(id, FileTransferFailureReason.TargetWriteFailed, destination.target)
        }
        val published = finished.value ?: session.uri
        val sentRecorded = runCatching {
            ports.recordSent(destination.sourceUserId, received.copy(role = BridgeTransferRole.Sent), published)
        }.getOrDefault(false)
        if (!sentRecorded) {
            // The file really arrived; the result stays "sent". Only the source-side row is missing.
            log.warn("ledger.sent_record_failed id=$id source=${destination.sourceUserId}")
        }
        return TransferOutcome.Sent(id, published, bytes)
    }

    /** Points the failure at the side that actually failed. */
    private fun failure(
        id: String,
        reason: FileTransferFailureReason,
        target: SpaceRole,
        source: SpaceRole,
    ): TransferOutcome.Failed {
        val userClass = reason.userFailureClass()
        val side = when (userClass) {
            FileTransferFailureReason.SourceUnreadable -> source
            FileTransferFailureReason.BridgeNotReady -> null
            else -> target
        }
        return TransferOutcome.Failed(id, userClass, side)
    }

    private fun logResult(outcome: TransferOutcome) {
        val line = when (outcome) {
            is TransferOutcome.Sent ->
                "xfer.item.result id=${outcome.transferId} outcome=sent reason=- side=- started=true bytes=${outcome.bytes}"
            is TransferOutcome.Failed ->
                "xfer.item.result id=${outcome.transferId} outcome=failed reason=${outcome.reason} " +
                    "side=${outcome.failedSpace ?: "-"} started=true bytes=0"
            is TransferOutcome.Cancelled ->
                "xfer.item.result id=${outcome.transferId} outcome=cancelled reason=- side=- " +
                    "started=${outcome.started} bytes=0"
        }
        if (outcome is TransferOutcome.Sent) log.info(line) else log.warn(line)
    }
}
