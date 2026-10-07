package com.yzddmr6.prismspace.prism.compose.component

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yzddmr6.prismspace.controller.PrismAppClones
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.theme.PrismSpacing
import com.yzddmr6.prismspace.prism.compose.vm.SpaceRow
import com.yzddmr6.prismspace.prism.compose.vm.SpaceSegment
import com.yzddmr6.prismspace.prism.compose.vm.SpaceViewModel
import com.yzddmr6.prismspace.prism.compose.vm.ActionFeedback
import com.yzddmr6.prismspace.prism.compose.vm.AppFeedbackBus
import com.yzddmr6.prismspace.prism.compose.vm.SpaceActionGate
import com.yzddmr6.prismspace.prism.compose.vm.DualFreezeAction
import com.yzddmr6.prismspace.prism.compose.vm.dualFreezeAction
import com.yzddmr6.prismspace.prism.service.FileBridgeService
import com.yzddmr6.prismspace.prism.ui.PrismAppsViewModel
import com.yzddmr6.prismspace.shortcut.PrismAppShortcut
import kotlinx.coroutines.launch

/**
 * Bottom-sheet action drawer for the Space screen.
 *
 * Dual segment:
 *   启动应用 → vm.launch
 *   冻结/解冻 → vm.setFrozen
 *   应用信息 → vm.openSystemSettings
 *   卸载分身 (danger) → vm.remove, gated by [uninstallEntry] on dual-space usability
 *
 * Main segment:
 *   打开应用设置 → vm.openSystemSettings
 *   克隆到双开空间 → opens clone flow
 *   (if prepared) 继续安装 → profile install entry, gated by [installEntry] on dual-space usability
 *   (if cloned) 跳双开空间 → onJumpDual
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppActionSheet(
    row: SpaceRow,
    context: android.content.Context,
    vm: SpaceViewModel,
    uninstallEntry: SpaceActionGate,
    installEntry: SpaceActionGate,
    onDismiss: () -> Unit,
    onJumpDual: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val prismAppsVm: PrismAppsViewModel = viewModel()
    val activity = LocalContext.current as? androidx.fragment.app.FragmentActivity

    fun dismiss() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

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
            // Header: app name + package
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = PrismSpacing.Md)) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = row.pkg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(PrismSpacing.Xs))

            if (row.segment == SpaceSegment.Dual) {
                // --- Dual tab actions ---
                // A clone is "frozen" to the user if it's hidden OR suspended (one unified concept);
                // 解冻 then clears whichever applied (vm.setFrozen(false) recovers both).
                val paused = row.frozen || row.suspended
                if (row.launchable) {
                    SheetAction(
                        icon = PrismIcons.Play,
                        title = stringResource(R.string.lz_app_launch),
                        subtitle = if (paused) stringResource(R.string.lz_app_launch_frozen_subtitle) else null,
                    ) {
                        dismiss()
                        vm.launch(context, row.pkg, SpaceSegment.Dual)
                    }
                }

                when (dualFreezeAction(row)) {
                    // Critical packages are kept available by PrismSpace: no freeze action, only the reason.
                    DualFreezeAction.KeptAvailable -> SheetAction(
                        icon = PrismIcons.Info,
                        title = stringResource(R.string.lz_app_freeze),
                        subtitle = stringResource(R.string.dialog_critical_app_kept_available),
                        enabled = false,
                    ) {}
                    DualFreezeAction.Freeze, DualFreezeAction.Unfreeze -> SheetAction(
                        icon = if (paused) PrismIcons.Sun else PrismIcons.Snow,
                        title = if (paused) stringResource(R.string.lz_app_unfreeze) else stringResource(R.string.lz_app_freeze),
                    ) {
                        dismiss()
                        vm.setFrozen(row.pkg, !paused)
                    }
                }

                if (row.launchable) {
                    SheetAction(
                        icon = PrismIcons.Add,
                        title = stringResource(R.string.lz_app_create_shortcut),
                    ) {
                        dismiss()
                        vm.appFor(row.pkg, SpaceSegment.Dual)?.let { app ->
                            PrismAppShortcut.requestPin(context, app)
                        }
                    }
                }

                SheetAction(
                    icon = PrismIcons.Gear,
                    title = stringResource(R.string.lz_app_app_info),
                ) {
                    dismiss()
                    vm.openSystemSettings(row.pkg, SpaceSegment.Dual)
                }

                SheetAction(
                    icon = PrismIcons.Trash,
                    title = stringResource(R.string.lz_app_uninstall_clone),
                    subtitle = if (uninstallEntry.enabled) null else uninstallEntry.guidance,
                    danger = true,
                    enabled = uninstallEntry.enabled,
                ) {
                    // Gated on dual-space usability: a disabled entry surfaces the guidance and
                    // fires no uninstall request (fail closed; the request itself is re-checked
                    // against fresh usability inside vm.remove).
                    if (!uninstallEntry.enabled) {
                        uninstallEntry.guidance?.let(vm::reportTransientError)
                        return@SheetAction
                    }
                    dismiss()
                    val activity = context as? Activity ?: return@SheetAction
                    vm.remove(activity, row.pkg, SpaceSegment.Dual)
                }
            } else {
                // --- Main tab actions ---
                // 启动应用 is the most frequent action → first when the package actually has
                // a launcher activity. System-app search can surface packages without one.
                if (row.launchable) {
                    SheetAction(
                        icon = PrismIcons.Play,
                        title = stringResource(R.string.lz_app_launch),
                    ) {
                        dismiss()
                        vm.launch(context, row.pkg, SpaceSegment.Main)
                    }
                }

                SheetAction(
                    icon = PrismIcons.Gear,
                    title = stringResource(R.string.lz_app_app_info),   // unified label with the Dual tab (was "打开应用设置")
                ) {
                    dismiss()
                    vm.openSystemSettings(row.pkg, SpaceSegment.Main)
                }

                if (!row.cloneStateKnown) {
                    SheetAction(icon = PrismIcons.Info, title = stringResource(R.string.lz_space_detail_unknown),
                        subtitle = stringResource(R.string.lz_space_cached_list), enabled = false) {}
                } else if (row.cloned) {
                    SheetAction(
                        icon = PrismIcons.Grid,
                        title = stringResource(R.string.lz_app_go_to_dual_space),
                    ) {
                        dismiss()
                        onJumpDual()
                    }
                } else if (row.prepared) {
                    SheetAction(
                        icon = PrismIcons.Add,
                        title = stringResource(R.string.lz_app_continue_install),
                        subtitle = if (installEntry.enabled) null else installEntry.guidance,
                        enabled = installEntry.enabled,
                    ) {
                        // Pending-install gate: the 待安装 record stays untouched; a blocked tap only
                        // surfaces the state guidance and fires no install flow.
                        if (!installEntry.enabled) {
                            installEntry.guidance?.let(vm::reportTransientError)
                            return@SheetAction
                        }
                        dismiss()
                        if (activity != null) {
                            val result = FileBridgeService().openProfileInstallEntry(activity)
                            if (!result.success) AppFeedbackBus.emit(ActionFeedback(result.message, true))
                        }
                    }
                } else {
                    SheetAction(
                        icon = PrismIcons.Add,
                        title = stringResource(R.string.lz_app_clone_to_dual_space),
                    ) {
                        Log.i(TAG, "Main action clone tapped pkg=${row.pkg} activity=${activity != null}")
                        dismiss()
                        val app = vm.appFor(row.pkg, SpaceSegment.Main)
                        Log.i(TAG, "Main action clone app lookup pkg=${row.pkg} found=${app != null}")
                        if (app != null && activity != null) {
                            PrismAppClones(activity, prismAppsVm, app, onCloneStateChanged = vm::refresh).request()
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(PrismSpacing.Sm))
        }
    }
}

private const val TAG = "Prism.ActionSheet"

@Composable
private fun SheetAction(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val contentColor = (if (danger) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface)
        .let { if (enabled) it else it.copy(alpha = DisabledAlpha) }
    val iconColor = (if (danger) MaterialTheme.colorScheme.error
                     else MaterialTheme.colorScheme.onSurfaceVariant)
        .let { if (enabled) it else it.copy(alpha = DisabledAlpha) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(PrismSpacing.Xl),
        )
        Spacer(modifier = Modifier.width(PrismSpacing.Lg))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
