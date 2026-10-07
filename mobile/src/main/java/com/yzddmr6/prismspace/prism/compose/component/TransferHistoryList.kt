package com.yzddmr6.prismspace.prism.compose.component

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.theme.PrismMinTouchTarget
import com.yzddmr6.prismspace.prism.compose.theme.PrismSpacing
import com.yzddmr6.prismspace.prism.compose.vm.StringResolver
import com.yzddmr6.prismspace.prism.compose.vm.prismResolver
import com.yzddmr6.prismspace.prism.transfer.OpenMode
import com.yzddmr6.prismspace.prism.transfer.OpenOutcome
import com.yzddmr6.prismspace.prism.transfer.RowAction
import com.yzddmr6.prismspace.prism.transfer.RowActionKind
import com.yzddmr6.prismspace.prism.transfer.RowIcon
import com.yzddmr6.prismspace.prism.transfer.ShareOutcome
import com.yzddmr6.prismspace.prism.transfer.TransferLedgerRecord
import com.yzddmr6.prismspace.prism.transfer.TransferOpenCoordinator
import com.yzddmr6.prismspace.prism.transfer.TransferRowModel
import com.yzddmr6.prismspace.prism.transfer.sentenceNameRes
import com.yzddmr6.prismspace.prism.transfer.transferRowModel
import kotlinx.coroutines.launch

/**
 * The one transfer-ledger list, shared by the main-space Files page and the dual-space entry.
 * Row-end button = primary action (open folder / install entry); tapping the row opens a small
 * action sheet with the primary action, "open file" (or why it is unavailable) and "remove record".
 *
 * @param onApkAction install-entry action for APK-suite rows, owned by the host screen.
 */
@Composable
internal fun TransferHistoryList(
    records: List<TransferLedgerRecord>,
    currentIsParent: Boolean,
    onClear: () -> Unit,
    onRemove: (String) -> Unit,
    onApkAction: (TransferLedgerRecord) -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    val res = remember(context) { prismResolver(context) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var sheetRecord by remember { mutableStateOf<TransferLedgerRecord?>(null) }
    var missingRecord by remember { mutableStateOf<TransferLedgerRecord?>(null) }
    var noViewerRecord by remember { mutableStateOf<TransferLedgerRecord?>(null) }
    var opening by remember { mutableStateOf(false) }

    fun open(record: TransferLedgerRecord, mode: OpenMode) {
        val host = activity ?: return
        if (opening) return
        opening = true
        scope.launch {
            val outcome = try {
                TransferOpenCoordinator.open(host, record, mode)
            } finally {
                opening = false
            }
            when (outcome) {
                OpenOutcome.Opened -> Unit
                is OpenOutcome.Handoff -> Toast.makeText(
                    context,
                    res(R.string.lz_xfer_open_handoff, arrayOf(res(outcome.owner.sentenceNameRes(), emptyArray()))),
                    Toast.LENGTH_SHORT,
                ).show()
                OpenOutcome.Missing -> missingRecord = record
                OpenOutcome.NoViewer -> noViewerRecord = record
                is OpenOutcome.Blocked -> Toast.makeText(context, outcome.guidance, Toast.LENGTH_LONG).show()
                is OpenOutcome.Failed -> Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun share(record: TransferLedgerRecord) {
        val host = activity ?: return
        if (opening) return
        opening = true
        scope.launch {
            val outcome = try {
                TransferOpenCoordinator.shareRecord(host, record)
            } finally {
                opening = false
            }
            when (outcome) {
                ShareOutcome.Missing -> missingRecord = record
                else -> shareOutcomeMessage(outcome, res)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            }
        }
    }

    fun perform(record: TransferLedgerRecord, action: RowAction) {
        if (!action.enabled) {
            action.disabledReason?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            return
        }
        when (action.kind) {
            RowActionKind.OpenFolder -> open(record, OpenMode.Folder)
            RowActionKind.OpenFile -> open(record, OpenMode.File)
            RowActionKind.ContinueInstall, RowActionKind.Install -> onApkAction(record)
            RowActionKind.ShareInOtherSpace -> share(record)
        }
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = PrismSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.lz_pf_files_history_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (records.isNotEmpty()) {
                PrismTextButton(onClick = { showClearConfirm = true }) { Text(stringResource(R.string.lz_pf_files_clear)) }
            }
        }
        if (records.isEmpty()) {
            GroupCard {
                Text(
                    text = stringResource(R.string.lz_xfer_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = PrismSpacing.Lg, vertical = 18.dp),
                )
            }
        } else {
            GroupCard {
                records.forEach { record ->
                    val model = transferRowModel(record, res, currentIsParent)
                    ActionRow(
                        title = model.title,
                        summary = model.summary,
                        leadingIcon = model.icon.vector(),
                        trailing = model.primary?.let { primary ->
                            {
                                PrismTextButton(onClick = { perform(record, primary) }, enabled = !opening) {
                                    Text(primary.label)
                                }
                            }
                        },
                        onClick = { sheetRecord = record },
                    )
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.lz_pf_files_clear_confirm_title)) },
            // Make explicit it only clears the LOG, not the transferred files (users fear data loss).
            text = { Text(stringResource(R.string.lz_pf_files_clear_confirm_body)) },
            confirmButton = {
                PrismTextButton(onClick = { showClearConfirm = false; onClear() }) {
                    Text(stringResource(R.string.lz_pf_files_clear))
                }
            },
            dismissButton = {
                PrismTextButton(onClick = { showClearConfirm = false }) { Text(stringResource(R.string.lz_set_cancel)) }
            },
        )
    }

    sheetRecord?.let { record ->
        RecordActionSheet(
            model = transferRowModel(record, res, currentIsParent),
            onAction = { action -> sheetRecord = null; perform(record, action) },
            onRemove = { sheetRecord = null; onRemove(record.id) },
            onDismiss = { sheetRecord = null },
        )
    }

    missingRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { missingRecord = null },
            title = { Text(stringResource(R.string.lz_xfer_open_missing)) },
            text = { Text(record.displayName) },
            confirmButton = {
                PrismTextButton(onClick = { missingRecord = null; onRemove(record.id) }) {
                    Text(stringResource(R.string.lz_xfer_remove_record))
                }
            },
            dismissButton = {
                PrismTextButton(onClick = { missingRecord = null }) { Text(stringResource(R.string.lz_xfer_action_close)) }
            },
        )
    }

    noViewerRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { noViewerRecord = null },
            title = { Text(stringResource(R.string.lz_xfer_open_no_viewer)) },
            text = { Text(record.displayName) },
            confirmButton = {
                PrismTextButton(onClick = { noViewerRecord = null; open(record, OpenMode.Folder) }) {
                    Text(stringResource(R.string.lz_xfer_open_folder))
                }
            },
            dismissButton = {
                PrismTextButton(onClick = { noViewerRecord = null }) { Text(stringResource(R.string.lz_xfer_action_close)) }
            },
        )
    }
}

