package com.yzddmr6.prismspace.prism.compose.screen

import android.graphics.drawable.Drawable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yzddmr6.prismspace.prism.compose.component.AppActionSheet
import com.yzddmr6.prismspace.prism.compose.component.DisabledAlpha
import com.yzddmr6.prismspace.prism.compose.component.ExperimentalUnsupportedSheet
import com.yzddmr6.prismspace.prism.compose.component.PrismIcons
import com.yzddmr6.prismspace.prism.compose.component.SpaceSegmentChips
import com.yzddmr6.prismspace.prism.compose.nav.AppLaunchSignals
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
import com.yzddmr6.prismspace.prism.compose.vm.SpaceSegment
import com.yzddmr6.prismspace.prism.compose.vm.SpaceSegmentState
import com.yzddmr6.prismspace.prism.compose.vm.SpaceUiState
import com.yzddmr6.prismspace.prism.compose.vm.SpaceViewModel
import com.yzddmr6.prismspace.prism.compose.vm.applyListTransform
import com.yzddmr6.prismspace.prism.compose.vm.batchActionsFor
import com.yzddmr6.prismspace.prism.ui.PrismAppsViewModel
import com.yzddmr6.prismspace.mobile.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceScreen(nav: NavHostController) {
    val vm: SpaceViewModel = viewModel()
    val prismAppsVm: PrismAppsViewModel = viewModel()
    val uiState by vm.uiState.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.syncExperimentalFlag() }
    val activity = context as? FragmentActivity

    // Uninstall (分身) goes through the system uninstaller (async, in another task). Refresh the list
    // whenever we come back to the foreground so a just-uninstalled clone disappears instead of lingering.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // rememberSaveable: survives configuration change AND system process death
    // for the most user-affecting state (search query). selectedRow / menuExpanded
    // are transient sheet/menu state and stay on plain `remember`.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedRow by remember { mutableStateOf<SpaceRow?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }

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

    val totalInSegment = (uiState.current as? SpaceSegmentState.Content)?.rows?.size ?: 0
    val allSelected = uiState.isMultiSelect && totalInSegment > 0 && uiState.selectedCount >= totalInSegment

    Scaffold(
        topBar = {
            SpaceTopBar(
                isMultiSelect = uiState.isMultiSelect,
                selectedCount = uiState.selectedCount,
                allSelected = allSelected,
                onSelectAll = { vm.selectAll() },
                onExit = { vm.exitMultiSelect() },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // In multi-select, batch actions sit at the top (they used to be a bottom bar that
            // overlapped/duplicated the global 4-tab navigation). Otherwise the normal toolbar.
            if (uiState.isMultiSelect) {
                BatchBar(
                    segment = uiState.segment,
                    batchProgress = uiState.batchProgress,
                    onAction = { action ->
                        when (action) {
                            // Confirm once before destructive uninstall / before the serial clone run.
                            BatchAction.Uninstall, BatchAction.CopyToDual -> pendingBatch = action
                            BatchAction.Freeze -> if (activity != null) vm.executeBatch(action, activity, prismAppsVm)
                        }
                    },
                    onCancel = { vm.exitMultiSelect() },
                )
            } else {
                SpaceToolbar(
                    uiState = uiState,
                    searchQuery = searchQuery,
                    menuExpanded = menuExpanded,
                    onSelectMain = { vm.selectSegment(SpaceSegment.Main) },
                    onSelectDual = { vm.selectSpace(it) },
                    onCreate = { vm.createSpace() },
                    onSearchChanged = { searchQuery = it },
                    onMenuToggle = { menuExpanded = !menuExpanded },
                    onMenuDismiss = { menuExpanded = false },
                    onSortSelected = { order ->
                        vm.setSortOrder(order)
                        menuExpanded = false
                    },
                    onCloneFilterSelected = { filter ->
                        vm.setCloneFilter(filter)
                        menuExpanded = false
                    },
                    onShowSystemToggled = {
                        if (uiState.segment == SpaceSegment.Dual) vm.setShowSystemDual(!uiState.showSystemDual)
                        else vm.setShowSystem(!uiState.showSystem)
                    },
                    onRefresh = {
                        vm.refresh()
                        menuExpanded = false
                    },
                )
            }

            // App list — client-side filter/sort/search applied over loaded rows
            AppListSection(
                segment = uiState.segment,
                currentState = uiState.current,
                isMultiSelect = uiState.isMultiSelect,
                selectedPkgs = uiState.selectedPkgs,
                searchQuery = searchQuery,
                sortOrder = uiState.sortOrder,
                cloneFilter = uiState.cloneFilter,
                showSystem = if (uiState.segment == SpaceSegment.Dual) uiState.showSystemDual else uiState.showSystem,
                dualUsability = uiState.dualUsability,
                onToggleSelect = { vm.toggleSelect(it) },
                onEnterMultiSelect = { vm.enterMultiSelect(it) },
                onRowSelected = { selectedRow = it },
            )
        }
    }

    // Action sheet (only when not in multi-select)
    if (!uiState.isMultiSelect) {
        selectedRow?.let { row ->
            AppActionSheet(
                row = row,
                context = context,
                vm = vm,
                onDismiss = { selectedRow = null },
                onJumpDual = {
                    vm.selectSegment(SpaceSegment.Dual)
                },
            )
        }
    }

    uiState.experimentalCreateBlocked?.let { info ->
        ExperimentalUnsupportedSheet(info = info, onDismiss = { vm.clearExperimentalCreateBlocked() })
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
    selectedCount: Int,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onExit: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = if (isMultiSelect) stringResource(R.string.lz_space_selected_count, selectedCount)
                       else stringResource(R.string.lz_space_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        actions = {
            if (isMultiSelect) {
                // 全选 toggles to 取消全选 once everything is selected; clearing all leaves multi-select.
                TextButton(onClick = if (allSelected) onExit else onSelectAll) {
                    Text(if (allSelected) stringResource(R.string.lz_space_deselect_all) else stringResource(R.string.lz_space_select_all))
                }
                IconButton(onClick = onExit) {
                    Icon(
                        imageVector = PrismIcons.Close,
                        contentDescription = stringResource(R.string.lz_space_exit_multiselect),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
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
    segment: SpaceSegment,
    currentState: SpaceSegmentState,
    isMultiSelect: Boolean,
    selectedPkgs: Set<String>?,
    searchQuery: String,
    sortOrder: SortOrder,
    cloneFilter: CloneFilter,
    showSystem: Boolean,
    dualUsability: SpaceUsability,
    onToggleSelect: (String) -> Unit,
    onEnterMultiSelect: (String) -> Unit,
    onRowSelected: (SpaceRow) -> Unit,
) {
    val allRows = (currentState as? SpaceSegmentState.Content)?.rows ?: emptyList()
    val filteredRows = if (isMultiSelect) {
        allRows
    } else {
        applyListTransform(
            rows = allRows,
            segment = segment,
            query = searchQuery,
            sort = sortOrder,
            cloneFilter = cloneFilter,
            showSystem = showSystem,
        )
    }

    when {
        currentState is SpaceSegmentState.Loading -> {
            LoadingPlaceholder()
        }
        filteredRows.isEmpty() -> {
            EmptyPlaceholder(
                segment = segment,
                hasQuery = searchQuery.isNotBlank(),
                dualUsability = dualUsability,
            )
        }
        else -> {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = PrismSpacing.Lg, vertical = PrismSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(filteredRows, key = { it.pkg }) { row ->
                    val isSelected = selectedPkgs?.contains(row.pkg) == true
                    AppCard(
                        row = row,
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
                            if (!isMultiSelect) {
                                onEnterMultiSelect(row.pkg)
                            }
                        },
                    )
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
    onAction: (BatchAction) -> Unit,
    onCancel: () -> Unit,
) {
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
                                onClick = { onAction(action) },
                            )
                        }
                        BatchAction.Freeze -> {
                            BatchBarButton(
                                icon = PrismIcons.Snow,
                                label = stringResource(R.string.lz_space_batch_freeze),
                                danger = false,
                                onClick = { onAction(action) },
                            )
                        }
                        BatchAction.Uninstall -> {
                            BatchBarButton(
                                icon = PrismIcons.Trash,
                                label = stringResource(R.string.lz_space_batch_uninstall),
                                danger = true,
                                onClick = { onAction(action) },
                            )
                        }
                    }
                }
                BatchBarButton(
                    icon = PrismIcons.Close,
                    label = stringResource(R.string.lz_space_batch_cancel),
                    danger = false,
                    onClick = onCancel,
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
    onClick: () -> Unit,
) {
    val color = if (danger) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
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
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
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
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = if (danger) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.lz_space_dialog_cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpaceToolbar(
    uiState: SpaceUiState,
    searchQuery: String,
    menuExpanded: Boolean,
    onSelectMain: () -> Unit,
    onSelectDual: (String) -> Unit,
    onCreate: () -> Unit,
    onSearchChanged: (String) -> Unit,
    onMenuToggle: () -> Unit,
    onMenuDismiss: () -> Unit,
    onSortSelected: (SortOrder) -> Unit,
    onCloneFilterSelected: (CloneFilter) -> Unit,
    onShowSystemToggled: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PrismSpacing.Lg),
    ) {
        // N-chip space switcher — horizontally scrollable row above the search box
        val chips = spaceChips(
            spaces = uiState.spaces,
            selectedMain = uiState.segment == SpaceSegment.Main,
            selectedDualId = selectedDualChipId(uiState.segment, uiState.selectedDualSpaceId, uiState.spaces),
            showCreate = uiState.experimentalMultiProfile,
        )
        SpaceSegmentChips(
            chips = chips,
            onSelectMain = onSelectMain,
            onSelectDual = onSelectDual,
            onCreate = onCreate,
        )

        Spacer(modifier = Modifier.height(PrismSpacing.Sm))

        // Search box + ⋯ button in one row
        val placeholder = if (uiState.segment == SpaceSegment.Dual) stringResource(R.string.lz_space_search_dual) else stringResource(R.string.lz_space_search_main)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PrismSpacing.Sm),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChanged,
                modifier = Modifier.weight(1f),
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

            // ⋯ overflow menu button anchored to the RIGHT of the search box
            Box {
                IconButton(
                    onClick = onMenuToggle,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = PrismIcons.Dots,
                        contentDescription = stringResource(R.string.lz_space_more_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }

                OverflowMenu(
                    expanded = menuExpanded,
                    segment = uiState.segment,
                    sortOrder = uiState.sortOrder,
                    cloneFilter = uiState.cloneFilter,
                    showSystem = if (uiState.segment == SpaceSegment.Dual) uiState.showSystemDual else uiState.showSystem,
                    onDismiss = onMenuDismiss,
                    onSortSelected = onSortSelected,
                    onCloneFilterSelected = onCloneFilterSelected,
                    onShowSystemToggled = onShowSystemToggled,
                    onRefresh = onRefresh,
                )
            }
        }

        Spacer(modifier = Modifier.height(PrismSpacing.Sm))
    }
}

// ---------------------------------------------------------------------------
// Overflow menu (排序方式 / 筛选〔主空间〕 / 操作).
// ---------------------------------------------------------------------------

@Composable
private fun OverflowMenu(
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
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        // Section: 排序方式
        MenuSectionLabel(text = stringResource(R.string.lz_space_menu_sort))
        MenuCheckItem(
            label = stringResource(R.string.lz_space_menu_sort_name),
            checked = sortOrder == SortOrder.Name,
            onClick = { onSortSelected(SortOrder.Name) },
        )
        MenuCheckItem(
            label = stringResource(R.string.lz_space_menu_sort_time),
            checked = sortOrder == SortOrder.Time,
            onClick = { onSortSelected(SortOrder.Time) },
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

        // 显示系统应用: dual lists launchable system apps; toggle defaults ON.
        androidx.compose.material.Divider(color = MaterialTheme.colorScheme.outlineVariant)
        MenuCheckItem(
            label = stringResource(R.string.lz_space_menu_show_system),
            checked = showSystem,
            onClick = onShowSystemToggled,
        )

        // Section: 操作
        androidx.compose.material.Divider(color = MaterialTheme.colorScheme.outlineVariant)
        MenuSectionLabel(text = stringResource(R.string.lz_space_menu_actions))
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
    isMultiSelect: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val extra = LocalPrismExtraColors.current
    val context = LocalContext.current
    val chipBg = if (row.chipOk) extra.okContainer else MaterialTheme.colorScheme.surfaceVariant
    val chipFg = if (row.chipOk) extra.ok else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = RoundedCornerShape(PrismRadius.Lg),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(
            width = PrismSpacing.Hair,
            color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Keep the app icon visible in multi-select; overlay a selection check in the corner
            // (the icon used to be replaced by a checkbox, hiding which app it is).
            Box(contentAlignment = Alignment.BottomEnd) {
                AppIcon(pkg = row.pkg, label = row.label)
                if (isMultiSelect) SelectionCheckBadge(checked = isSelected)
            }

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
                Text(
                    text = row.pkg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(6.dp))
                // Status chip
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = chipBg,
                    contentColor = chipFg,
                ) {
                    Text(
                        text = row.chipText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = PrismSpacing.Sm, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

/** Small selection check overlaid on the bottom-end corner of the app icon during multi-select. */
@Composable
private fun SelectionCheckBadge(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(18.dp)
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
private fun AppIcon(pkg: String, label: String) {
    val context = LocalContext.current
    val icon: Drawable? = remember(pkg) {
        try { context.packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }
    }

    if (icon != null) {
        val bmp = remember(icon) { icon.toBitmap(48, 48).asImageBitmap() }
        Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
    } else {
        // Fallback: colored circle with initial
        val initial = label.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
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
