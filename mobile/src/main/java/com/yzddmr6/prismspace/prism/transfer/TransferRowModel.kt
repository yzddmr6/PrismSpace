package com.yzddmr6.prismspace.prism.transfer

import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.vm.StringResolver
import com.yzddmr6.prismspace.prism.service.TransferDirection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class RowIcon { Image, File, ApkSuite }

internal enum class RowActionKind { OpenFolder, OpenFile, ContinueInstall, Install, ShareInOtherSpace }

internal data class RowAction(
    val kind: RowActionKind,
    val label: String,
    val enabled: Boolean = true,
    val disabledReason: String? = null,
)

internal data class TransferRowModel(
    val id: String,
    val title: String,
    val summary: String,
    val icon: RowIcon,
    val primary: RowAction?,
    val secondary: RowAction?,
    val canRemove: Boolean,
    /** "Continue sharing in the other space": Sent file rows only (the file lives in the paired space). */
    val share: RowAction? = null,
)

/** "MM-dd HH:mm"; blank when the time is unknown. The one formatter for every transfer list. */
internal fun formatTransferTime(millis: Long, locale: Locale = Locale.getDefault()): String =
    if (millis <= 0L) "" else SimpleDateFormat("MM-dd HH:mm", locale).format(Date(millis))

/**
 * Pure row presentation shared by the Files page, the dual-space entry and the Home card.
 * Summaries only differ by direction: "Sent · <space holding the file>" / "Received · from <space>".
 */
internal fun transferRowModel(
    record: TransferLedgerRecord,
    res: StringResolver,
    currentIsParent: Boolean,
    formatTime: (Long) -> String = { formatTransferTime(it) },
): TransferRowModel {
    fun label(role: SpaceRole) = res(role.labelRes(), emptyArray())
    val pairedSpace = if (currentIsParent) SpaceRole.Dual else SpaceRole.Main
    val directionPart = when (record.role) {
        TransferRole.Sent -> {
            val holder = when (record.direction) {
                TransferDirection.ToProfile -> SpaceRole.Dual
                TransferDirection.ToMain -> SpaceRole.Main
                null -> pairedSpace
            }
            res(R.string.lz_xfer_row_sent, arrayOf(label(holder)))
        }
        TransferRole.Received -> when (record.direction) {
            TransferDirection.ToProfile -> res(R.string.lz_xfer_row_received_from, arrayOf(label(SpaceRole.Main)))
            TransferDirection.ToMain -> res(R.string.lz_xfer_row_received_from, arrayOf(label(SpaceRole.Dual)))
            null -> res(R.string.lz_xfer_row_received, emptyArray())
        }
    }
    val summary = listOfNotNull(
        res(R.string.lz_xfer_kind_apk_suite, emptyArray()).takeIf { record.kind == TransferKind.ApkSuite },
        directionPart,
        record.relativePath?.takeIf { it.isNotBlank() },
        formatTime(record.timeMillis).takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    val primary = when (record.kind) {
        TransferKind.File -> RowAction(RowActionKind.OpenFolder, res(R.string.lz_xfer_open_folder, emptyArray()))
        TransferKind.ApkSuite -> if (currentIsParent) {
            RowAction(RowActionKind.ContinueInstall, res(R.string.lz_app_continue_install, emptyArray()))
        } else {
            RowAction(RowActionKind.Install, res(R.string.lz_pf_install, emptyArray()))
        }
    }
    val secondary = when (record.kind) {
        TransferKind.File -> RowAction(
            RowActionKind.OpenFile,
            res(R.string.lz_xfer_open_file, emptyArray()),
            enabled = record.contentUri != null,
            disabledReason = res(R.string.lz_xfer_open_uri_unknown, emptyArray()).takeIf { record.contentUri == null },
        )
        TransferKind.ApkSuite -> null
    }
    val share = if (record.role == TransferRole.Sent && record.kind == TransferKind.File) {
        RowAction(
            RowActionKind.ShareInOtherSpace,
            res(R.string.lz_xfer_share_continue, emptyArray()),
            enabled = record.contentUri != null,
            disabledReason = res(R.string.lz_xfer_open_uri_unknown, emptyArray()).takeIf { record.contentUri == null },
        )
    } else {
        null
    }
    return TransferRowModel(
        id = record.id,
        title = record.displayTitle(),
        summary = summary,
        icon = when {
            record.kind == TransferKind.ApkSuite -> RowIcon.ApkSuite
            record.isImage -> RowIcon.Image
            else -> RowIcon.File
        },
        primary = primary,
        secondary = secondary,
        canRemove = true,
        share = share,
    )
}

/** In-sentence space name ("the dual space" / "双开空间"). */
internal fun SpaceRole.sentenceNameRes(): Int = when (this) {
    SpaceRole.Main -> R.string.lz_xfer_space_main
    SpaceRole.Dual -> R.string.lz_xfer_space_dual
}

/** Capitalized label ("Dual space"), used in row summaries. */
internal fun SpaceRole.labelRes(): Int = when (this) {
    SpaceRole.Main -> R.string.lz_home_main_space
    SpaceRole.Dual -> R.string.lz_home_dual_space
}
