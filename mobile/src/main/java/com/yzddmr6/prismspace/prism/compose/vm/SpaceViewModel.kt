package com.yzddmr6.prismspace.prism.compose.vm

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.os.Build
import android.os.SystemClock
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.controller.CloneSuspendRecovery
import com.yzddmr6.prismspace.controller.PrismAppClones
import com.yzddmr6.prismspace.controller.PrismAppControl
import com.yzddmr6.prismspace.controller.ClonePreparationStore
import com.yzddmr6.prismspace.controller.SystemAppSelectionClient
import com.yzddmr6.prismspace.controller.UserCloneRegistry
import com.yzddmr6.prismspace.prism.service.ProfileBridgeResult
import com.yzddmr6.prismspace.prism.service.profileBridgeFailureMessage
import com.yzddmr6.prismspace.data.PrismAppListProvider
import com.yzddmr6.prismspace.engine.LaunchResult
import com.yzddmr6.prismspace.prism.compose.space.PrismSpace
import com.yzddmr6.prismspace.prism.compose.space.PrismSpaceKind
import com.yzddmr6.prismspace.prism.compose.space.resolveSpaceSelection
import com.yzddmr6.prismspace.prism.compose.space.SpaceRepository
import com.yzddmr6.prismspace.prism.compose.space.SpaceRepositoryProvider
import com.yzddmr6.prismspace.prism.compose.space.SpaceSnapshot
import com.yzddmr6.prismspace.prism.compose.space.SpaceStateRepository
import com.yzddmr6.prismspace.prism.compose.space.SpaceUsability
import com.yzddmr6.prismspace.prism.service.ProfileUninstallLauncher
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId
import com.yzddmr6.prismspace.data.PrismAppInfo
import com.yzddmr6.prismspace.data.helper.installed
import com.yzddmr6.prismspace.prism.ui.PrismAppsViewModel
import com.yzddmr6.prismspace.util.LauncherAppsCompat
import com.yzddmr6.prismspace.util.UserHandles
import java.text.Collator
import java.util.Locale
import android.util.LruCache
import com.yzddmr6.prismspace.util.PrismLocale
import com.yzddmr6.prismspace.prism.service.SpaceIconLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// Segment — which tab is active
// ---------------------------------------------------------------------------

enum class SpaceSegment { Main, Dual }

// ---------------------------------------------------------------------------
// Pure data transfer object — Android-free, unit-testable
// ---------------------------------------------------------------------------

internal data class SpaceAppInput(
    val pkg: String,
    val label: String,
    val frozen: Boolean,
    val suspended: Boolean,
    val launchable: Boolean,
    val system: Boolean,
    val cloned: Boolean,       // true = this main-space app is also in dual profile
    val prepared: Boolean = false,
    val segment: SpaceSegment,
    val critical: Boolean = false,
    val userId: Int = 0,
    val iconVersion: String = "",
    val cloneStateKnown: Boolean = true,
    /** Dual segment: the unified verdict; null derives it from [launchable] (main segment, tests). */
    val launchability: AppLaunchability? = null,
    /** Dual segment: a system package the space's policy keeps out ("not added"). */
    val policyHidden: Boolean = false,
    /** Dual segment: opened through this action inside the profile (no launcher entry). */
    val entryAction: String? = null,
)

/** The row's single next-step action rendered as its inline button; null = no action row button. */
enum class SpaceRowAction { Open, Resume, AddClone, ContinueInstall, AddSystemApp }

/** The dual-segment freeze entry of the action sheet. */
enum class DualFreezeAction { Freeze, Unfreeze, KeptAvailable }

/** Critical packages are kept available by PrismSpace (provisioning re-enables, unhides and
 *  unsuspends them), so they never offer a freeze action. A paused critical package left behind by
 *  an older version still offers unfreeze so the user can recover it. */
fun dualFreezeAction(row: SpaceRow): DualFreezeAction = when {
    row.frozen || row.suspended -> DualFreezeAction.Unfreeze
    row.critical -> DualFreezeAction.KeptAvailable
    else -> DualFreezeAction.Freeze
}

// ---------------------------------------------------------------------------
// Pure row model surfaced to the Compose UI
// ---------------------------------------------------------------------------

data class SpaceRow(
    val pkg: String,
    val label: String,
    val frozen: Boolean,
    val suspended: Boolean,
    val launchable: Boolean,
    val system: Boolean,
    val cloned: Boolean,
    val prepared: Boolean,
    val segment: SpaceSegment,
    val chipText: String?,     // status tag text; null = healthy rows carry no tag
    val chipOk: Boolean,       // true → ok-green, false → muted/warn
    val primaryAction: SpaceRowAction? = null,   // the row's only inline next-step action
    val critical: Boolean = false,
    val userId: Int = 0,
    val iconVersion: String = "",
    val cloneStateKnown: Boolean = true,
    val launchability: AppLaunchability = if (launchable) AppLaunchability.Launchable else AppLaunchability.NoLauncherEntry,
    val policyHidden: Boolean = false,
    /** Opened through a profile-side action rather than a launcher activity (no pinned shortcut). */
    val entryAction: String? = null,
)

/** What the dual-segment action sheet offers for a system package's membership in the space. */
enum class SystemMembershipAction { Add, Remove, None }

/** Critical packages are kept by PrismSpace; packages without a launcher entry are platform
 *  components the policy never hides, so neither offers a removal. */
fun systemMembershipAction(row: SpaceRow): SystemMembershipAction = when {
    !row.system || row.segment != SpaceSegment.Dual -> SystemMembershipAction.None
    row.policyHidden -> SystemMembershipAction.Add
    row.critical || row.launchability == AppLaunchability.NoLauncherEntry -> SystemMembershipAction.None
    else -> SystemMembershipAction.Remove
}

// ---------------------------------------------------------------------------
// Pure mapper — the only business logic owned by this layer
// Tag honesty: healthy rows carry NO tag; only exceptional states are labelled.
//   Dual: 未添加 (policy-hidden) > 已暂停 > 无界面 > 系统应用; healthy user apps → no tag.
//   Main: prepared → "待安装"; cloned / not-cloned → no tag (the row action carries state).
// Inline action: dual → 添加/恢复/打开 by the unified launchability, never keyed on "system";
//   main → 添加分身/去安装; added rows → none.
// ---------------------------------------------------------------------------

/** Dual rows: [SpaceAppInput.launchability] when collected, else derived from the legacy flags. */
internal fun dualLaunchability(app: SpaceAppInput): AppLaunchability = app.launchability
    ?: resolveLaunchability(if (app.launchable) true else null, app.frozen, app.suspended)