/**
 * Toast text for a "continue sharing" outcome, shared by the transfer sheet and the ledger rows;
 * null when nothing needs saying (the share sheet opened with every file).
 */
internal fun shareOutcomeMessage(outcome: ShareOutcome, res: StringResolver): String? = when (outcome) {
    is ShareOutcome.Opened -> skippedMessage(outcome.dropped, res)
    is ShareOutcome.Handoff -> listOfNotNull(
        res(R.string.lz_xfer_share_handoff, arrayOf(res(outcome.owner.sentenceNameRes(), emptyArray()))),
        skippedMessage(outcome.dropped, res),
    ).joinToString("\n")
    ShareOutcome.Missing -> res(R.string.lz_xfer_share_missing, emptyArray())
    ShareOutcome.NoTarget -> res(R.string.lz_xfer_share_no_target, emptyArray())
    is ShareOutcome.Blocked -> outcome.guidance
    is ShareOutcome.Failed -> outcome.message
}

private fun skippedMessage(dropped: Int, res: StringResolver): String? =
    if (dropped > 0) res(R.string.lz_xfer_share_skipped, arrayOf(dropped)) else null

private fun RowIcon.vector(): ImageVector = when (this) {
    RowIcon.Image -> PrismIcons.Img
    RowIcon.File -> PrismIcons.File
    RowIcon.ApkSuite -> PrismIcons.Box
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordActionSheet(
    model: TransferRowModel,
    onAction: (RowAction) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = PrismSpacing.Sm),
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = PrismSpacing.Md)) {
                Text(model.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(model.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            model.primary?.let { action ->
                RecordSheetAction(PrismIcons.Files, action.label, null, action.enabled) { onAction(action) }
            }
            model.secondary?.let { action ->
                RecordSheetAction(PrismIcons.FileOpen, action.label, action.disabledReason, action.enabled) { onAction(action) }
            }
            model.share?.let { action ->
                RecordSheetAction(PrismIcons.Share, action.label, action.disabledReason, action.enabled) { onAction(action) }
            }
            if (model.canRemove) {
                RecordSheetAction(PrismIcons.Trash, stringResource(R.string.lz_xfer_remove_record), null, true, onRemove)
            }
            Spacer(Modifier.height(PrismSpacing.Sm))
        }
    }
}

@Composable
private fun RecordSheetAction(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PrismMinTouchTarget)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = PrismSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            modifier = Modifier.size(PrismSpacing.Xl),
        )
        Spacer(Modifier.width(PrismSpacing.Lg))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
