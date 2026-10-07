package com.yzddmr6.prismspace.prism.transfer

import com.yzddmr6.prismspace.prism.service.FileTransferFailureReason
import com.yzddmr6.prismspace.prism.service.TransferDirection

/**
 * Model of the single file-transfer primitive "send to the other space".
 *
 * Planning types carry only strings and primitives: JVM unit tests run against android.jar stubs,
 * so `Uri` / `Intent` stay in the thin Android adapters.
 */

/** User-visible space names: 主空间 / 双开空间. */
enum class SpaceRole { Main, Dual }

enum class TransferKind(val wire: String) {
    File("file"),
    ApkSuite("apk_suite");

    companion object {
        fun fromWire(value: String?): TransferKind? = entries.firstOrNull { it.wire == value }
    }
}

enum class TransferRole(val wire: String) {
    Sent("sent"),
    Received("received");

    companion object {
        fun fromWire(value: String?): TransferRole? = entries.firstOrNull { it.wire == value }
    }
}

enum class TransferEntry { ShareSheet, FilesPage, ProfileEntry }

/** One received item before resolution. */
data class SourceRef(
    val receivedUri: String,
    /** [SourceUriPlanner.candidates]: the received URI, then the paired-user qualified one (if any). */
    val candidates: List<String>,
    /** The whole batch's `intent.type`; only a fallback for the per-item mime. */
    val intentType: String?,
)

sealed interface SourceResolution {
    data class Resolved(
        val sourceUserId: Int,
        val rule: SourceUserRule,
        /** The candidate that passed the probe; the transfer opens only this one. */
        val readableUri: String,
        val displayName: String,
        val mime: String,
        val declaredSize: Long?,
    ) : SourceResolution

    data object Unreadable : SourceResolution
}

enum class SourceUserRule { QualifiedAuthority, CurrentUser, PairedUser }

/** Only "the other space" exists; the SAF "save as" branch is gone. */
sealed interface TransferDestination {
    /** [targetUserId] is null when the target space does not exist (no dual space yet). */
    data class OtherSpace(val sourceUserId: Int, val targetUserId: Int?, val target: SpaceRole) : TransferDestination
}

/** [transferId] is generated on the source side and shared by both ledgers. */
data class TransferItem(val transferId: String, val source: SourceResolution.Resolved)

data class TransferRequest(
    val batchId: String,
    val entry: TransferEntry,
    val kind: TransferKind = TransferKind.File,
    val destination: TransferDestination.OtherSpace,
    val items: List<TransferItem>,
    /** Unreadable items: listed in Confirm and reported as SourceUnreadable in the result. */
    val skipped: List<SourceRef>,
)

sealed interface BatchPlan {
    data class Ready(val request: TransferRequest) : BatchPlan
    data class Rejected(val reason: BatchRejection) : BatchPlan
}

enum class BatchRejection { NoFiles, AllUnreadable, MixedSourceUsers, UnmanagedSourceUser }

sealed interface TransferOutcome {
    val transferId: String

    data class Sent(override val transferId: String, val publishedUri: String, val bytes: Long) : TransferOutcome

    /** [reason] is always one of [userFailureClass]'s four classes. */
    data class Failed(
        override val transferId: String,
        val reason: FileTransferFailureReason,
        val failedSpace: SpaceRole?,
    ) : TransferOutcome

    data class Cancelled(override val transferId: String, val started: Boolean) : TransferOutcome
}

/** Collapses every failure reason into the four user-visible classes. */
fun FileTransferFailureReason.userFailureClass(): FileTransferFailureReason = when (this) {
    FileTransferFailureReason.SourceUnreadable -> FileTransferFailureReason.SourceUnreadable
    FileTransferFailureReason.SpaceMissing,
    FileTransferFailureReason.SpaceInactive,
    FileTransferFailureReason.SpaceUnavailable -> FileTransferFailureReason.SpaceUnavailable
    FileTransferFailureReason.BridgeNotReady,
    FileTransferFailureReason.TimedOut -> FileTransferFailureReason.BridgeNotReady
    FileTransferFailureReason.IOError,
    FileTransferFailureReason.TargetWriteFailed,
    FileTransferFailureReason.Cancelled -> FileTransferFailureReason.TargetWriteFailed
}

/** One ledger row. The owning user is implied by [role]: Received → this user; Sent → the paired space. */
data class TransferLedgerRecord(
    /** Files: the shared transferId. APK suites: a UUID. */
    val id: String,
    /** APK suites: the app label; the title is still [displayTitle] ("label-package"). */
    val displayName: String,
    /** Null for legacy records. */
    val mime: String?,
    /** Bytes actually written; null when unknown. */
    val sizeBytes: Long?,
    /** MediaStore URI inside the owning user; null when unknown (legacy). */
    val contentUri: String?,
    /** "Pictures/PrismSpace" | "Download/PrismSpace" | null. */
    val relativePath: String?,
    val direction: TransferDirection?,
    val role: TransferRole,
    val kind: TransferKind,
    /** APK suites only. */
    val packageName: String?,
    /** APK suites on the receiving side: every published URI of this suite, base first. */
    val apkUris: List<String>,
    val timeMillis: Long,
    val legacy: Boolean,
    /** APK-suite index row kept after the user cleared the ledger; never shown in lists. */
    val hidden: Boolean = false,
    /** Icon hint. Derived from [mime] for new rows; kept from the legacy `isImage` field otherwise. */
    val isImage: Boolean = mime?.startsWith("image/", ignoreCase = true) == true,
)

/** "label-package" for APK suites (e.g. Chrome-com.android.chrome); the file name otherwise. */
fun TransferLedgerRecord.displayTitle(): String =
    if (!packageName.isNullOrBlank()) "$displayName-$packageName" else displayName