internal fun mapRows(inputs: List<SpaceAppInput>, res: StringResolver): List<SpaceRow> = inputs.map { app ->
    val launchability = if (app.segment == SpaceSegment.Dual) dualLaunchability(app)
        else if (app.launchable) AppLaunchability.Launchable else AppLaunchability.NoLauncherEntry
    val (chipText, chipOk) = when (app.segment) {
        SpaceSegment.Dual -> when {
            // A policy-hidden package is hidden too; "not added" is the truthful state, not "paused".
            app.policyHidden -> res(R.string.lz_vm_chip_not_added, emptyArray()) to false
            // Truthful badge: 已暂停 covers both freeze mechanisms and any lingering
            // suspended state, so a paused clone never reads as running.
            app.frozen || app.suspended -> res(R.string.lz_vm_chip_paused, emptyArray()) to false
            launchability == AppLaunchability.NoLauncherEntry -> res(R.string.lz_vm_chip_no_ui, emptyArray()) to false
            app.system -> res(R.string.lz_vm_chip_system, emptyArray()) to false
            else -> null to true
        }
        SpaceSegment.Main -> when {
            app.prepared -> res(R.string.lz_vm_chip_pending_install, emptyArray()) to false
            else -> null to true
        }
    }
    val primaryAction = when (app.segment) {
        SpaceSegment.Dual -> when {
            app.policyHidden -> SpaceRowAction.AddSystemApp
            else -> when (launchability) {
                AppLaunchability.Paused -> SpaceRowAction.Resume
                AppLaunchability.Launchable -> SpaceRowAction.Open
                AppLaunchability.NoLauncherEntry -> null
            }
        }
        SpaceSegment.Main -> when {
            !app.cloneStateKnown -> null
            app.prepared -> SpaceRowAction.ContinueInstall
            app.cloned -> null
            else -> SpaceRowAction.AddClone
        }
    }
    SpaceRow(
        pkg       = app.pkg,
        label     = app.label,
        frozen    = app.frozen,
        suspended = app.suspended,
        // A paused package with an entry can still be launched (the sheet resumes it first).
        launchable = if (app.segment == SpaceSegment.Dual) launchability != AppLaunchability.NoLauncherEntry && !app.policyHidden
            else app.launchable,
        system    = app.system,
        cloned    = app.cloned,
        prepared  = app.prepared,
        segment   = app.segment,
        chipText  = chipText,
        chipOk    = chipOk,
        primaryAction = primaryAction,
        critical  = app.critical,
        userId = app.userId,
        iconVersion = app.iconVersion,
        cloneStateKnown = app.cloneStateKnown,
        launchability = launchability,
        policyHidden = app.policyHidden,
        entryAction = app.entryAction,
    )
}

/** System packages exist in managed profiles for platform reasons; only an explicit marker makes one a user clone. */
internal fun mainAppIsCloned(isSystem: Boolean, installedInDual: Boolean, systemCloneMarked: Boolean): Boolean =
    if (isSystem) systemCloneMarked else installedInDual

/**
 * The system-app view is intentionally search-first: a managed profile can contain hundreds of
 * packages, so a blank query reveals nothing and cannot invite accidental bulk operations.
 */
internal fun filterSystemAppRows(rows: List<SpaceRow>, query: String): List<SpaceRow> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return emptyList()
    return rows.asSequence()
        .filter { it.system }
        .filter { it.label.lowercase().contains(needle) || it.pkg.lowercase().contains(needle) }
        .sortedBy { it.label.lowercase() }
        .toList()
}

// ---------------------------------------------------------------------------
// Filter, sort, and search model.
// ---------------------------------------------------------------------------

enum class SortOrder { Name, Cloned }

enum class CloneFilter { All, Yes, No }

/**
 * Pure client-side list transform: search → filter → sort.
 *
 * - search:      matches label OR packageName, case-insensitive; applied to both segments.
 * - showSystem:  MAIN segment hides system apps (row.system == true) when false. The DUAL segment
 *                keeps policy-enabled system apps as normal rows; its toggle merges in every installed
 *                system package upstream (see [mergeAllSystemRows]), so no filtering happens here.
 * - cloneFilter: All / Yes / No clone filter; MAIN segment only.
 * - sort Name:   localized name collation with package-name tie-breaker (both segments).
 * - sort Cloned: 已添加优先 — cloned-first (already-cloned at top), then name; MAIN segment only.
 *                Falls back to Name sort for Dual segment.
 *
 * No backend or provider changes — operates only on the already-loaded in-memory list.
 * (The pseudo "install time" ordering was removed: load order never equaled install time.)
 */
internal fun applyListTransform(
    rows: List<SpaceRow>,
    segment: SpaceSegment,
    query: String,
    sort: SortOrder,
    cloneFilter: CloneFilter,
    showSystem: Boolean,
    locale: Locale = Locale.getDefault(),
): List<SpaceRow> {
    var result = rows

    // 1. Search (both segments)
    if (query.isNotBlank()) {
        val q = query.lowercase()
        result = result.filter {
            it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q)
        }
    }

    // 2a. System-app visibility belongs to the selected space.
    if (segment == SpaceSegment.Main && !showSystem) result = result.filter { !it.system }
    // 2b. Clone filter (main segment only)
    if (segment == SpaceSegment.Main) {
        result = when (cloneFilter) {
            CloneFilter.Yes -> result.filter { it.cloned }
            CloneFilter.No  -> result.filter { it.cloneStateKnown && !it.cloned }
            CloneFilter.All -> result
        }
    }

    // Locale-aware, total ordering: identical names never inherit mutable provider-map order.
    val collator = Collator.getInstance(locale)
    val byName = Comparator<SpaceRow> { a, b ->
        collator.compare(a.label, b.label).takeIf { it != 0 } ?: a.pkg.compareTo(b.pkg)
    }
    val comparator = if (sort == SortOrder.Cloned && segment == SpaceSegment.Main)
        compareByDescending<SpaceRow> { it.cloned }.then(byName) else byName
    result = result.sortedWith(comparator)

    return result
}

/** Dual「显示全部系统应用」: normal rows plus every installed system package, deduplicated by package. */
internal fun mergeAllSystemRows(normal: List<SpaceAppInput>, system: List<SpaceAppInput>): List<SpaceAppInput> {
    val seen = normal.mapTo(HashSet()) { it.pkg }
    return normal + system.filter { seen.add(it.pkg) }
}

/** Dual segment: normal rows are user apps plus system apps the policy keeps that have a launcher entry. */
internal fun isDualNormalRow(system: Boolean, shownAsEnabled: Boolean, policyHidden: Boolean, launchability: AppLaunchability): Boolean =
    shownAsEnabled && (!system || (!policyHidden && launchability != AppLaunchability.NoLauncherEntry))

// ---------------------------------------------------------------------------
// UI state — one per segment
// ---------------------------------------------------------------------------

sealed interface SpaceSegmentState {
    object Loading : SpaceSegmentState
    object Unavailable : SpaceSegmentState
    object Empty : SpaceSegmentState
    data class Content(val rows: List<SpaceRow>) : SpaceSegmentState
}

