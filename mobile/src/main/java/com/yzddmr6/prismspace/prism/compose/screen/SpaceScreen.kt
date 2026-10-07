package com.yzddmr6.prismspace.prism.compose.screen

import android.graphics.Bitmap
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.LinearProgressIndicator
import com.yzddmr6.prismspace.prism.service.SpaceIconLoader
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.graphics.RectangleShape
import com.yzddmr6.prismspace.prism.compose.theme.PrismMinTouchTarget
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yzddmr6.prismspace.controller.PrismAppClones
import com.yzddmr6.prismspace.prism.compose.component.AppActionSheet
import com.yzddmr6.prismspace.prism.compose.component.DisabledAlpha
import com.yzddmr6.prismspace.prism.compose.component.PrismIcons
import com.yzddmr6.prismspace.prism.compose.component.PrismTextButton
import com.yzddmr6.prismspace.prism.compose.component.SpaceSegmentChips
import com.yzddmr6.prismspace.prism.compose.component.SuspendRecoveryDialog
import com.yzddmr6.prismspace.prism.compose.nav.AppLaunchSignals
import com.yzddmr6.prismspace.prism.compose.nav.SYSTEM_APP_PICKER_ORIGIN_SPACE
import com.yzddmr6.prismspace.prism.compose.space.SpaceUsability
import com.yzddmr6.prismspace.prism.compose.space.selectedDualChipId
import com.yzddmr6.prismspace.prism.compose.space.spaceChips
import com.yzddmr6.prismspace.prism.compose.theme.LocalPrismExtraColors
import com.yzddmr6.prismspace.prism.compose.theme.PrismRadius
import com.yzddmr6.prismspace.prism.compose.theme.PrismSpacing
import com.yzddmr6.prismspace.prism.compose.vm.BatchAction
import com.yzddmr6.prismspace.prism.compose.vm.CloneFilter
import com.yzddmr6.prismspace.prism.compose.vm.SortOrder
import com.yzddmr6.prismspace.prism.compose.vm.SpaceRow
import com.yzddmr6.prismspace.prism.compose.vm.SpaceRowAction
import com.yzddmr6.prismspace.prism.compose.vm.SpaceSegment
import com.yzddmr6.prismspace.prism.compose.vm.SpaceSegmentState
import com.yzddmr6.prismspace.prism.compose.vm.SpaceUiState
import com.yzddmr6.prismspace.prism.compose.vm.SpaceViewModel
import com.yzddmr6.prismspace.prism.compose.vm.SpaceBrowseOptions
import com.yzddmr6.prismspace.prism.compose.vm.SpaceActionGate
import com.yzddmr6.prismspace.prism.compose.vm.ActionFeedback
import com.yzddmr6.prismspace.prism.compose.vm.AppFeedbackBus
import com.yzddmr6.prismspace.prism.compose.vm.batchActionsFor
import com.yzddmr6.prismspace.prism.compose.vm.batchAllSelected
import com.yzddmr6.prismspace.prism.compose.vm.continueInstallGate
import com.yzddmr6.prismspace.prism.compose.vm.prismResolver
import com.yzddmr6.prismspace.prism.compose.vm.uninstallGate
import com.yzddmr6.prismspace.prism.service.FileBridgeService
import com.yzddmr6.prismspace.prism.ui.PrismAppsViewModel
import com.yzddmr6.prismspace.mobile.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceScreen() {
    val vm: SpaceViewModel = viewModel()
    val prismAppsVm: PrismAppsViewModel = viewModel()
    val uiState by vm.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    // Clone uninstall is presented by the system uninstaller INSIDE the dual space (profile-routed
    // bridge command); the entry is gated on dual-space usability and fails closed with guidance.
    val uninstallEntry = uninstallGate(uiState.dualUsability, prismResolver(context))
    // Pending-install (待安装) continue entry: same usability gate, record stays untouched.
    val installEntry = continueInstallGate(uiState.dualUsability, prismResolver(context))

    // Uninstall (分身) goes through the system uninstaller (async, in another task). Refresh the list
    // whenever we come back to the foreground so a just-uninstalled clone disappears instead of lingering.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) vm.onHostPaused()
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.onHostResumed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            vm.onHostPaused()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Browsing choices live in the ViewModel saved state; each list saves its own scroll.
    // The action sheet and view menu are transient.
    val searchQuery = uiState.query
    val systemSearchQuery = uiState.systemQuery
    val listStates = rememberSaveableStateHolder()
    var systemAppsOpen by rememberSaveable { mutableStateOf(false) }
    var selectedRow by remember { mutableStateOf<SpaceRow?>(null) }
    var viewPanelExpanded by remember { mutableStateOf(false) }

    // Entry-time filtered result set: snapshotted as the multi-select domain on entry, so
    // search/filter/sort survive batch mode and 全选 covers exactly this set.
    val entryDomainRows = (uiState.current as? SpaceSegmentState.Content)?.rows.orEmpty()

    // Cross-tab entries: 首页「添加分身」直达主空间分段；设置「系统应用」直达双开系统应用视图。
    // Nonce signals: each collector acts once per nonce (acknowledged via lastHandled).
    // 结构性防呆：信号到达时若仍在多选态，先退出多选再切分段（快照域不被跨分段沿用）。
    var lastMainSegmentNonce by rememberSaveable { mutableStateOf(0) }
    var lastSystemAppsNonce by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        AppLaunchSignals.openSpaceMainSegment.collect { nonce ->
            if (nonce > 0 && nonce != lastMainSegmentNonce) {
                lastMainSegmentNonce = nonce
                vm.exitMultiSelect()
                systemAppsOpen = false
                vm.selectSegment(SpaceSegment.Main)
            }
        }
    }
    LaunchedEffect(Unit) {
        AppLaunchSignals.openSpaceSystemApps.collect { nonce ->
            if (nonce > 0 && nonce != lastSystemAppsNonce) {
                lastSystemAppsNonce = nonce
                vm.exitMultiSelect()
                vm.selectSegment(SpaceSegment.Dual)
                systemAppsOpen = true
            }
        }
    }

    // One-shot batch confirm. Uninstall is irreversible; clone runs serially after one OK.
    var pendingBatch by remember { mutableStateOf<BatchAction?>(null) }

    // If entering multi-select, dismiss any open sheet
    if (uiState.isMultiSelect && selectedRow != null) {
        selectedRow = null
    }

    // Hide the global 4-tab bottom nav while multi-selecting; always restore it on leave so a user
    // who navigates away mid-selection (or the screen recomposes out) never gets a stranded nav bar.
    LaunchedEffect(uiState.isMultiSelect) { AppLaunchSignals.setMultiSelectActive(uiState.isMultiSelect) }
    DisposableEffect(Unit) { onDispose { AppLaunchSignals.setMultiSelectActive(false) } }

    val totalInSegment = (uiState.current as? SpaceSegmentState.Content)
        ?.rows?.count { !it.system } ?: 0
    // 多选时以快照域为分母（全选/取消全选切换的口径与选择域一致）；非多选保持原分段口径。
    val allSelected = if (uiState.isMultiSelect) {
        batchAllSelected(uiState.multiSelectDomain?.size ?: 0, uiState.selectedCount)
    } else {
        totalInSegment > 0 && uiState.selectedCount >= totalInSegment
    }

    Scaffold(
        topBar = {
            SpaceTopBar(
                isMultiSelect = uiState.isMultiSelect,
                systemAppsOpen = systemAppsOpen,
                selectedCount = uiState.selectedCount,
                allSelected = allSelected,
                onSelectAll = { vm.selectAll() },
                onExit = { vm.exitMultiSelect() },
                onEnterBatch = { vm.enterMultiSelect(entryDomainRows) },
                canEnterBatch = !uiState.calculating,
            )
        },
        bottomBar = {
            if (uiState.isMultiSelect) {
                BatchBar(
                    segment = uiState.segment,
                    batchProgress = uiState.batchProgress,
                    uninstallEntry = uninstallEntry,
                    selectionEmpty = uiState.selectedCount == 0,
                    onAction = { action ->
                        when (action) {
                            // Confirm once before destructive uninstall / before the serial clone run.
                            BatchAction.Uninstall, BatchAction.CopyToDual -> pendingBatch = action
                            BatchAction.Freeze -> if (activity != null) vm.executeBatch(action, activity, prismAppsVm)
                        }
                    },
                    onUninstallBlocked = { uninstallEntry.guidance?.let(vm::reportTransientError) },
                    onCancel = { vm.exitMultiSelect() },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // The toolbar (search + view controls) stays visible during multi-select — frozen to
            // the entry-time query/filter, which the selection domain snapshots.
            SpaceToolbar(
                uiState = uiState,
                searchQuery = if (systemAppsOpen) systemSearchQuery else searchQuery,
                systemAppsOpen = systemAppsOpen,
                viewPanelExpanded = viewPanelExpanded,
                enabled = !uiState.isMultiSelect,
                onSelectMain = {
                    systemAppsOpen = false
                    vm.selectSegment(SpaceSegment.Main)
                },
                onSelectDual = {
                    systemAppsOpen = false
                    vm.selectSpace(it)
                },
                onSearchChanged = {
                    if (systemAppsOpen) vm.setSystemQuery(it) else vm.setQuery(it)
                },
                onCloseSystemApps = {
                    systemAppsOpen = false
                },
                onViewPanelToggle = { viewPanelExpanded = !viewPanelExpanded },
                onViewPanelDismiss = { viewPanelExpanded = false },
                onSortSelected = { order ->
                    vm.setSortOrder(order)
                    viewPanelExpanded = false
                },
                onCloneFilterSelected = { filter ->
                    vm.setCloneFilter(filter)
                    viewPanelExpanded = false
                },
                onShowSystemToggled = {
                    if (uiState.segment == SpaceSegment.Dual) vm.setShowSystemDual(!uiState.showSystemDual)
                    else vm.setShowSystem(!uiState.showSystem)
                },
                onRefresh = {
                    vm.refresh()
                    viewPanelExpanded = false
                },
                onAddSystemApps = {
                    viewPanelExpanded = false
                    uiState.spaces.firstOrNull { it.id == uiState.selectedDualSpaceId }?.let { space ->
                        AppLaunchSignals.signalOpenSystemAppPicker(space.userId, SYSTEM_APP_PICKER_ORIGIN_SPACE)
                    }
                },
            )

            uiState.refreshError?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = PrismSpacing.Lg))
            }
            if (uiState.selectedDualSpaceId != null && uiState.dualUsability != SpaceUsability.Usable &&
                uiState.current is SpaceSegmentState.Content) {
                Text(stringResource(R.string.lz_space_cached_list), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = PrismSpacing.Lg))
            }
            // Render the cached projection; each space owns its saved list state.
            val listKey = uiState.activeSpaceId + if (systemAppsOpen) ":system" else ":apps"
            listStates.SaveableStateProvider(listKey) {
                AppListSection(
                    listKey = listKey,
                    icons = vm.icons,
                    criteria = if (systemAppsOpen) SpaceBrowseOptions(systemQuery = systemSearchQuery) else uiState.browse.copy(systemQuery = ""),
                    projectionReady = !uiState.calculating,
                    segment = uiState.segment,
                    currentState = if (systemAppsOpen) uiState.systemApps else uiState.current,
                    systemAppsMode = systemAppsOpen,
                    isMultiSelect = uiState.isMultiSelect,
                    multiSelectDomain = uiState.multiSelectDomain,
                    selectedPkgs = uiState.selectedPkgs,
                    searchQuery = if (systemAppsOpen) systemSearchQuery else searchQuery,
                    dualUsability = uiState.dualUsability,
                    onToggleSelect = { vm.toggleSelect(it) },
                    onEnterMultiSelect = { vm.enterMultiSelect(it, entryDomainRows) },
                    onRowSelected = { selectedRow = it },
                    onRowPrimaryAction = { row, action ->
                        when (action) {
                            SpaceRowAction.Open -> vm.launch(context, row.pkg, SpaceSegment.Dual)
                            SpaceRowAction.Resume -> vm.setFrozen(row.pkg, false)
                            SpaceRowAction.AddSystemApp -> vm.setSystemAppInSpace(row.pkg, available = true)
                            SpaceRowAction.AddClone -> {
                                // Same entry as the action sheet: confirm sheet follows the configured
                                // method; the row button never skips it.
                                val app = vm.appFor(row.pkg, SpaceSegment.Main)
                                if (app != null && activity != null) {
                                    PrismAppClones(activity, prismAppsVm, app, onCloneStateChanged = vm::refresh).request()
                                }
                            }
                            SpaceRowAction.ContinueInstall -> {
                                // Pending-install row action: gated like the sheet's 继续安装 entry.
                                if (!installEntry.enabled) {
                                    installEntry.guidance?.let(vm::reportTransientError)
                                } else if (activity != null) {
                                    val result = FileBridgeService().openProfileInstallEntry(activity)
                                    if (!result.success) {
                                        AppFeedbackBus.emit(ActionFeedback(result.message, true))
                                    }
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    // Action sheet (only when not in multi-select)
    if (!uiState.isMultiSelect) {
        selectedRow?.let { row ->
            AppActionSheet(
                row = row,
                context = context,
                vm = vm,
                uninstallEntry = uninstallEntry,
                installEntry = installEntry,
                onDismiss = { selectedRow = null },
                onJumpDual = {
                    vm.selectSegment(SpaceSegment.Dual)
                },
            )
        }
    }

    uiState.mainCopyLostPackage?.let { pkg ->
        AlertDialog(
            onDismissRequest = { vm.clearMainCopyLostWarning() },
            title = { Text(stringResource(R.string.lz_space_main_copy_lost_title)) },
            text = { Text(stringResource(R.string.lz_space_main_copy_lost_body, pkg)) },
            confirmButton = {
                PrismTextButton(onClick = { vm.clearMainCopyLostWarning() }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    // ── Suspend dead-end escape: restore provably cannot lift a foreign suspension → offer
    //    the user-confirmed reinstall (data erasing) instead of a silently dead button. ──────
    uiState.suspendRecovery?.let { prompt ->
        SuspendRecoveryDialog(
            prompt = prompt,
            onReinstall = { vm.forceRecoverSuspendedClone() },
            onDismiss = { vm.dismissSuspendRecovery() },
        )
    }

    // ── Batch confirm (uninstall / clone) ──────────────────────────────────────
    pendingBatch?.let { action ->
        val count = uiState.selectedCount
        val (title, body, confirmLabel, danger) = when (action) {
            BatchAction.Uninstall -> BatchConfirmCopy(
                stringResource(R.string.lz_space_batch_uninstall_title, count),
                stringResource(R.string.lz_space_batch_uninstall_body, count),
                stringResource(R.string.lz_space_batch_uninstall_confirm), true,
            )
            else -> BatchConfirmCopy(
                stringResource(R.string.lz_space_batch_clone_title, count),
                stringResource(R.string.lz_space_batch_clone_body, count),
                stringResource(R.string.lz_space_batch_clone_confirm), false,
            )
        }
        BatchConfirmDialog(
            title = title,
            body = body,
            confirmLabel = confirmLabel,
            danger = danger,
            onConfirm = {
                pendingBatch = null
                if (activity != null) vm.executeBatch(action, activity, prismAppsVm)
            },
            onDismiss = { pendingBatch = null },
        )
    }
}

// ---------------------------------------------------------------------------
// Top app bar
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpaceTopBar(
    isMultiSelect: Boolean,
    systemAppsOpen: Boolean,
    selectedCount: Int,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onExit: () -> Unit,
    onEnterBatch: () -> Unit,
    canEnterBatch: Boolean,
) {
    TopAppBar(
        title = {
            Text(
                text = when {
                    isMultiSelect -> stringResource(R.string.lz_space_selected_count, selectedCount)
                    systemAppsOpen -> stringResource(R.string.lz_system_apps_title)
                    else -> stringResource(R.string.lz_space_title)
                },
                style = MaterialTheme.typography.titleLarge,
            )
        },
        actions = {
            if (isMultiSelect) {
                // 全选 toggles to 取消全选 once everything is selected; clearing all leaves multi-select.
                PrismTextButton(onClick = if (allSelected) onExit else onSelectAll) {
                    Text(if (allSelected) stringResource(R.string.lz_space_deselect_all) else stringResource(R.string.lz_space_select_all))
                }
                IconButton(onClick = onExit) {
                    Icon(
                        imageVector = PrismIcons.Close,
                        contentDescription = stringResource(R.string.lz_space_exit_multiselect),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else if (!systemAppsOpen) {
                // 批量管理是顶栏显式入口（不再只依赖长按）。
                PrismTextButton(onClick = onEnterBatch, enabled = canEnterBatch) {
                    Text(stringResource(R.string.lz_space_batch_manage))
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

// ---------------------------------------------------------------------------
// App list section (filter/sort/search + loading/empty/lazy)
// ---------------------------------------------------------------------------

@Composable
private fun AppListSection(
    listKey: String,
    icons: SpaceIconLoader,
    criteria: SpaceBrowseOptions,
    projectionReady: Boolean,
    segment: SpaceSegment,
    currentState: SpaceSegmentState,
    systemAppsMode: Boolean,
    isMultiSelect: Boolean,
    multiSelectDomain: List<SpaceRow>?,
    selectedPkgs: Set<String>?,
    searchQuery: String,
    dualUsability: SpaceUsability,
    onToggleSelect: (String) -> Unit,
    onEnterMultiSelect: (String) -> Unit,
    onRowSelected: (SpaceRow) -> Unit,
    onRowPrimaryAction: (SpaceRow, SpaceRowAction) -> Unit,
) {
    val listState = rememberLazyListState()
    var displayedCriteria by rememberSaveable { mutableStateOf(criteria) }
    LaunchedEffect(criteria, projectionReady) {
        // Explicitly changing a query/filter/sort starts its new result at the top. Returning to
        // the same space restores the saved criteria and therefore never triggers this reset.
        if (projectionReady && displayedCriteria != criteria) {
            listState.scrollToItem(0)
            displayedCriteria = criteria
        }
    }
    val allRows = (currentState as? SpaceSegmentState.Content)?.rows.orEmpty()
    val filteredRows = if (isMultiSelect) multiSelectDomain ?: allRows.filterNot { it.system } else allRows

    when {
        currentState is SpaceSegmentState.Loading -> {
            LoadingPlaceholder()
        }
        currentState is SpaceSegmentState.Unavailable -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.lz_setvm_state_refresh_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(PrismSpacing.Lg),
                )
            }
        }
        filteredRows.isEmpty() -> {
            EmptyPlaceholder(
                segment = segment,
                hasQuery = searchQuery.isNotBlank(),
                systemAppsMode = systemAppsMode,
                dualUsability = dualUsability,
            )
        }
        else -> {
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(horizontal = PrismSpacing.Lg)
                    .clip(RoundedCornerShape(PrismRadius.Lg))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(PrismSpacing.Hair, LocalPrismExtraColors.current.cardBorder, RoundedCornerShape(PrismRadius.Lg)),
                contentPadding = PaddingValues(0.dp),
            ) {
                items(filteredRows, key = { "$listKey:${it.pkg}" }) { row ->
                    val isSelected = selectedPkgs?.contains(row.pkg) == true
                    AppCard(
                        row = row,
                        icons = icons,
                        isMultiSelect = isMultiSelect,
                        isSelected = isSelected,
                        onClick = {
                            if (isMultiSelect) {
                                onToggleSelect(row.pkg)
                            } else {
                                onRowSelected(row)
                            }
                        },
                        onLongClick = {
                            if (!isMultiSelect && !systemAppsMode) {
                                onEnterMultiSelect(row.pkg)
                            }
                        },
                        onPrimaryAction = { action -> onRowPrimaryAction(row, action) },
                    )
                    if (row != filteredRows.last()) Divider(color = LocalPrismExtraColors.current.cardBorder,
                        modifier = Modifier.padding(horizontal = PrismSpacing.Lg))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Bottom batch action bar.
// Main:  复制到双开 / 取消
// Dual:  冻结 / 卸载 / 取消
// ---------------------------------------------------------------------------

@Composable
private fun BatchBar(
    segment: SpaceSegment,
    batchProgress: String?,
    uninstallEntry: SpaceActionGate,
    selectionEmpty: Boolean,
    onAction: (BatchAction) -> Unit,
    onUninstallBlocked: () -> Unit,
    onCancel: () -> Unit,
) {
    // While a batch is running the whole action row is inert (progress stays visible above);
    // no batch action can be re-triggered until the run ends, success or failure.
    // 空选择（显式「批量管理」入口刚进入）时动作不可用，只能先勾选或取消。
    val running = batchProgress != null
    val actionsEnabled = !running && !selectionEmpty
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material.Divider(color = MaterialTheme.colorScheme.outlineVariant)
            if (batchProgress != null) {
                Text(
                    text = batchProgress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PrismSpacing.Sm, vertical = PrismSpacing.Sm),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                val actions = batchActionsFor(segment)
                actions.forEach { action ->
                    when (action) {
                        BatchAction.CopyToDual -> {
                            BatchBarButton(
                                icon = PrismIcons.Add,
                                label = stringResource(R.string.lz_space_batch_copy_to_dual),
                                danger = false,
                                enabled = actionsEnabled,
                                onClick = { if (actionsEnabled) onAction(action) },
                            )
                        }
                        BatchAction.Freeze -> {
                            BatchBarButton(
                                icon = PrismIcons.Snow,
                                label = stringResource(R.string.lz_space_batch_freeze),
                                danger = false,
                                enabled = actionsEnabled,
                                onClick = { if (actionsEnabled) onAction(action) },
                            )
                        }
                        BatchAction.Uninstall -> {
                            // Gated on dual-space usability: a disabled button stays tappable and
                            // surfaces the state-specific guidance instead of firing any request.
                            BatchBarButton(
                                icon = PrismIcons.Trash,
                                label = stringResource(R.string.lz_space_batch_uninstall),
                                danger = true,
                                enabled = actionsEnabled && uninstallEntry.enabled,
                                onClick = {
                                    when {
                                        !actionsEnabled -> Unit
                                        uninstallEntry.enabled -> onAction(action)
                                        else -> onUninstallBlocked()
                                    }
                                },
                            )
                        }
                    }
                }
                BatchBarButton(
                    icon = PrismIcons.Close,
                    label = stringResource(R.string.lz_space_batch_cancel),
                    danger = false,
                    enabled = !running,
                    onClick = { if (!running) onCancel() },
                )
            }
        }
    }
}

@Composable
private fun BatchBarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    danger: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val color = (if (danger) MaterialTheme.colorScheme.error
                 else MaterialTheme.colorScheme.onSurface)
        .let { if (enabled) it else it.copy(alpha = DisabledAlpha) }
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = PrismSpacing.Md, vertical = PrismSpacing.Sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.height(PrismSpacing.Xs))
        Text(
            text = label,
            // 12sp 下限：labelSmall(11sp) 低于辅助/动作文字契约。
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

/** Copy + styling for a batch confirm prompt (kept as a value so the call site can destructure). */
private data class BatchConfirmCopy(
    val title: String,
    val body: String,
    val confirmLabel: String,
    val danger: Boolean,
)

@Composable
private fun BatchConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    danger: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            PrismTextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = if (danger) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            PrismTextButton(onClick = onDismiss) { Text(stringResource(R.string.lz_space_dialog_cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpaceToolbar(
    uiState: SpaceUiState,
    searchQuery: String,
    systemAppsOpen: Boolean,
    viewPanelExpanded: Boolean,
    enabled: Boolean,
    onSelectMain: () -> Unit,
    onSelectDual: (String) -> Unit,
    onSearchChanged: (String) -> Unit,
    onCloseSystemApps: () -> Unit,
    onViewPanelToggle: () -> Unit,
    onViewPanelDismiss: () -> Unit,
    onSortSelected: (SortOrder) -> Unit,
    onCloneFilterSelected: (CloneFilter) -> Unit,
    onShowSystemToggled: () -> Unit,
    onRefresh: () -> Unit,
    onAddSystemApps: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PrismSpacing.Lg),
    ) {
        // N-chip space switcher — horizontally scrollable row above the search box.
        // Hidden in multi-select: switching space would invalidate the snapshotted selection domain.
        run {
            val chips = spaceChips(
                spaces = uiState.spaces,
                selectedMain = uiState.segment == SpaceSegment.Main,
                selectedDualId = selectedDualChipId(uiState.segment, uiState.selectedDualSpaceId, uiState.spaces),
            )
            SpaceSegmentChips(
                chips = chips,
                onSelectMain = onSelectMain,
                onSelectDual = onSelectDual,
                enabled = enabled,
            )

            Spacer(modifier = Modifier.height(PrismSpacing.Sm))
        }

        // Search box + 「列表视图」单入口（排序/筛选/显示系统应用/刷新都收在这个面板内）。
        val placeholder = when {
            systemAppsOpen -> stringResource(R.string.lz_system_apps_search)
            uiState.segment == SpaceSegment.Dual -> stringResource(R.string.lz_space_search_dual)
            else -> stringResource(R.string.lz_space_search_main)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PrismSpacing.Sm),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChanged,
                modifier = Modifier.weight(1f),
                // Multi-select keeps the entry-time query visible but frozen (the selection
                // domain is a snapshot of it).
                enabled = enabled,
                placeholder = {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = PrismIcons.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(PrismRadius.Md),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )

            if (systemAppsOpen) {
                PrismTextButton(onClick = onCloseSystemApps) {
                    Text(stringResource(R.string.lz_system_apps_back))
                }
            } else {
                // 视图控制单入口；非默认视图状态时显示指示点。
                val showSystem = if (uiState.segment == SpaceSegment.Dual) uiState.showSystemDual else uiState.showSystem
                val viewDirty = uiState.sortOrder != SortOrder.Name ||
                    (uiState.segment == SpaceSegment.Main && uiState.cloneFilter != CloneFilter.All) ||
                    showSystem
                Box {
                IconButton(
                    onClick = onViewPanelToggle,
                    enabled = enabled,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = PrismIcons.Sort,
                        contentDescription = stringResource(R.string.lz_space_view_controls),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    if (viewDirty) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 9.dp, end = 9.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }

                ViewPanel(
                    expanded = viewPanelExpanded,
                    segment = uiState.segment,
                    sortOrder = uiState.sortOrder,
                    cloneFilter = uiState.cloneFilter,
                    showSystem = showSystem,
                    onDismiss = onViewPanelDismiss,
                    onSortSelected = onSortSelected,
                    onCloneFilterSelected = onCloneFilterSelected,
                    onShowSystemToggled = onShowSystemToggled,
                    onRefresh = onRefresh,
                    onAddSystemApps = onAddSystemApps,
                )
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(PrismSpacing.Sm)) {
            if (uiState.refreshing || uiState.calculating) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.Center))
        }
    }
}

// ---------------------------------------------------------------------------
// View panel (排序方式 / 筛选〔主空间〕 / 显示〔全部〕系统应用 / 添加系统应用〔双开〕 / 刷新) — the single entry
// right of the search box. The top bar carries no ⋯ overflow menu anymore.
// ---------------------------------------------------------------------------

@Composable
private fun ViewPanel(
    expanded: Boolean,
    segment: SpaceSegment,
    sortOrder: SortOrder,
    cloneFilter: CloneFilter,
    showSystem: Boolean,
    onDismiss: () -> Unit,
    onSortSelected: (SortOrder) -> Unit,
    onCloneFilterSelected: (CloneFilter) -> Unit,
    onShowSystemToggled: () -> Unit,
    onRefresh: () -> Unit,
    onAddSystemApps: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        // Section: 排序方式（诚实口径：名称 / 已添加优先；伪「安装时间」已移除）
        MenuSectionLabel(text = stringResource(R.string.lz_space_menu_sort))
        MenuCheckItem(
            label = stringResource(R.string.lz_space_menu_sort_name),
            checked = sortOrder == SortOrder.Name,
            onClick = { onSortSelected(SortOrder.Name) },
        )
        if (segment == SpaceSegment.Main) {
            MenuCheckItem(
                label = stringResource(R.string.lz_space_menu_sort_cloned),
                checked = sortOrder == SortOrder.Cloned,
                onClick = { onSortSelected(SortOrder.Cloned) },
            )
        }

        // Section: 筛选 (main segment only)
        if (segment == SpaceSegment.Main) {
            androidx.compose.material.Divider(color = MaterialTheme.colorScheme.outlineVariant)
            MenuSectionLabel(text = stringResource(R.string.lz_space_menu_filter))
            MenuCheckItem(
                label = stringResource(R.string.lz_space_menu_filter_all),
                checked = cloneFilter == CloneFilter.All,
                onClick = { onCloneFilterSelected(CloneFilter.All) },
            )
            MenuCheckItem(
                label = stringResource(R.string.lz_space_menu_filter_cloned_only),
                checked = cloneFilter == CloneFilter.Yes,
                onClick = { onCloneFilterSelected(CloneFilter.Yes) },
            )
            MenuCheckItem(
                label = stringResource(R.string.lz_space_menu_filter_not_cloned_only),
                checked = cloneFilter == CloneFilter.No,
                onClick = { onCloneFilterSelected(CloneFilter.No) },
            )
        }

        // Main: 显示系统应用 hides/shows system rows. Dual: system apps the policy keeps are normal rows
        // already; 显示全部系统应用 merges in every installed system package. Both default OFF.
        androidx.compose.material.Divider(color = MaterialTheme.colorScheme.outlineVariant)
        MenuCheckItem(
            label = stringResource(
                if (segment == SpaceSegment.Dual) R.string.lz_space_menu_show_all_system else R.string.lz_space_menu_show_system,
            ),
            checked = showSystem,
            onClick = onShowSystemToggled,
        )

        // The toolbar is the single view-control entry and the dual list has no section header,
        // so the system-app selection page hangs here.
        if (segment == SpaceSegment.Dual) {
            androidx.compose.material.Divider(color = MaterialTheme.colorScheme.outlineVariant)
            DropdownMenuItem(
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = PrismIcons.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.lz_space_add_system_apps),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
                onClick = onAddSystemApps,
            )
        }

        androidx.compose.material.Divider(color = MaterialTheme.colorScheme.outlineVariant)
        DropdownMenuItem(
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = PrismIcons.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.lz_space_menu_refresh),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            onClick = onRefresh,
        )
    }
}

@Composable
private fun MenuSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = androidx.compose.ui.Modifier.padding(horizontal = PrismSpacing.Lg, vertical = PrismSpacing.Sm),
    )
}

@Composable
private fun MenuCheckItem(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (checked) {
                    Icon(
                        imageVector = PrismIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        onClick = onClick,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCard(
    row: SpaceRow,
    icons: SpaceIconLoader,
    isMultiSelect: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPrimaryAction: (SpaceRowAction) -> Unit,
) {
    val extra = LocalPrismExtraColors.current
    val chipBg = if (row.chipOk) extra.okContainer else MaterialTheme.colorScheme.surfaceVariant
    val chipFg = if (row.chipOk) extra.ok else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = RectangleShape,
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,

    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Keep identity visible in multi-select; the selection check stays on the trailing side.
            AppIcon(row, icons)

            Spacer(modifier = Modifier.width(14.dp))

            // Text column
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (row.frozen) MaterialTheme.colorScheme.onSurface.copy(alpha = DisabledAlpha)
                            else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.segment == SpaceSegment.Main && !row.system) Text(
                    text = stringResource(when {
                        !row.cloneStateKnown -> R.string.lz_space_detail_unknown
                        row.cloned -> R.string.lz_space_detail_added
                        row.primaryAction == SpaceRowAction.ContinueInstall -> R.string.lz_space_detail_pending
                        else -> R.string.lz_space_detail_not_added
                    }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 状态标签：健康行不贴标签，仅异常状态（已暂停/待安装/系统应用）呈现。
                if (row.chipText != null && (row.segment == SpaceSegment.Dual || row.system)) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = chipBg,
                        contentColor = chipFg,
                    ) {
                        Text(
                            text = row.chipText,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = PrismSpacing.Sm, vertical = 3.dp),
                        )
                    }
                }
            }

            // 行内按钮 = 该行当前唯一主动作（打开/恢复/添加分身/去安装）；无动作不显示。
            if (isMultiSelect) SelectionCheckBadge(checked = isSelected)
            val action = row.primaryAction
            if (!isMultiSelect && action != null) {
                Spacer(modifier = Modifier.width(PrismSpacing.Sm))
                Button(
                    onClick = { onPrimaryAction(action) },
                    modifier = Modifier.defaultMinSize(minHeight = PrismMinTouchTarget),
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = PrismSpacing.Md),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (action == SpaceRowAction.Open) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.primary,
                        contentColor = if (action == SpaceRowAction.Open) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(
                        text = stringResource(
                            when (action) {
                                SpaceRowAction.Open -> R.string.lz_space_row_open
                                SpaceRowAction.Resume -> R.string.lz_space_row_resume
                                SpaceRowAction.AddClone -> R.string.lz_space_row_add_clone
                                SpaceRowAction.ContinueInstall -> R.string.lz_space_row_install
                                SpaceRowAction.AddSystemApp -> R.string.lz_space_row_add
                            }
                        ),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** Small selection check overlaid on the bottom-end corner of the app icon during multi-select.
 *  The badge carries a contentDescription so TalkBack reads the row's selection state. */
@Composable
private fun SelectionCheckBadge(checked: Boolean) {
    val description = stringResource(
        if (checked) R.string.lz_space_row_selected else R.string.lz_space_row_not_selected,
    )
    Box(
        modifier = Modifier
            .size(18.dp)
            .semantics { contentDescription = description }
            .clip(RoundedCornerShape(9.dp))
            .background(if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = 1.5.dp,
                color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(9.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = PrismIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(PrismSpacing.Md),
            )
        }
    }
}

@Composable
private fun AppIcon(row: SpaceRow, icons: SpaceIconLoader) {
    val pixels = with(LocalDensity.current) { 44.dp.roundToPx() }
    val dark = isSystemInDarkTheme()
    val bitmap by produceState<Bitmap?>(null, row.userId, row.pkg, row.iconVersion, pixels, dark) {
        value = icons.load(row.userId, row.pkg, row.iconVersion, pixels, dark)
    }
    val image = bitmap?.let { remember(it) { it.asImageBitmap() } }
    if (image != null) {
        Image(image, null, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
    } else {
        Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center) {
            Text(row.label.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun LoadingPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun EmptyPlaceholder(
    segment: SpaceSegment,
    hasQuery: Boolean,
    systemAppsMode: Boolean,
    dualUsability: SpaceUsability,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(PrismSpacing.Xxl),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // A light icon makes the empty state feel considered rather than like a blank string
            // (search glyph when filtering; the apps-grid otherwise to nudge toward cloning).
            Icon(
                imageVector = if (hasQuery) PrismIcons.Search else PrismIcons.Grid,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            )
            Spacer(modifier = Modifier.height(PrismSpacing.Md))
            Text(
                text = when {
                    systemAppsMode && !hasQuery -> stringResource(R.string.lz_system_apps_empty_search_title)
                    systemAppsMode -> stringResource(R.string.lz_system_apps_empty_result_title)
                    hasQuery -> stringResource(R.string.lz_space_empty_no_match)
                    segment == SpaceSegment.Dual &&
                        dualUsability == SpaceUsability.LockedNeedsUnlock -> stringResource(R.string.lz_space_empty_dual_locked)
                    segment == SpaceSegment.Dual &&
                        dualUsability == SpaceUsability.Suspended -> stringResource(R.string.lz_space_empty_dual_suspended)
                    segment == SpaceSegment.Dual &&
                        dualUsability == SpaceUsability.BridgeNotReady -> stringResource(R.string.lz_space_empty_dual_bridge)
                    segment == SpaceSegment.Dual &&
                        dualUsability == SpaceUsability.Unknown -> stringResource(R.string.lz_space_empty_dual_unknown)
                    segment == SpaceSegment.Dual -> stringResource(R.string.lz_space_empty_dual_none)
                    else -> stringResource(R.string.lz_space_empty_none)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(PrismSpacing.Sm))
            Text(
                text = when {
                    systemAppsMode && !hasQuery -> stringResource(R.string.lz_system_apps_empty_search_hint)
                    systemAppsMode -> stringResource(R.string.lz_system_apps_empty_result_hint)
                    hasQuery -> stringResource(R.string.lz_space_empty_no_match_hint)
                    segment == SpaceSegment.Dual &&
                        dualUsability == SpaceUsability.LockedNeedsUnlock -> stringResource(R.string.lz_space_empty_dual_locked_hint)
                    segment == SpaceSegment.Dual &&
                        dualUsability == SpaceUsability.Suspended -> stringResource(R.string.lz_space_empty_dual_suspended_hint)
                    segment == SpaceSegment.Dual &&
                        dualUsability == SpaceUsability.BridgeNotReady -> stringResource(R.string.lz_space_empty_dual_bridge_hint)
                    segment == SpaceSegment.Dual &&
                        dualUsability == SpaceUsability.Unknown -> stringResource(R.string.lz_space_empty_dual_unknown_hint)
                    segment == SpaceSegment.Dual -> stringResource(R.string.lz_space_empty_dual_none_hint)
                    else -> stringResource(R.string.lz_space_empty_none_hint)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
