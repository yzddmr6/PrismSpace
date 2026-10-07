package com.yzddmr6.prismspace.bridge

import android.os.Bundle
import kotlinx.parcelize.Parcelize

/**
 * What the caller wants to open for a transferred file, executed in the user that owns it.
 * [Share] starts the system share sheet there over the published files.
 */
enum class BridgeOpenMode { Folder, File, Share }

/**
 * Pre-flight answer from the owning user, so the caller can explain the outcome on its own screen.
 * [Unrecorded]: the file is not a "received" row of the owner's ledger any more, so the owner would
 * ignore a forwarded request for it.
 */
enum class BridgeInspectResult { Exists, Missing, NoViewer, Unrecorded }

/** Writes one ledger row in the destination user (the source-side "Sent" row of a transfer). */
@Parcelize
data class RecordTransfer(val record: TransferLedgerDto, val contentUri: String) : DestinationCommand<Boolean> {
    override val id get() = "file.record_transfer"
    override fun encodeResult(result: Boolean, out: Bundle) = out.putBoolean(RESULT, result)
    override fun decodeResult(src: Bundle) = src.getBoolean(RESULT)
}

/** Checks, inside the owning user, whether a transferred file still exists and can be viewed there. */
@Parcelize
data class InspectTransferredFile(
    val contentUri: String,
    val mime: String?,
    val mode: BridgeOpenMode,
) : DestinationCommand<BridgeInspectResult> {
    override val id get() = "file.inspect_transferred_file"
    override fun encodeResult(result: BridgeInspectResult, out: Bundle) = out.putString(RESULT, result.name)
    override fun decodeResult(src: Bundle): BridgeInspectResult =
        BridgeInspectResult.valueOf(requireNotNull(src.getString(RESULT)) { "Missing inspect result for $id" })
}

internal val TRANSFER_COMMAND_SAMPLES: List<BridgeCommand<*>> = listOf(
    RecordTransfer(
        TransferLedgerDto(
            "id",
            "name",
            "type",
            1L,
            "Download/PrismSpace",
            BridgeTransferDirection.ToProfile,
            BridgeTransferRole.Sent,
        ),
        "content://target",
    ),
    InspectTransferredFile("content://target", "type", BridgeOpenMode.File),
)
