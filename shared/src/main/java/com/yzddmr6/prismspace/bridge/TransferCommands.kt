package com.yzddmr6.prismspace.bridge

import android.os.Bundle
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * What the caller wants to open for a transferred file, executed in the user that owns it.
 * [Share] starts the system share sheet there over the published files of [TransferOpenRequestDto.shareItems].
 */
enum class BridgeOpenMode { Folder, File, Share }

/** Pre-flight answer from the owning user, so the caller can explain the outcome on its own screen. */
enum class BridgeInspectResult { Exists, Missing, NoViewer }

/** One published file to share inside the user that owns it. */
@Parcelize
data class TransferShareItemDto(val contentUri: String, val mime: String?) : Parcelable

/**
 * A pending "open this transferred file" request, parked in the owning user until its entry screen resumes.
 * [shareItems] is used by [BridgeOpenMode.Share] only (≥ 1 item); empty for the other modes. Both ends
 * of the bridge are the same installed APK, so the defaulted field has no cross-version concern.
 */
@Parcelize
data class TransferOpenRequestDto(
    val recordId: String,
    val mode: BridgeOpenMode,
    val contentUri: String?,
    val mime: String?,
    val relativePath: String?,
    val shareItems: List<TransferShareItemDto> = emptyList(),
) : Parcelable

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

/**
 * Parks an open request in the owning user. The handler only records it: a bridge handler runs in
 * the background, where Android blocks activity starts; the owner's foreground entry screen drains it.
 */
@Parcelize
data class QueueTransferOpen(val request: TransferOpenRequestDto) : DestinationCommand<Boolean> {
    override val id get() = "file.queue_transfer_open"
    override fun encodeResult(result: Boolean, out: Bundle) = out.putBoolean(RESULT, result)
    override fun decodeResult(src: Bundle) = src.getBoolean(RESULT)
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
    QueueTransferOpen(TransferOpenRequestDto("id", BridgeOpenMode.Folder, null, null, "Download/PrismSpace")),
)
