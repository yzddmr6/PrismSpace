@file:Suppress("LongMethod", "MagicNumber")
package com.yzddmr6.prismspace.prism.compose.screen

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.widget.Toast
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.component.ActionRow
import com.yzddmr6.prismspace.prism.compose.component.GroupCard
import com.yzddmr6.prismspace.prism.compose.component.PrismTextButton
import com.yzddmr6.prismspace.prism.compose.component.PrismIcons
import androidx.compose.foundation.clickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Button
import androidx.compose.ui.draw.rotate
import com.yzddmr6.prismspace.prism.compose.vm.ActionFeedback
import com.yzddmr6.prismspace.prism.compose.vm.AppFeedbackBus
import com.yzddmr6.prismspace.prism.compose.vm.continueInstallGate
import com.yzddmr6.prismspace.prism.compose.vm.fileTransferGate
import com.yzddmr6.prismspace.prism.compose.vm.prismResolver
import kotlinx.coroutines.launch
import com.yzddmr6.prismspace.prism.compose.component.TransferHistoryList
import com.yzddmr6.prismspace.prism.compose.component.TransferSheetHost
import com.yzddmr6.prismspace.prism.compose.theme.PrismSpacing
import com.yzddmr6.prismspace.prism.compose.vm.FilesViewModel
import com.yzddmr6.prismspace.prism.compose.vm.TransferSheetViewModel
import com.yzddmr6.prismspace.prism.service.FileBridgeService
import com.yzddmr6.prismspace.prism.service.prepareSystemFilePickerUsable
import com.yzddmr6.prismspace.prism.transfer.TransferEntry
import com.yzddmr6.prismspace.prism.transfer.TransferSheetState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen() {
    val vm: FilesViewModel = viewModel()
    val context = LocalContext.current
    val activity = context as? Activity
    val history by vm.history.collectAsState()
    val vendorCloneNotice by vm.vendorCloneNotice.collectAsState()
    val transferVm: TransferSheetViewModel = viewModel()
    var showReturnGuide by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // The picker result lands in this Activity, so the transfer runs here too: no URI grant is
    // handed to another component and no intent can ask to skip the confirmation.
    val sendFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        transferVm.startInApp(uris, TransferEntry.FilesPage)
    }

    // Refresh on first show and on every resume: a share-sheet transfer may have written a row
    // while this tab was in the background.
    LaunchedEffect(Unit) { vm.refresh() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    TransferSheetHost(transferVm, onClosed = { vm.refresh() })
    // Both ledger rows are written before the Result appears; show the "Sent" row right away.
    val sheet by transferVm.state.collectAsState()
    val showingResult = sheet is TransferSheetState.Result
    LaunchedEffect(showingResult) { if (showingResult) vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lz_pf_files_title)) },
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
            // ── 发送主卡（含空间门禁预检：空间不可用时不弹选择器，给状态引导） ──
            GroupCard(title = null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PrismSpacing.Lg, vertical = PrismSpacing.Lg),
                ) {
                    Text(
                        text = stringResource(R.string.lz_xfer_send_to_dual),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.lz_pf_files_send_other_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = PrismSpacing.Xs),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                // 空间门禁预检：与启动分身同一可用性来源，不可用即引导不发请求。
                                val gate = fileTransferGate(vm.dualUsability(), prismResolver(context))
                                if (!gate.enabled) {
                                    gate.guidance?.let {
                                        AppFeedbackBus.emit(ActionFeedback(it, isError = true))
                                    }
                                    return@launch
                                }
                                // The profile owner may have frozen the ROM's file picker together with
                                // other explicit system clones. Restore that required system surface at
                                // the point of use before Android resolves OPEN_DOCUMENT.
                                if (prepareSystemFilePickerUsable(context)) {
                                    sendFiles.launch(arrayOf("*/*"))
                                } else {
                                    Toast.makeText(context, R.string.lz_xfer_picker_unavailable, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = PrismSpacing.Md),
                    ) {
                        Text(stringResource(R.string.lz_pf_choose_files))
                    }
                }
            }

            // ── 传回教学（折叠） ─────────────────────────────────────────────
            GroupCard(title = null) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showReturnGuide = !showReturnGuide }
                            .padding(horizontal = PrismSpacing.Lg, vertical = PrismSpacing.Md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.lz_pf_files_return_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = PrismIcons.Chev,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.rotate(if (showReturnGuide) 90f else 0f),
                        )
                    }
                    // Always visible (outside the collapsed part): the notice must not hide behind a tap.
                    if (vendorCloneNotice) {
                        Text(
                            text = stringResource(R.string.lz_xfer_clone_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = PrismSpacing.Lg, end = PrismSpacing.Lg, bottom = PrismSpacing.Md),
                        )
                    }
                    if (showReturnGuide) {
                        GuideStep(1, stringResource(R.string.lz_pf_files_step1))
                        GuideStep(2, stringResource(R.string.lz_pf_files_step2))
                        GuideStep(3, stringResource(R.string.lz_pf_files_step3))

                        Text(
                            text = stringResource(R.string.lz_pf_files_tab_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = PrismSpacing.Lg, vertical = PrismSpacing.Sm),
                        )
                    }
                }
            }

            // ── Transfer ledger: files sent from here and files received here ──
            TransferHistoryList(
                records = history,
                currentIsParent = true,
                onClear = vm::clearHistory,
                onRemove = vm::removeRecord,
                onApkAction = {
                    // APK-suite rows keep their install entry, gated like the Home card.
                    scope.launch {
                        val gate = continueInstallGate(vm.dualUsability(), prismResolver(context))
                        if (!gate.enabled) {
                            gate.guidance?.let { AppFeedbackBus.emit(ActionFeedback(it, isError = true)) }
                            return@launch
                        }
                        activity?.let { host ->
                            val result = FileBridgeService().openProfileInstallEntry(host)
                            if (!result.success) AppFeedbackBus.emit(ActionFeedback(result.message, isError = true))
                        }
                    }
                },
            )

            Spacer(Modifier.height(PrismSpacing.Sm))
        }
    }
}

@Composable
private fun GuideStep(n: Int, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PrismSpacing.Lg, vertical = PrismSpacing.Sm),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "$n",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = PrismSpacing.Md),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
