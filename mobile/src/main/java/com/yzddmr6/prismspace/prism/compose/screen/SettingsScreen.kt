package com.yzddmr6.prismspace.prism.compose.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yzddmr6.prismspace.prism.compose.component.AboutSheet
import com.yzddmr6.prismspace.prism.compose.nav.AppLaunchSignals
import com.yzddmr6.prismspace.prism.compose.component.ActionRow
import com.yzddmr6.prismspace.prism.compose.component.DeleteFinalSheet
import com.yzddmr6.prismspace.prism.compose.component.DeleteWarningSheet
import com.yzddmr6.prismspace.prism.compose.component.GroupCard
import com.yzddmr6.prismspace.prism.compose.component.ModeGuideSheet
import com.yzddmr6.prismspace.prism.compose.component.NavRow
import com.yzddmr6.prismspace.prism.compose.component.PrismIcons
import com.yzddmr6.prismspace.prism.compose.component.PrismTextButton
import com.yzddmr6.prismspace.prism.compose.component.RepairConfirmSheet
import com.yzddmr6.prismspace.prism.compose.component.StatusRow
import com.yzddmr6.prismspace.prism.compose.component.SwitchRow
import com.yzddmr6.prismspace.prism.compose.component.UpdateAvailableDialog
import com.yzddmr6.prismspace.prism.compose.theme.PrismSpacing
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.vm.ActionFeedback
import com.yzddmr6.prismspace.prism.compose.vm.AppFeedbackBus
import com.yzddmr6.prismspace.prism.compose.vm.PrismMode
import com.yzddmr6.prismspace.prism.compose.vm.prismModeLabelRes
import com.yzddmr6.prismspace.prism.compose.vm.SettingsViewModel
import com.yzddmr6.prismspace.prism.compose.vm.suspendSwitchPresentation
import com.yzddmr6.prismspace.util.Activities
import com.yzddmr6.prismspace.util.PrismLocale

