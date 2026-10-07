package com.yzddmr6.prismspace.prism.transfer

/**
 * A cross-space request exactly as the forwarded intent carried it. The receiving aliases are exported
 * (the system forwarder starts them as the caller), so nothing here is trusted: extras only *point at*
 * ledger rows; what is opened or shared is always rebuilt from this user's own ledger.
 */
internal data class RawForwardedRequest(
    val recordId: String?,
    val mode: String?,
    val contentUri: String?,
    val shareUris: List<String>?,
    val shareMimes: List<String>?,
)

internal sealed interface ForwardVerdict {
    /** [request] is rebuilt from the ledger row(s), never from the extras. */
    data class Accept(val request: TransferOpenRequest) : ForwardVerdict
    data class Ignore(val reason: String) : ForwardVerdict
}

internal object ForwardIgnoreReason {
    const val MALFORMED = "malformed"
    const val UNKNOWN_RECORD = "unknown_record"
    const val URI_MISMATCH = "uri_mismatch"
    const val UNSUPPORTED_KIND = "unsupported_kind"
    const val SHARE_ITEM_UNKNOWN = "share_item_unknown"
}

/** The mode a raw request names, or null when absent or not a known mode. */
internal fun RawForwardedRequest.openMode(): OpenMode? = mode?.let { name -> OpenMode.entries.firstOrNull { it.name == name } }

/**
 * Folder / File: the id must be a received file row here (the file lives in this user); a carried URI
 * must equal that row's URI. Share: every carried URI must be the URI of a received file row here, or
 * the whole request is ignored (a forged request usually carries unknown URIs; never execute part of it).
 */
internal fun validateForwardedRequest(raw: RawForwardedRequest, ledger: List<TransferLedgerRecord>): ForwardVerdict {
    val id = raw.recordId?.takeIf { it.isNotBlank() } ?: return ForwardVerdict.Ignore(ForwardIgnoreReason.MALFORMED)
    val mode = raw.openMode() ?: return ForwardVerdict.Ignore(ForwardIgnoreReason.MALFORMED)
    if (mode == OpenMode.Share) {
        val items = TransferOpenPlanner.shareItemsOf(raw.shareUris, raw.shareMimes)
            ?: return ForwardVerdict.Ignore(ForwardIgnoreReason.MALFORMED)
        val received = receivedFiles(ledger)
        val rebuilt = items.map { item ->
            val row = received.firstOrNull { it.contentUri == item.contentUri }
                ?: return ForwardVerdict.Ignore(ForwardIgnoreReason.SHARE_ITEM_UNKNOWN)
            ShareItem(item.contentUri, row.mime)
        }
        return ForwardVerdict.Accept(TransferOpenRequest(id, OpenMode.Share, null, null, null, rebuilt))
    }
    val rows = ledger.filter { it.id == id }
    if (rows.isEmpty()) return ForwardVerdict.Ignore(ForwardIgnoreReason.UNKNOWN_RECORD)
    val row = rows.firstOrNull { it.kind == TransferKind.File && it.role == TransferRole.Received }
        ?: return ForwardVerdict.Ignore(ForwardIgnoreReason.UNSUPPORTED_KIND)
    if (raw.contentUri != null && raw.contentUri != row.contentUri) return ForwardVerdict.Ignore(ForwardIgnoreReason.URI_MISMATCH)
    return ForwardVerdict.Accept(TransferOpenRequest(row.id, mode, row.contentUri, row.mime, row.relativePath))
}

/** Whether [contentUri] is a received file row of this user's ledger (the pre-check behind `Unrecorded`). */
internal fun isRecordedHere(contentUri: String, ledger: List<TransferLedgerRecord>): Boolean =
    receivedFiles(ledger).any { it.contentUri == contentUri }

private fun receivedFiles(ledger: List<TransferLedgerRecord>) =
    ledger.filter { it.kind == TransferKind.File && it.role == TransferRole.Received && it.contentUri != null }
