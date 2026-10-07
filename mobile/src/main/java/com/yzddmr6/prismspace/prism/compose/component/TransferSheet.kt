package com.yzddmr6.prismspace.prism.compose.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.theme.PrismMinTouchTarget
import com.yzddmr6.prismspace.prism.compose.theme.PrismSpacing
import com.yzddmr6.prismspace.prism.compose.vm.TransferSheetViewModel
import com.yzddmr6.prismspace.prism.service.FileTransferFailureReason
import com.yzddmr6.prismspace.prism.transfer.BatchRejection
import com.yzddmr6.prismspace.prism.transfer.ItemResult
import com.yzddmr6.prismspace.prism.transfer.ItemStatus
import com.yzddmr6.prismspace.prism.transfer.ResultHeadline
import com.yzddmr6.prismspace.prism.transfer.SpaceRole
import com.yzddmr6.prismspace.prism.transfer.TransferSheetState
import com.yzddmr6.prismspace.prism.transfer.sentenceNameRes

/**
 * The one transfer sheet, hosted by the share receiver, the Files page and the dual-space entry.
 * Leaving the host (ON_STOP that is not a configuration change) cancels the batch: a transfer never
 * claims to keep running in the background.
 *
 * @param onClosed invoked once the user closed the sheet (Done / Cancel / dismissed).
 * @param resultActions extension slot rendered on the Result state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferSheetHost(
    vm: TransferSheetViewModel,
    onClosed: () -> Unit = {},
    resultActions: @Composable ColumnScope.() -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && context.findActivity()?.isChangingConfigurations != true) vm.cancel()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state == null) {
        if (state == null && wasOpen) onClosed()
        wasOpen = state != null
    }
    val current = state ?: return
    // A new sheet per phase: a sheet swiped away during Progress cancels, and the Result reappears.
    key(current.phase()) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                when (vm.state.value) {
                    TransferSheetState.Resolving, is TransferSheetState.Progress, is TransferSheetState.Confirm -> vm.cancel()
                    else -> vm.close()
                }
            },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = PrismSpacing.Lg)
                    .padding(bottom = PrismSpacing.Xl),
                verticalArrangement = Arrangement.spacedBy(PrismSpacing.Md),
            ) {
                when (current) {
                    TransferSheetState.Resolving -> ResolvingContent()
                    is TransferSheetState.Confirm -> ConfirmContent(current, onSend = vm::send, onCancel = vm::cancel)
                    is TransferSheetState.Progress -> ProgressContent(current, onCancel = vm::cancel)
                    is TransferSheetState.Result -> ResultContent(current, resultActions, onDone = vm::close)
                    is TransferSheetState.Rejected -> RejectedContent(current.reason, onDone = vm::close)
                }
            }
        }
    }
}

private fun TransferSheetState.phase(): Int = when (this) {
    TransferSheetState.Resolving -> 0
    is TransferSheetState.Confirm -> 1
    is TransferSheetState.Progress -> 2
    is TransferSheetState.Result -> 3
    is TransferSheetState.Rejected -> 4
}

@Composable
private fun ResolvingContent() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = PrismSpacing.Lg)) {
        CircularProgressIndicator(modifier = Modifier.size(PrismSpacing.Xl), strokeWidth = 3.dp)
        Spacer(Modifier.width(PrismSpacing.Lg))
        Text(stringResource(R.string.lz_xfer_resolving), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun spaceName(role: SpaceRole): String = stringResource(role.sentenceNameRes())

@Composable
private fun ConfirmContent(state: TransferSheetState.Confirm, onSend: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    SheetTitle(stringResource(R.string.lz_xfer_confirm_title, spaceName(state.target)))
    Text(
        text = stringResource(R.string.lz_xfer_confirm_count, state.items.size),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    GroupCard {
        Column(
            modifier = Modifier
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PrismSpacing.Lg, vertical = PrismSpacing.Sm),
        ) {
            state.items.forEach { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = PrismSpacing.Xs), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(PrismSpacing.Md))
                    Text(
                        text = item.sizeBytes?.let { Formatter.formatShortFileSize(context, it) }
                            ?: stringResource(R.string.lz_xfer_size_unknown),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (state.hasImages) HintText(stringResource(R.string.lz_xfer_confirm_dest_images))
    if (state.hasFiles) HintText(stringResource(R.string.lz_xfer_confirm_dest_files))
    if (state.skipped > 0) HintText(stringResource(R.string.lz_xfer_confirm_skipped, state.skipped))
    state.gateGuidance?.let {
        Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PrismSpacing.Md)) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = PrismMinTouchTarget)) {
            Text(stringResource(R.string.lz_xfer_action_cancel))
        }
        Button(
            onClick = onSend,
            enabled = state.gateGuidance == null,
            modifier = Modifier.weight(1f).heightIn(min = PrismMinTouchTarget),
        ) {
            Text(stringResource(R.string.lz_xfer_action_send))
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ProgressContent(state: TransferSheetState.Progress, onCancel: () -> Unit) {
    SheetTitle(stringResource(if (state.target == SpaceRole.Dual) R.string.lz_xfer_send_to_dual else R.string.lz_xfer_send_to_main))
    Text(
        text = stringResource(R.string.lz_xfer_progress_item, state.index, state.total, state.currentName),
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    val percent = state.percent
    if (percent == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(progress = percent / 100f, modifier = Modifier.fillMaxWidth())
    }
    HintText(stringResource(R.string.lz_xfer_progress_hint))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = PrismMinTouchTarget)) {
        Text(stringResource(R.string.lz_xfer_action_cancel))
    }
}

@Composable
private fun ColumnScope.ResultContent(
    state: TransferSheetState.Result,
    resultActions: @Composable ColumnScope.() -> Unit,
    onDone: () -> Unit,
) {
    SheetTitle(
        when (state.headline) {
            ResultHeadline.AllSent -> stringResource(R.string.lz_xfer_result_all, state.sent, spaceName(state.target))
            ResultHeadline.CancelledRest -> stringResource(R.string.lz_xfer_result_cancelled, state.sent, state.total)
            ResultHeadline.Partial -> stringResource(R.string.lz_xfer_result_partial, state.sent, state.total)
            ResultHeadline.NoneSent -> stringResource(R.string.lz_xfer_result_none)
        },
    )
    GroupCard {
        Column(
            modifier = Modifier
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PrismSpacing.Lg, vertical = PrismSpacing.Sm),
        ) {
            state.rows.forEach { row ->
                Column(Modifier.fillMaxWidth().padding(vertical = PrismSpacing.Xs)) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = itemResultText(row, state.target),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.status == ItemStatus.Failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    resultActions()
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = PrismMinTouchTarget)) {
        Text(stringResource(R.string.lz_xfer_action_done))
    }
}

@Composable
private fun itemResultText(row: ItemResult, target: SpaceRole): String = when (row.status) {
    ItemStatus.Sent -> stringResource(R.string.lz_xfer_item_sent)
    ItemStatus.Cancelled -> stringResource(R.string.lz_xfer_item_cancelled)
    ItemStatus.Failed -> when (row.reason) {
        FileTransferFailureReason.SourceUnreadable -> stringResource(R.string.lz_xfer_fail_source_unreadable)
        FileTransferFailureReason.SpaceUnavailable ->
            stringResource(R.string.lz_xfer_fail_space_unavailable, spaceName(row.failedSpace ?: target))
        FileTransferFailureReason.BridgeNotReady -> stringResource(R.string.lz_xfer_fail_bridge)
        else -> stringResource(R.string.lz_xfer_fail_target_write, spaceName(row.failedSpace ?: target))
    }
}

@Composable
private fun RejectedContent(reason: BatchRejection, onDone: () -> Unit) {
    SheetTitle(stringResource(R.string.lz_xfer_result_none))
    Text(
        text = stringResource(
            when (reason) {
                BatchRejection.NoFiles -> R.string.lz_xfer_reject_no_file
                BatchRejection.AllUnreadable -> R.string.lz_xfer_fail_source_unreadable
                BatchRejection.MixedSourceUsers -> R.string.lz_xfer_reject_mixed_sources
                BatchRejection.UnmanagedSourceUser -> R.string.lz_xfer_reject_unmanaged_source
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = PrismMinTouchTarget)) {
        Text(stringResource(R.string.lz_xfer_action_done))
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