// ---------------------------------------------------------------------------
// SettingsScreen: run mode, space controls, diagnostics, update, language and about.
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val vm: SettingsViewModel = viewModel()
    val uiState by vm.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.refreshCapabilities() }
    LaunchedEffect(Unit) {
        AppLaunchSignals.activateSpace.collect { vm.repairSpace(context, activationOnly = true) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshCapabilities()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Sheet visibility state
    var showModeSheet by remember { mutableStateOf(false) }
    var showSpaceDetails by remember { mutableStateOf(false) }
    var showAboutSheet by remember { mutableStateOf(false) }
    var showRepairConfirm by remember { mutableStateOf(false) }
    var showDeleteWarning by remember { mutableStateOf(false) }
    var showDeleteFinal by remember { mutableStateOf(false) }
    var showLangDialog by remember { mutableStateOf(false) }

    if (showSpaceDetails) AlertDialog(
        onDismissRequest = { showSpaceDetails = false },
        title = { Text(stringResource(R.string.lz_shell_space_details)) },
        text = { Text(stringResource(R.string.lz_shell_space_details_summary, uiState?.cloneCount ?: 0)) },
        confirmButton = { PrismTextButton(onClick = { showSpaceDetails = false }) { Text(stringResource(android.R.string.ok)) } },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lz_set_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PrismSpacing.Lg, vertical = PrismSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(PrismSpacing.Md),
        ) {
            val state = uiState

            // ── 双开空间 ─────────────────────────────────────────────────
            // modeLabel derives from selectedMode, not transient capability detection.
            GroupCard(title = stringResource(R.string.lz_set_group_space)) {
                if (state == null) {
                    ActionRow(
                        title = stringResource(R.string.lz_home_loading),
                        leadingIcon = PrismIcons.Shield,
                        enabled = false,
                        onClick = {},
                    )
                } else {
                    // 状态/操作行（创建/恢复/解锁/重连/修复，统一呈现策略驱动）。
                    ActionRow(
                        title = state.spaceActionTitle,
                        summary = state.spaceActionSummary,
                        leadingIcon = PrismIcons.Wrench,
                        enabled = state.spaceActionEnabled || state.spaceUsability == com.yzddmr6.prismspace.prism.compose.space.SpaceUsability.Usable,
                        onClick = {
                            if (state.spaceUsability == com.yzddmr6.prismspace.prism.compose.space.SpaceUsability.Usable) showSpaceDetails = true
                            else if (state.spaceActionNeedsConfirmation) showRepairConfirm = true
                            else vm.repairSpace(context)
                        },
                    )
                    if (state.spaceUsability != com.yzddmr6.prismspace.prism.compose.space.SpaceUsability.NotProvisioned) {
                        val switch = suspendSwitchPresentation(state.spaceFreezeState, state.spaceUsability)
                        SwitchRow(
                            title = stringResource(R.string.lz_set_suspend_title),
                            summary = stringResource(switch.summaryRes),
                            leadingIcon = PrismIcons.Snow,
                            checked = state.spaceSuspended,
                            enabled = switch.enabled,
                            onCheckedChange = { vm.suspendSpace(it) },
                        )
                        NavRow(
                            title = stringResource(R.string.lz_system_apps_title),
                            summary = stringResource(R.string.lz_set_system_apps_summary),
                            leadingIcon = PrismIcons.Droid,
                            onClick = { AppLaunchSignals.signalOpenSpaceSystemApps() },
                        )
                    }
                    // Diagnostic only: surplus profiles are never targets and never removed in-app.
                    if (state.surplusProfileIds.isNotEmpty()) {
                        ActionRow(
                            title = stringResource(R.string.lz_set_surplus_profiles_title),
                            summary = stringResource(
                                R.string.lz_set_surplus_profiles_summary,
                                state.surplusProfileIds.joinToString(", "),
                            ),
                            leadingIcon = PrismIcons.Alert,
                            onClick = { vm.openSurplusProfileRemoval(Activities.findActivityFrom(context)) },
                        )
                    }
                }
            }

            // ── 添加分身方式：当前方式 + 实时能力（每次添加不再询问） ──────────────
            GroupCard(title = stringResource(R.string.lz_set_group_clone_method)) {
                // Single label source avoids Home/Settings drift.
                val modeLabel = stringResource(prismModeLabelRes(uiState?.selectedMode ?: PrismMode.Normal))
                NavRow(
                    title = if (uiState?.selectedMode == PrismMode.Normal || uiState == null)
                        stringResource(R.string.lz_app_method_filesync_title) else modeLabel,
                    summary = if (uiState?.selectedMode == PrismMode.Normal || uiState == null)
                        stringResource(R.string.lz_app_method_filesync_summary) else stringResource(R.string.lz_shell_enhanced_method),
                    leadingIcon = PrismIcons.Key,
                    onClick = { showModeSheet = true },
                )
            }

            // ── 通用 ────────────────────────────────────────────────────
            GroupCard(title = stringResource(R.string.lz_set_group_general)) {
                val curLang = PrismLocale.getStored(context)
                val langLabel = when (curLang) {
                    PrismLocale.ZH -> stringResource(R.string.prism_language_zh)
                    PrismLocale.ZH_TW -> stringResource(R.string.prism_language_zh_tw)
                    PrismLocale.EN -> stringResource(R.string.prism_language_en)
                    else -> stringResource(R.string.prism_language_system)
                }
                NavRow(
                    title = stringResource(R.string.prism_settings_language),
                    summary = langLabel,
                    leadingIcon = Icons.Outlined.Language,
                    onClick = { showLangDialog = true },
                )
                // GitHub Releases update check.
                ActionRow(
                    title = stringResource(R.string.lz_set_check_update_title),
                    summary = stringResource(R.string.lz_shell_current_version, context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""),
                    leadingIcon = PrismIcons.Refresh,
                    onClick = { vm.checkForUpdate() },
                )
            }

            // ── 支持 ────────────────────────────────────────────────────
            GroupCard(title = stringResource(R.string.lz_set_group_support)) {
                // Diagnostic log export.
                ActionRow(
                    title = stringResource(R.string.lz_set_export_logs_title),
                    summary = stringResource(R.string.lz_set_export_logs_summary),
                    leadingIcon = Icons.Outlined.IosShare,
                    onClick = { vm.exportLogs(context) },
                )
                // Feedback and discussion.
                val feedbackAccount = stringResource(R.string.lz_set_feedback_account)
                val feedbackCopied = stringResource(R.string.lz_set_feedback_copied)
                val copyFeedbackAccount = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(feedbackAccount, feedbackAccount))
                    AppFeedbackBus.emit(ActionFeedback(feedbackCopied, isError = false))
                }
                ActionRow(
                    title = stringResource(R.string.lz_set_feedback_title),
                    summary = stringResource(R.string.lz_set_feedback_summary, feedbackAccount),
                    leadingIcon = PrismIcons.Info,
                    trailing = {
                        PrismTextButton(onClick = copyFeedbackAccount) {
                            Text(stringResource(R.string.lz_set_feedback_copy))
                        }
                    },
                    onClick = copyFeedbackAccount,
                )
                NavRow(
                    title = stringResource(R.string.lz_set_about_title),
                    summary = stringResource(R.string.lz_set_about_summary),
                    leadingIcon = PrismIcons.Info,
                    onClick = { showAboutSheet = true },
                )
            }

            // ── 实验性功能 ────────────────────────────────────────────────
            GroupCard(title = stringResource(R.string.lz_set_group_experimental)) {
                // 只读行：系统已存在第二个双开空间时 PrismSpace 自动识别；没有自建创建入口。
                StatusRow(
                    title = stringResource(R.string.lz_set_multi_space_title),
                    summary = stringResource(R.string.lz_set_multi_space_summary),
                    leadingIcon = PrismIcons.Grid,
                )
            }

            // ── 危险区 ───────────────────────────────────────────────────
            if (state?.profileOwnerReady == true) {
                GroupCard(title = stringResource(R.string.lz_set_group_danger)) {
                    ActionRow(
                        title = stringResource(R.string.lz_set_delete_space_title),
                        summary = stringResource(R.string.lz_set_delete_space_summary),
                        leadingIcon = PrismIcons.Trash,
                        danger = true,
                        onClick = { showDeleteWarning = true },
                    )
                }
            }
        }
    }

    // ── Mode guide sheet ────────────────────────────────────────────────────
    if (showModeSheet) {
        ModeGuideSheet(
            currentMode = uiState?.selectedMode ?: PrismMode.Normal,
            onDismiss = { showModeSheet = false },
            onSetNormal = { vm.setNormalMode() },
            onCheckShizuku = { vm.checkShizuku() },
            onRequestRoot = { vm.requestRoot() },
        )
    }

    // ── About sheet ─────────────────────────────────────────────────────────
    if (showAboutSheet) {
        val versionText = remember(context) {
            try {
                val pi = context.packageManager.getPackageInfo(context.packageName, 0)
                val vc = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
                "v${pi.versionName ?: "?"} ($vc)"
            } catch (_: Exception) { "v?" }
        }
        AboutSheet(
            versionText = versionText,
            packageName = context.packageName,
            onDismiss = { showAboutSheet = false },
        )
    }

    if (showRepairConfirm) {
        RepairConfirmSheet(
            onConfirm = { showRepairConfirm = false; vm.repairSpace(context) },
            onDismiss = { showRepairConfirm = false },
        )
    }
    if (showDeleteWarning) {
        DeleteWarningSheet(
            cloneCount = uiState?.cloneCount ?: 0,
            onContinue = {
                showDeleteWarning = false
                showDeleteFinal = true
            },
            onDismiss = { showDeleteWarning = false },
        )
    }
    if (showDeleteFinal) {
        DeleteFinalSheet(
            onConfirm = {
                showDeleteFinal = false
                vm.deleteDualSpace(Activities.findActivityFrom(context))
            },
            onDismiss = { showDeleteFinal = false },
        )
    }

    // ── Language picker ───────────────────────────────────────────────────────
    if (showLangDialog) {
        val current = PrismLocale.getStored(context)
        val options = listOf(
            PrismLocale.SYSTEM to stringResource(R.string.prism_language_system),
            PrismLocale.ZH to stringResource(R.string.prism_language_zh),
            PrismLocale.ZH_TW to stringResource(R.string.prism_language_zh_tw),
            PrismLocale.EN to stringResource(R.string.prism_language_en),
        )
        AlertDialog(
            onDismissRequest = { showLangDialog = false },
            title = { Text(stringResource(R.string.prism_settings_language)) },
            text = {
                Column {
                    options.forEach { (tag, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showLangDialog = false
                                    if (tag != current) {
                                        PrismLocale.setStored(context, tag)
                                        (context as? android.app.Activity)?.recreate()
                                    }
                                }
                                .padding(vertical = PrismSpacing.Md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = tag == current, onClick = null)
                            Spacer(Modifier.width(PrismSpacing.Md))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { PrismTextButton(onClick = { showLangDialog = false }) { Text(stringResource(R.string.lz_set_cancel)) } },
        )
    }

    // ── update-available dialog ───────────────────────────────────────────────
    uiState?.updateInfo?.let { info ->
        UpdateAvailableDialog(info = info, context = context, onDismiss = { vm.dismissUpdate() })
    }

}