data class SpaceUiState(
    val segment: SpaceSegment = SpaceSegment.Dual,
    val dual: SpaceSegmentState = SpaceSegmentState.Loading,
    val main: SpaceSegmentState = SpaceSegmentState.Loading,
    val systemApps: SpaceSegmentState = SpaceSegmentState.Loading,
    // Multi-select: null = not in multi-select mode; non-null = set of selected pkgs
    val selectedPkgs: Set<String>? = null,
    // Snapshot of the entry-time filtered result set (search/filter/sort applied, system rows
    // removed) — the domain for selection, 全选, and counts while multi-select is active.
    val multiSelectDomain: List<SpaceRow>? = null,
    // Batch progress message while a batch op is running, null otherwise
    val batchProgress: String? = null,
    val browsing: Map<String, SpaceBrowseOptions> = emptyMap(),
    val refreshing: Boolean = false,
    val calculating: Boolean = false,
    val refreshError: String? = null,
    val selectedDualSpaceId: String? = null,
    val spaces: List<PrismSpace> = emptyList(),
    val feedbackMessage: String? = null,
    val feedbackIsError: Boolean = false,
    val dualUsability: SpaceUsability = SpaceUsability.Unknown,
    val mainCopyLostPackage: String? = null,
    // Dead-end escape offer: set when a restore left the clone suspended by a foreign suspender.
    val suspendRecovery: SuspendRecoveryPrompt? = null,
) {
    val activeSpaceId: String get() = if (segment == SpaceSegment.Main) "main" else selectedDualSpaceId ?: "dual"
    val browse: SpaceBrowseOptions get() = browsing[activeSpaceId] ?: SpaceBrowseOptions()
    val sortOrder: SortOrder get() = browse.sort
    val cloneFilter: CloneFilter get() = browse.filter
    val query: String get() = browse.query
    val systemQuery: String get() = browse.systemQuery
    val showSystem: Boolean get() = browsing["main"]?.showSystem ?: false
    val showSystemDual: Boolean get() = browsing[selectedDualSpaceId]?.showSystem ?: false
    val current: SpaceSegmentState get() = if (segment == SpaceSegment.Dual) dual else main
    val dualCount: Int get() = (dual as? SpaceSegmentState.Content)?.rows?.size ?: 0
    val mainCount: Int get() = (main as? SpaceSegmentState.Content)?.rows?.size ?: 0
    val isMultiSelect: Boolean get() = selectedPkgs != null
    val selectedCount: Int get() = selectedPkgs?.size ?: 0
}

// ---------------------------------------------------------------------------
// Batch action types per segment — pure, unit-testable
// ---------------------------------------------------------------------------

enum class BatchAction { Freeze, Uninstall, CopyToDual }

/** Offered when a restore could not lift a foreign suspension: pkg + suspender (null = unknown). */
data class SuspendRecoveryPrompt(val pkg: String, val suspender: String?)

/** Which batch actions are available for a given segment. */
internal fun batchActionsFor(segment: SpaceSegment): List<BatchAction> = when (segment) {
    SpaceSegment.Main -> listOf(BatchAction.CopyToDual)
    SpaceSegment.Dual -> listOf(BatchAction.Freeze, BatchAction.Uninstall)
}

// ---------------------------------------------------------------------------
// ViewModel — bridges the Compose UI to space/app data via SpaceRepository.
// App enumeration uses SpaceRepository; execution paths retain fresh capability checks.
// ---------------------------------------------------------------------------

class SpaceViewModel(app: Application, private val savedState: SavedStateHandle) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(SpaceUiState(
        browsing = savedState.get<HashMap<String, SpaceBrowseOptions>>("space_browsing")?.toMap().orEmpty(),
        selectedDualSpaceId = savedState["selected_dual"],
        segment = savedState.get<String>("space_segment")?.let { runCatching { SpaceSegment.valueOf(it) }.getOrNull() } ?: SpaceSegment.Dual,
    ))
    val uiState: StateFlow<SpaceUiState> = _uiState
    private val spaceRepo: SpaceRepository by lazy { SpaceRepositoryProvider.get(getApplication()) }
    private val stateRepo: SpaceStateRepository by lazy { SpaceStateRepository(getApplication()) }
    private var uninstallHostResumed = false
    private var uninstallQueue = UninstallQueueState()
    private var uninstallValidationJob: Job? = null
    private var uninstallSkipped = 0
    private var uninstallLaunchInFlight: UninstallRequest? = null
    private val uninstallLaunchPort = UninstallLaunchPort { request ->
        when (val result = ProfileUninstallLauncher().requestUninstall(
            getApplication(), request.packageName, request.targetUserId, canLaunch = { uninstallHostResumed },
        )) {
            ProfileUninstallLauncher.Result.Launched -> UninstallLaunchReply.Launched
            is ProfileUninstallLauncher.Result.Failed -> UninstallLaunchReply.Failed(result.message, result.reason)
        }
    }

    private data class SpaceApps(
        val apps: Map<String, PrismAppInfo>,
        val normal: List<SpaceAppInput>,
        val system: List<SpaceAppInput>,
    )
    private data class SpaceView(val normal: SpaceSegmentState, val system: SpaceSegmentState)
    @Volatile private var snapshots: Map<String, SpaceApps> = emptyMap()
    private var views: Map<String, SpaceView> = emptyMap()
    private val labels = LruCache<String, String>(2048)
    private var labelLocale = PrismLocale.wrap(app).resources.configuration.locales[0].toLanguageTag()
    private data class ProjectionInput(
        val normal: List<SpaceAppInput>, val system: List<SpaceAppInput>, val options: SpaceBrowseOptions,
        val targetPackages: Set<String>?, val hasTarget: Boolean, val pending: Set<String>,
        val marked: Set<String>, val locale: Locale,
    )
    private var projections: Map<String, Pair<ProjectionInput, SpaceView>> = emptyMap()
    private var projectionVersion = 0
    private var projectionJob: Job? = null
    val icons = SpaceIconLoader(app, viewModelScope)
    private val reloads = SpaceReloadQueue(viewModelScope, onFailure = ::refreshFailed) { reload(it) }

    init {
        viewModelScope.launch {
            stateRepo.state.collectLatest { snapshot ->
                when (snapshot) {
                    SpaceSnapshot.Loading -> publishViews(_uiState.value.copy(dualUsability = SpaceUsability.Unknown))
                    is SpaceSnapshot.Failed -> publishViews(_uiState.value.copy(
                        dualUsability = SpaceUsability.Unknown,
                        refreshError = app.getString(R.string.lz_setvm_state_refresh_failed),
                    ))
                    is SpaceSnapshot.Loaded -> reloads.request(SpaceReloadRequest())
                }
            }
        }
        viewModelScope.launch {
            spaceRepo.appChanges().collect { users ->
                if (users.isNotEmpty()) reloads.request(SpaceReloadRequest(users, reason = "callback"))
            }
        }
    }

    /** Switching known views only selects a cached projection; it never refreshes both lists. */
    fun selectSegment(segment: SpaceSegment) {
        if (_uiState.value.batchProgress != null || _uiState.value.segment == segment) return
        publishViews(_uiState.value.copy(segment = segment))
        savedState["space_segment"] = segment.name
    }

    fun selectSpace(dualSpaceId: String) {
        val current = _uiState.value
        if (current.batchProgress != null || (current.segment == SpaceSegment.Dual && current.selectedDualSpaceId == dualSpaceId)) return
        val space = current.spaces.firstOrNull { it.id == dualSpaceId && it.kind == PrismSpaceKind.Dual } ?: return
        val targetChanged = current.selectedDualSpaceId != dualSpaceId
        publishViews(current.copy(segment = SpaceSegment.Dual, selectedDualSpaceId = dualSpaceId))
        savedState["space_segment"] = SpaceSegment.Dual.name
        savedState["selected_dual"] = dualSpaceId
        if (space.id !in snapshots) reloads.request(SpaceReloadRequest(setOf(space.userId)))
        if (targetChanged) projectViews()
    }

    private fun setFeedback(message: String, isError: Boolean) {
        _uiState.value = _uiState.value.copy(feedbackMessage = message, feedbackIsError = isError)
        AppFeedbackBus.emit(ActionFeedback(message, isError))
    }

    fun clearFeedback() {
        _uiState.value = _uiState.value.copy(feedbackMessage = null, feedbackIsError = false)
    }

    fun reportTransientError(message: String) { setFeedback(message, isError = true) }

    fun clearMainCopyLostWarning() {
        _uiState.value = _uiState.value.copy(mainCopyLostPackage = null)
    }

    // -----------------------------------------------------------------------
    // Data refresh
    // -----------------------------------------------------------------------

    fun refresh() { reloads.request(SpaceReloadRequest(checkSpaceFacts = true)) }

    private suspend fun reload(request: SpaceReloadRequest) {
        _uiState.value = _uiState.value.copy(refreshing = true, refreshError = null)
        try {
            if (request.checkSpaceFacts && !stateRepo.refresh("space_explicit")) {
                throw IllegalStateException("space_facts_unavailable")
            }
            // A failed fact lookup is not evidence that a profile disappeared.
            if (stateRepo.state.value is SpaceSnapshot.Loading) return
            if (stateRepo.state.value is SpaceSnapshot.Failed) throw IllegalStateException("space_facts_unavailable")
            val previous = snapshots
            val (spaces, updated) = withContext(Dispatchers.IO) {
                val spaces = spaceRepo.spaces()
                val updated = previous.filterKeys { id -> spaces.any { it.id == id } }.toMutableMap()
                spaces.filter { request.users == null || it.userId in request.users || it.id !in previous }.forEach { space ->
                    if (space.kind == PrismSpaceKind.Main || spaceRepo.usabilityOf(space) == SpaceUsability.Usable) {
                        DiagnosticLog.d(TAG, "load app snapshot space=${space.id}")
                        val apps = loadApps(space, request.reason)
                        // Retire completed preparations only against a freshly read usable profile,
                        // never as a side effect of rendering a cached list or changing its filter.
                        if (space.kind == PrismSpaceKind.Dual) {
                            ClonePreparationStore.reconcileInstalled(getApplication(), apps.apps.keys)
                        }
                        updated[space.id] = apps
                    }
                }
                spaces to updated.toMap()
            }
            snapshots = updated
            views = views.filterKeys { id -> spaces.any { it.id == id } }
            val current = _uiState.value
            val selection = resolveSpaceSelection(current.segment, current.selectedDualSpaceId, spaces)
            savedState["space_segment"] = selection.segment.name
            savedState["selected_dual"] = selection.selectedDualSpaceId
            publishViews(current.copy(spaces = spaces, segment = selection.segment, selectedDualSpaceId = selection.selectedDualSpaceId))
            projectViews()
        } finally {
            _uiState.value = _uiState.value.copy(refreshing = false)
        }
    }

    private fun refreshFailed(error: Exception) {
        DiagnosticLog.w(TAG, "app snapshot refresh failed", error)
        publishViews(_uiState.value.copy(refreshError = prismResolver(getApplication())(
            R.string.lz_setvm_state_refresh_failed, emptyArray())))
    }

    /** Rows are projected once off-main and cached for every space; UI only renders this result. */
    private fun projectViews() {
        projectionJob?.cancel()
        val version = ++projectionVersion
        val source = snapshots
        val browsing = _uiState.value.browsing
        val target = _uiState.value.selectedDualSpaceId
        val previous = projections
        _uiState.value = _uiState.value.copy(calculating = true)
        projectionJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val context: Context = getApplication()
                    val locale = PrismLocale.wrap(context).resources.configuration.locales[0]
                    val res = prismResolver(context)
                    val targetApps = source[target]?.apps?.keys
                    val pending = ClonePreparationStore.pendingPackages(context)
                    val marked = UserCloneRegistry.packages(context)
                    source.mapValues { (id, apps) ->
                        val options = browsing[id] ?: SpaceBrowseOptions()
                        val key = ProjectionInput(apps.normal, apps.system, options,
                            if (id == "main") targetApps else null, target != null,
                            if (id == "main") pending else emptySet(), marked, locale)
                        previous[id]?.takeIf { it.first == key } ?: run {
                            val inputs = if (id == "main") apps.normal.map { input ->
                                input.copy(
                                    cloned = mainAppIsCloned(input.system, input.pkg in targetApps.orEmpty(), input.pkg in marked),
                                    prepared = input.pkg in pending && input.pkg !in targetApps.orEmpty(),
                                    cloneStateKnown = target == null || targetApps != null,
                                )
                            } else if (options.showSystem) mergeAllSystemRows(apps.normal, apps.system) else apps.normal
                            val segment = if (id == "main") SpaceSegment.Main else SpaceSegment.Dual
                            key to SpaceView(
                                applyListTransform(mapRows(inputs, res), segment, options.query, options.sort,
                                    options.filter, options.showSystem, locale).toSegmentState(),
                                filterSystemAppRows(mapRows(apps.system, res), options.systemQuery).toSegmentState(),
                            )
                        }
                    }
                }
                projections = result
                views = result.mapValues { it.value.second }
                publishViews(_uiState.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DiagnosticLog.w(TAG, "app list projection failed", error)
                publishViews(_uiState.value.copy(refreshError = prismResolver(getApplication())(
                    R.string.lz_setvm_state_refresh_failed, emptyArray())))
            } finally {
                if (version == projectionVersion) _uiState.value = _uiState.value.copy(calculating = false)
            }
        }
    }

    private fun publishViews(state: SpaceUiState) {
        val fallback = if (state.refreshError == null) SpaceSegmentState.Loading else SpaceSegmentState.Unavailable
        val selected = state.selectedDualSpaceId
        val usability = state.spaces.firstOrNull { it.id == selected }
            ?.let { spaceRepo.usabilityOf(it) } ?: SpaceUsability.NotProvisioned
        _uiState.value = state.copy(
            main = views["main"]?.normal ?: fallback,
            dual = views[selected]?.normal ?: if (usability == SpaceUsability.Usable) fallback else SpaceSegmentState.Unavailable,
            systemApps = views[selected]?.system ?: fallback,
            dualUsability = if (stateRepo.state.value is SpaceSnapshot.Loaded) usability else SpaceUsability.Unknown,
        )
    }

    private fun List<SpaceRow>.toSegmentState(): SpaceSegmentState =
        if (isEmpty()) SpaceSegmentState.Empty else SpaceSegmentState.Content(this)

    fun appFor(pkg: String, segment: SpaceSegment): PrismAppInfo? =
        snapshots[if (segment == SpaceSegment.Main) "main" else _uiState.value.selectedDualSpaceId]?.apps?.get(pkg)

    // -----------------------------------------------------------------------
    // Multi-select
    // -----------------------------------------------------------------------

    /** Long-press an app card to enter multi-select mode with that app pre-selected. The current
     *  search/filter/sort result set is snapshotted as the selection domain. */
    fun enterMultiSelect(pkg: String, domain: List<SpaceRow>) {
        if (_uiState.value.calculating || _uiState.value.batchProgress != null) return
        // System packages are deliberately single-action only so the critical-package warning
        // cannot be bypassed through a batch operation.
        val state = MultiSelect.enter(pkg, domain) ?: return
        _uiState.value = _uiState.value.copy(selectedPkgs = state.selected, multiSelectDomain = state.domain)
    }

    /** The explicit 批量管理 top-bar entry: enter multi-select with an empty selection over the
     *  current domain snapshot. */
    fun enterMultiSelect(domain: List<SpaceRow>) {
        if (_uiState.value.calculating || _uiState.value.batchProgress != null) return
        val state = MultiSelect.enterEmpty(domain) ?: return
        _uiState.value = _uiState.value.copy(selectedPkgs = state.selected, multiSelectDomain = state.domain)
    }

    /** Toggle selection of a pkg while in multi-select mode. */
    fun toggleSelect(pkg: String) {
        if (_uiState.value.batchProgress != null) return
        val current = _uiState.value
        val domain = current.multiSelectDomain ?: return
        val selected = current.selectedPkgs ?: return
        val next = MultiSelect.toggle(MultiSelectState(domain, selected), pkg)
        _uiState.value = _uiState.value.copy(
            selectedPkgs = next?.selected,
            multiSelectDomain = next?.domain,
        )
    }

    /** Select every app in the selection domain (the "全选" action) — the entry-time filtered
     *  result set, never the whole segment. */
    fun selectAll() {
        if (_uiState.value.batchProgress != null) return
        val current = _uiState.value
        val domain = current.multiSelectDomain ?: return
        val selected = current.selectedPkgs ?: return
        val next = MultiSelect.selectAll(MultiSelectState(domain, selected))
        _uiState.value = _uiState.value.copy(selectedPkgs = next.selected)
    }

    /** Exit multi-select mode and clear selection. */
    fun exitMultiSelect() {
        if (_uiState.value.batchProgress != null) return
        _uiState.value = _uiState.value.copy(selectedPkgs = null, multiSelectDomain = null, batchProgress = null)
    }

    // -----------------------------------------------------------------------
    // Batch execution — loops single-item APIs sequentially off main thread
    // -----------------------------------------------------------------------

    fun executeBatch(
        action: BatchAction,
        activity: FragmentActivity,
        prismAppsVm: PrismAppsViewModel,
    ) {
        if (_uiState.value.batchProgress != null) return
        val pkgs = _uiState.value.selectedPkgs?.takeIf { it.isNotEmpty() }?.toList() ?: return
        val segment = _uiState.value.segment
        val total = pkgs.size
        if (action == BatchAction.Uninstall) {
            startUninstallQueue(pkgs, segment)
            return
        }
        val res: StringResolver = prismResolver(getApplication())
        viewModelScope.launch {
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(batchProgress = res(R.string.lz_vm_batch_progress, arrayOf(0, total)))
            }
            var succeeded = 0
            val failures = mutableListOf<String>()
            try {
                when (action) {
                    BatchAction.Freeze -> {
                        withContext(Dispatchers.IO) {
                            pkgs.forEachIndexed { i, pkg ->
                                runCatching {
                                    val app = appFor(pkg, segment)
                                    if (app != null && !app.isSystem) {
                                        if (PrismAppControl.freeze(app)) succeeded++
                                        else failures.add(pkg)
                                    } else {
                                        failures.add(pkg)
                                    }
                                }.onFailure { failures.add(pkg) }
                                withContext(Dispatchers.Main) {
                                    _uiState.value = _uiState.value.copy(
                                        batchProgress = res(R.string.lz_vm_batch_progress, arrayOf(i + 1, total))
                                    )
                                }
                            }
                        }
                        AppFeedbackBus.emit(batchActionFeedback(action, succeeded, failures.size, res))
                    }
                    BatchAction.Uninstall -> error("Uninstall is handled by the profile-routed uninstall queue")
                    BatchAction.CopyToDual -> {
                        // Real per-package results: staging (normal mode) counts as prepared, never
                        // cloned; only a verified enhanced-route install counts as installed. The
                        // batch is confirmed once up front; no fixed-delay success guessing.
                        // Quiet-mode activation is asked at most once per run (driver-owned budget):
                        // a refused/timed-out prompt fails the remaining packages without each of
                        // them showing the system dialog.
                        val counts = runBatchClone(
                            pkgs,
                            BatchClonePort { pkg ->
                                val app = appFor(pkg, SpaceSegment.Main) ?: return@BatchClonePort BatchCloneResult.Failed()
                                PrismAppClones(activity, prismAppsVm, app).requestForBatch()
                            },
                            activate = {
                                val context: Context = getApplication()
                                val profile = Users.profile
                                if (profile != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    runCatching { Users.requestQuietModeDisabled(context, profile) }
                                        .onFailure {
                                            DiagnosticLog.e(TAG, "batch clone activation failed user=${profile.toId()}", it)
                                        }
                                        .getOrDefault(false)
                                } else false
                            },
                        ) { done, totalCount ->
                            _uiState.value = _uiState.value.copy(
                                batchProgress = res(R.string.lz_vm_batch_progress, arrayOf(done, totalCount)),
                            )
                        }
                        AppFeedbackBus.emit(batchCloneFeedback(counts, res))
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(selectedPkgs = null, multiSelectDomain = null, batchProgress = null)
                }
            }
            refresh()
        }
    }

    // -----------------------------------------------------------------------
    // Passthroughs — exact PrismAppControl signatures, no Intent
    // -----------------------------------------------------------------------

    fun launch(context: Context, pkg: String, segment: SpaceSegment) {
        val app = appFor(pkg, segment) ?: return
        if (segment == SpaceSegment.Dual) {
            viewModelScope.launch checkSpace@{
                if (selectedDualUsability() != SpaceUsability.Usable) {
                    val fb = launchFeedback(LaunchResult.SpaceNotReady, app.label.toString(), prismResolver(context))
                    setFeedback(fb.message, isError = fb.isError)
                    return@checkSpace
                }
                PrismAppControl.launch(context, app)
            }
            return
        }
        PrismAppControl.launch(context, app)
    }

    /** Fresh usability of the selected dual space — the single source both launch and uninstall
     *  gating read from. A missing space reads as [SpaceUsability.NotProvisioned]. */
    private suspend fun selectedDualUsability(): SpaceUsability = withContext(Dispatchers.IO) {
        val sel = _uiState.value.selectedDualSpaceId?.let { spaceRepo.space(it) } ?: spaceRepo.dualSpace()
        if (sel == null) SpaceUsability.NotProvisioned else spaceRepo.usabilityOf(sel)
    }

    /** Emits the state-specific uninstall guidance for an unusable space; fires no request. */
    private fun blockUninstallWithGuidance(usability: SpaceUsability) {
        uninstallGate(usability, prismResolver(getApplication())).guidance
            ?.let { setFeedback(it, isError = true) }
    }

    fun setFrozen(pkg: String, frozen: Boolean) {
        val app = appFor(pkg, SpaceSegment.Dual) ?: return
        if (frozen && app.isCritical) {
            DiagnosticLog.i(TAG, "freeze refused for critical pkg=$pkg")
            reportTransientError(prismResolver(getApplication())(R.string.dialog_critical_app_kept_available, emptyArray()))
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (frozen) {
                    PrismAppControl.freeze(app)
                } else {
                    // 解冻 must fully recover: clear both freeze mechanisms so a clone
                    // paused by either path becomes launchable again.
                    PrismAppControl.unfreeze(app)
                    runCatching { PrismAppControl.setSuspended(app, false) }
                    // Honest dead-end escape: a suspension imposed by the system/root survives
                    // every in-app lever (cross-suspender protection). Offer the guaranteed
                    // reinstall path instead of leaving the user with a silently dead button.
                    if (CloneSuspendRecovery.isSuspended(app.context(), app.user, pkg) == true) {
                        val suspender = CloneSuspendRecovery
                            .diagnoseSuspension(app.context(), app.user, pkg, allowSuProbe = false)
                            ?.suspendingPackage
                        DiagnosticLog.w(TAG, "unfreeze dead end pkg=$pkg suspender=${suspender ?: "unknown"}")
                        _uiState.value = _uiState.value.copy(
                            suspendRecovery = SuspendRecoveryPrompt(pkg, suspender),
                        )
                    }
                }
                // Re-query the package as a "package change" (add=false) so the cached isHidden reflects
                // the freeze NOW — add=true would force isHidden=false (that path is for fresh installs).
                // Without this, refresh() reads a stale snapshot and the badge only flips on screen re-entry.
                PrismAppListProvider.getInstance(getApplication()).refreshPackage(app.packageName, app.user, false)
            }
            refresh()
        }
    }

    /** User-confirmed forced recovery: privileged unsuspend across transports first (no data
     *  loss), reinstall as the last resort. Explicit user action, so probing su is fair. */
    fun forceRecoverSuspendedClone() {
        val prompt = _uiState.value.suspendRecovery ?: return
        val app = appFor(prompt.pkg, SpaceSegment.Dual)
        _uiState.value = _uiState.value.copy(suspendRecovery = null)
        if (app == null) return
        val res = prismResolver(getApplication())
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                CloneSuspendRecovery.forceRecoverViaPrivileged(
                    app.context(), app.user, prompt.pkg, allowSuProbe = true,
                )
            }
            if (ok) {
                setFeedback(res(R.string.lz_space_suspend_reinstall_ok, arrayOf(prompt.pkg)), isError = false)
                withContext(Dispatchers.IO) {
                    PrismAppListProvider.getInstance(getApplication()).refreshPackage(prompt.pkg, app.user, false)
                }
                refresh()
            } else {
                setFeedback(res(R.string.lz_space_suspend_reinstall_failed, arrayOf()), isError = true)
            }
        }
    }

    fun dismissSuspendRecovery() {
        _uiState.value = _uiState.value.copy(suspendRecovery = null)
    }

    fun remove(activity: Activity, pkg: String, segment: SpaceSegment) {
        val app = appFor(pkg, segment) ?: return
        if (segment == SpaceSegment.Dual) {
            // Same space-usability source as launch(): an unusable space must never receive an
            // uninstall request (fail closed; the gate guidance is shown instead).
            viewModelScope.launch {
                val usability = selectedDualUsability()
                if (usability != SpaceUsability.Usable) return@launch blockUninstallWithGuidance(usability)
                // System packages leave a space through the policy (setSystemAppInSpace), never an uninstall.
                if (!app.isSystem) startUninstallQueue(listOf(pkg), segment)
            }
            return
        }
        if (!app.isSystem) startUninstallQueue(listOf(pkg), segment)
    }

    /**
     * 添加到双开空间 / 从双开空间移除 for a system package: one ApplySystemAppSelection through the
     * profile-side policy. Only the reported result is shown; nothing is assumed on bridge failure.
     */
    fun setSystemAppInSpace(pkg: String, available: Boolean) {
        val app = appFor(pkg, SpaceSegment.Dual) ?: return
        if (!app.isSystem) return
        if (!available && app.isCritical) {
            reportTransientError(prismResolver(getApplication())(R.string.dialog_critical_app_kept_available, emptyArray()))
            return
        }
        viewModelScope.launch {
            val usability = selectedDualUsability()
            if (usability != SpaceUsability.Usable) return@launch blockUninstallWithGuidance(usability)
            val context: Context = getApplication()
            val res = prismResolver(context)
            val result = withContext(Dispatchers.IO) {
                SystemAppSelectionClient.setAvailable(context, app.user.toId(), pkg, available)
            }
            val report = (result as? ProfileBridgeResult.Value)?.value
            val label = app.label.toString()
            when {
                report == null -> setFeedback(profileBridgeFailureMessage(context, result,
                    res(R.string.toast_cannot_clone, arrayOf(label))), isError = true)
                available && pkg in report.available ->
                    setFeedback(res(R.string.toast_successfully_cloned, arrayOf(label)), isError = false)
                !available && pkg in report.unavailable ->
                    setFeedback(res(R.string.lz_app_removed_system_from_space, arrayOf(label)), isError = false)
                pkg in report.absent -> setFeedback(res(R.string.lz_sysapp_picker_result_issues, arrayOf(1, 0)), isError = true)
                else -> setFeedback(res(R.string.lz_sysapp_picker_result_issues, arrayOf(0, 1)), isError = true)
            }
            reloads.request(SpaceReloadRequest(setOf(app.user.toId()), reason = "policy"))
        }
    }

    fun onHostPaused() { uninstallHostResumed = false }

    fun onHostResumed() {
        uninstallHostResumed = true
        beginUninstallVerification()
        val locale = PrismLocale.wrap(getApplication()).resources.configuration.locales[0].toLanguageTag()
        if (locale != labelLocale) {
            labelLocale = locale
            reloads.request(SpaceReloadRequest())
        }
        viewModelScope.launch { stateRepo.refresh("space_resumed") }
        // Space facts are a data class StateFlow: an unchanged state never re-emits, so launchability
        // (an app may toggle its own launcher entry while away) must be re-read explicitly.
        val dualUsers = _uiState.value.spaces.filter { it.kind == PrismSpaceKind.Dual }.mapTo(HashSet()) { it.userId }
        if (dualUsers.isNotEmpty()) reloads.request(SpaceReloadRequest(dualUsers, reason = "resume"))
    }

    private fun startUninstallQueue(pkgs: List<String>, segment: SpaceSegment) {
        if (!uninstallQueue.complete || uninstallQueue.total > 0 || _uiState.value.batchProgress != null) return
        _uiState.value = _uiState.value.copy(batchProgress = prismResolver(getApplication())(
            R.string.lz_vm_batch_progress, arrayOf(0, pkgs.size)))
        viewModelScope.launch {
            if (segment == SpaceSegment.Dual) {
                val usability = selectedDualUsability()
                if (usability != SpaceUsability.Usable) {
                    _uiState.value = _uiState.value.copy(batchProgress = null)
                    return@launch blockUninstallWithGuidance(usability)
                }
            }
            val requests = withContext(Dispatchers.IO) {
                pkgs.mapNotNull { pkg ->
                    val app = appFor(pkg, segment)?.takeUnless { it.isSystem } ?: return@mapNotNull null
                    UninstallRequest(app.packageName, app.user.toId(), mainCopyExists(app.packageName))
                }
            }
            uninstallSkipped = pkgs.size - requests.size
            if (requests.isEmpty()) {
                AppFeedbackBus.emit(batchActionFeedback(BatchAction.Uninstall, 0, pkgs.size, prismResolver(getApplication())))
                _uiState.value = _uiState.value.copy(selectedPkgs = null, multiSelectDomain = null, batchProgress = null)
                return@launch
            }
            updateUninstallQueue(UninstallQueueReducer.start(requests))
            driveUninstallLaunch()
        }
    }

    /** Fires the profile-routed system-uninstall request for the ready queue head. The launch and
     *  its result both happen inside the managed profile; verification keeps using the existing
     *  resume/observation flow. There is deliberately no user-0 fallback (issue #6). */
    private fun driveUninstallLaunch() {
        val current = uninstallQueue.current?.takeIf { it.stage == UninstallStage.ReadyToLaunch } ?: return
        if (uninstallLaunchInFlight == current.request) return
        uninstallLaunchInFlight = current.request
        viewModelScope.launch {
            // Per-head gate: re-check FRESH usability before EVERY request — a space that turned
            // unusable mid-queue stops the run; the head's request is never fired.
            val usability = selectedDualUsability()
            if (usability != SpaceUsability.Usable) {
                uninstallLaunchInFlight = null
                abortUninstallQueue(usability)
                return@launch
            }
            val reply = uninstallLaunchPort.requestUninstall(current.request)
            uninstallLaunchInFlight = null
            val request = current.request
            val system = appFor(request.packageName, SpaceSegment.Dual)?.isSystem == true
            when (reply) {
                UninstallLaunchReply.Launched -> {
                    PrismAppControl.logUninstallLaunchOutcome(request.packageName, system, launched = true, failureReason = null)
                    updateUninstallQueue(UninstallQueueReducer.launched(uninstallQueue))
                    val awaiting = uninstallQueue.current
                    // Android can silently reject an activity start. If the host never leaves
                    // foreground, terminate instead of keeping the batch locked forever.
                    viewModelScope.launch {
                        delay(3_000L)
                        if (uninstallHostResumed && uninstallQueue.current === awaiting) {
                            DiagnosticLog.w(TAG, "uninstall UI not observed pkg=${request.packageName}")
                            val mainExists = withContext(Dispatchers.IO) { mainCopyExists(request.packageName) }
                            if (uninstallHostResumed) {
                                val next = UninstallQueueReducer.launchUnobserved(uninstallQueue, awaiting, mainExists)
                                if (next !== uninstallQueue) completeUninstallTransition(next)
                            }
                        }
                    }
                }
                is UninstallLaunchReply.Failed -> {
                    PrismAppControl.logUninstallLaunchOutcome(
                        request.packageName, system, launched = false,
                        failureReason = reply.reason ?: reply.message,
                    )
                    reply.message?.let { setFeedback(it, isError = true) }
                    val mainExists = withContext(Dispatchers.IO) { mainCopyExists(request.packageName) }
                    completeUninstallTransition(UninstallQueueReducer.launchFailed(uninstallQueue, mainExists))
                }
            }
        }
    }

    /** Mid-queue gate trip: stop without firing the pending head. Already-completed heads keep
     *  their verified outcomes; unlaunched heads are honestly reported as not attempted. */
    private fun abortUninstallQueue(usability: SpaceUsability) {
        val res = prismResolver(getApplication())
        val guidance = uninstallGate(usability, res).guidance
            ?: res(R.string.prompt_space_not_ready, emptyArray())
        AppFeedbackBus.emit(uninstallAbortFeedback(uninstallQueue, uninstallSkipped, guidance, res))
        uninstallValidationJob?.cancel()
        uninstallQueue = UninstallQueueState()
        uninstallSkipped = 0
        _uiState.value = _uiState.value.copy(selectedPkgs = null, multiSelectDomain = null, batchProgress = null)
        refresh()
    }

    private fun beginUninstallVerification() {
        val next = UninstallQueueReducer.returned(uninstallQueue, SystemClock.elapsedRealtime())
        if (next == uninstallQueue) return
        updateUninstallQueue(next)
        if (next.current?.stage == UninstallStage.Verifying) startUninstallVerificationLoop()
    }

    private fun startUninstallVerificationLoop() {
        uninstallValidationJob?.cancel()
        uninstallValidationJob = viewModelScope.launch {
            while (true) {
                val request = uninstallQueue.current?.takeIf { it.stage == UninstallStage.Verifying }?.request ?: return@launch
                val (installed, mainExists) = withContext(Dispatchers.IO) {
                    targetInstalled(request) to mainCopyExists(request.packageName)
                }
                val next = UninstallQueueReducer.observed(
                    uninstallQueue,
                    installed,
                    mainExists,
                    SystemClock.elapsedRealtime(),
                )
                if (next.outcomes.size > uninstallQueue.outcomes.size) {
                    completeUninstallTransition(next)
                    return@launch
                }
                delay(250L)
            }
        }
    }

    private suspend fun completeUninstallTransition(next: UninstallQueueState) {
        val outcome = next.outcomes.lastOrNull()
        if (outcome != null) DiagnosticLog.i(TAG,
            "uninstall verified pkg=${outcome.request.packageName} targetUser=${outcome.request.targetUserId} status=${outcome.status}")
        if (outcome != null && shouldClearCloneRegistry(outcome.status)) {
            withContext(Dispatchers.IO) { UserCloneRegistry.remove(getApplication(), outcome.request.packageName) }
        }
        if (outcome?.mainCopyLost == true) {
            DiagnosticLog.w(
                TAG,
                "main_copy_lost pkg=${outcome.request.packageName} rom=${Build.MANUFACTURER}/${Build.DISPLAY}",
            )
            _uiState.value = _uiState.value.copy(mainCopyLostPackage = outcome.request.packageName)
        }
        updateUninstallQueue(next)
        if (next.complete) {
            val summary = next.summary
            AppFeedbackBus.emit(uninstallQueueFeedback(summary, uninstallSkipped, prismResolver(getApplication())))
            _uiState.value = _uiState.value.copy(selectedPkgs = null, multiSelectDomain = null, batchProgress = null)
            uninstallQueue = UninstallQueueState()
            uninstallSkipped = 0
            refresh()
        } else driveUninstallLaunch()
    }

    private fun updateUninstallQueue(next: UninstallQueueState) {
        uninstallQueue = next
        if (next.total > 0 && !next.complete) {
            _uiState.value = _uiState.value.copy(
                batchProgress = prismResolver(getApplication())(
                    R.string.lz_vm_batch_progress,
                    arrayOf(next.outcomes.size, next.total),
                ),
            )
        }
    }

    private fun targetInstalled(request: UninstallRequest): Boolean? {
        val context: Context = getApplication()
        val user = UserHandles.of(request.targetUserId)
        if (!Users.isProfileAvailable(context, user)) return null
        return try {
            LauncherAppsCompat(context)
                .getApplicationInfoNoThrows(request.packageName, MATCH_UNINSTALLED_PACKAGES, user)
                ?.installed == true
        } catch (error: RuntimeException) {
            DiagnosticLog.w(TAG, "uninstall observation unavailable targetUser=${request.targetUserId} exception=${error.javaClass.simpleName}")
            null
        }
    }

    private fun mainCopyExists(pkg: String): Boolean = runCatching {
        getApplication<Application>().packageManager.getApplicationInfo(pkg, 0).installed
    }.getOrDefault(false)

    fun openSystemSettings(pkg: String, segment: SpaceSegment) {
        val app = appFor(pkg, segment) ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                PrismAppControl.launchSystemAppSettings(app)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Filter, sort, and search state updates.
    // -----------------------------------------------------------------------

    private fun updateBrowse(change: (SpaceBrowseOptions) -> SpaceBrowseOptions) {
        val current = _uiState.value
        val next = change(current.browse)
        if (next == current.browse) return
        val browsing = current.browsing + (current.activeSpaceId to next)
        _uiState.value = current.copy(browsing = browsing)
        savedState["space_browsing"] = HashMap(browsing)
        projectViews()
    }

    fun setSortOrder(order: SortOrder) = updateBrowse { it.copy(sort = order) }
    fun setCloneFilter(filter: CloneFilter) = updateBrowse { it.copy(filter = filter) }
    fun setShowSystem(show: Boolean) = updateBrowse { it.copy(showSystem = show) }
    fun setShowSystemDual(show: Boolean) = setShowSystem(show)
    fun setQuery(query: String) = updateBrowse { it.copy(query = query) }
    fun setSystemQuery(query: String) = updateBrowse { it.copy(systemQuery = query) }

    /** Immutable input values, collected from the existing repository off the UI thread. */
    private fun loadApps(space: PrismSpace, reason: String): SpaceApps {
        val context = PrismLocale.wrap(getApplication())
        val locale = PrismLocale.wrap(context).resources.configuration.locales[0].toLanguageTag()
        val apps = spaceRepo.installedApps(space).filter { it.isInstalled && it.packageName != context.packageName }
        val segment = if (space.kind == PrismSpaceKind.Main) SpaceSegment.Main else SpaceSegment.Dual
        // One fresh LauncherApps read per load for visible dual-space packages (no static cache).
        val launcherPackages = if (segment != SpaceSegment.Dual) null else runCatching {
            getApplication<Application>().getSystemService(LauncherApps::class.java)!!
                .getActivityList(null, UserHandles.of(space.userId)).mapTo(HashSet()) { it.componentName.packageName }
        }.getOrNull()
        if (segment == SpaceSegment.Dual)
            DiagnosticLog.i(TAG, "launchability_refresh u=${space.userId} reason=$reason launcherPkgs=${launcherPackages?.size ?: -1}")
        val inputs = apps.associate { app ->
            val labelKey = "${space.id}:${app.packageName}:${app.sourceDir}:$locale"
            val label = labels.get(labelKey) ?: runCatching { app.loadLabel(context.packageManager).toString() }
                .getOrDefault(app.packageName).ifBlank { app.packageName }.also { labels.put(labelKey, it) }
            val launchability = if (segment == SpaceSegment.Dual)
                resolveLaunchability(dualLauncherEntry(app, launcherPackages), app.isHidden, app.isSuspended) else null
            app.packageName to SpaceAppInput(
                pkg = app.packageName, label = label, frozen = app.isHidden, suspended = app.isSuspended,
                launchable = launchability?.let { it != AppLaunchability.NoLauncherEntry } ?: app.isLaunchable,
                system = app.isSystem, cloned = false, segment = segment,
                critical = app.isCritical, userId = space.userId, iconVersion = app.sourceDir.orEmpty(),
                launchability = launchability,
                policyHidden = segment == SpaceSegment.Dual && app.isHiddenSysPrismAppTreatedAsDisabled,
                entryAction = if (segment == SpaceSegment.Dual) dualEntryAction(app) else null,
            )
        }
        val normal = apps.filter { app ->
            if (segment == SpaceSegment.Main) app.enabled else inputs.getValue(app.packageName).let { input ->
                isDualNormalRow(input.system, app.shouldShowAsEnabled(), input.policyHidden, input.launchability!!)
            }
        }.map { inputs.getValue(it.packageName) }
        return SpaceApps(apps.associateBy { it.packageName }, normal, inputs.values.filter { it.system })
    }
}

private const val TAG = "Prism.SpaceVM"
