package com.yzddmr6.prismspace.prism.compose.vm

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yzddmr6.prismspace.analytics.CrashMarker
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.controller.ClonePreparationStore
import com.yzddmr6.prismspace.controller.UserCloneRegistry
import com.yzddmr6.prismspace.prism.compose.component.PrismLevel
import com.yzddmr6.prismspace.prism.compose.space.SpaceRepository
import com.yzddmr6.prismspace.prism.compose.space.SpaceRepositoryProvider
import com.yzddmr6.prismspace.prism.compose.space.SpaceSnapshot
import com.yzddmr6.prismspace.prism.compose.space.SpaceStateRepository
import com.yzddmr6.prismspace.prism.compose.space.SpacePresentationKind
import com.yzddmr6.prismspace.prism.compose.space.presentSpace
import com.yzddmr6.prismspace.prism.compose.space.SpaceUsability
import com.yzddmr6.prismspace.prism.compose.nav.PrismRoutes
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.transfer.TransferLedger
import com.yzddmr6.prismspace.prism.transfer.transferRowModel
import com.yzddmr6.prismspace.util.Apps
import com.yzddmr6.prismspace.util.PrismLocale
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId
import com.yzddmr6.prismspace.space.SpaceState
import com.yzddmr6.prismspace.util.UserHandles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
// ---------------------------------------------------------------------------
// Health enum used by the home overview card.
// ---------------------------------------------------------------------------

enum class SpaceHealth { Normal, NotCreated, Provisioning, Suspended, Locked, Checking, NeedsRepair }

internal fun spaceHealth(state: SpaceState): SpaceHealth = when (presentSpace(state).kind) {
    SpacePresentationKind.Missing -> SpaceHealth.NotCreated
    SpacePresentationKind.Provisioning -> SpaceHealth.Provisioning
    SpacePresentationKind.Locked -> SpaceHealth.Locked
    SpacePresentationKind.Inactive -> SpaceHealth.Suspended
    SpacePresentationKind.Ready -> SpaceHealth.Normal
    SpacePresentationKind.Checking,
    SpacePresentationKind.Unavailable -> SpaceHealth.Checking
    SpacePresentationKind.Orphan,
    SpacePresentationKind.Incomplete,
    SpacePresentationKind.BridgeUnavailable -> SpaceHealth.NeedsRepair
}

internal fun profileStatusLabelRes(state: SpaceState): Int = when (state) {
    SpaceState.NoProfile -> R.string.lz_home_profile_not_created
    is SpaceState.ForeignProfile -> R.string.lz_home_profile_not_created
    is SpaceState.Provisioning -> R.string.lz_home_tag_provisioning
    is SpaceState.Inactive -> R.string.lz_home_profile_suspended
    is SpaceState.Locked -> R.string.lz_home_tag_locked
    is SpaceState.Healthy -> R.string.lz_home_profile_ready
    is SpaceState.OrphanProfile,
    is SpaceState.HalfProvisioned,
    is SpaceState.BridgeDown -> R.string.lz_home_tag_needsrepair
}

enum class HomePrimaryAction { OpenSpace, StartSetup, OpenSettings, ActivateSpace }

/** 概览卡标签行：与头像组同一截断口径（同取前 N 个），仅当总数超出展示数时才追加省略号。 */
internal fun overviewLabelsLine(labels: List<String>, cloneCount: Int): String =
    labels.joinToString("、") + if (cloneCount > labels.size) " …" else ""

// ---------------------------------------------------------------------------
// Pure UI model
// ---------------------------------------------------------------------------

data class HomeUiModel(
    val level: PrismLevel,
    val statusTitle: String,
    val statusBody: String,
    val tag: String,
    val mainCount: Int,
    val cloneCount: Int,
    val primaryLabel: String,
    val primaryRoute: String?,
    val primaryAction: HomePrimaryAction,
    // Info-row fields and repair affordance.
    val capabilityText: String = "",
    val versionName: String = "",
    val androidText: String = "",
    val deviceText: String = "",
    val showRepair: Boolean = false,
    val profileOwnerLabel: String = "",
    // Pending installs (clone-preparation store): labels of apps awaiting in-space confirmation.
    val pendingInstallLabels: List<String> = emptyList(),
    // 前往安装 gate — same usability source as clone launch/uninstall.
    val installEntryEnabled: Boolean = true,
    val installEntryGuidance: String? = null,
    // 空间概览: first few clone packages (icons) + labels, and the latest transfer line.
    val overviewClonePkgs: List<String> = emptyList(),
    val overviewCloneLabels: List<String> = emptyList(),
    val recentTransferText: String? = null,
) {
    /** 状态安静原则: verified-healthy and no-valence states render as one quiet line, not a card. */
    val calm: Boolean get() = level == PrismLevel.Ok || level == PrismLevel.Neutral
}

// ---------------------------------------------------------------------------
// Pure mapper — Android-free, unit-testable.
// Primary actions with route=null navigate via VM callback to Settings.
// ---------------------------------------------------------------------------

internal fun mapHome(
    health: SpaceHealth,
    mainCount: Int,
    cloneCount: Int,
    resolve: (Int) -> String,
): HomeUiModel = when (health) {
    SpaceHealth.Normal -> HomeUiModel(
        level = PrismLevel.Ok,
        statusTitle = resolve(R.string.lz_home_status_normal_title),
        statusBody = resolve(R.string.lz_home_status_normal_body),
        tag = resolve(R.string.lz_home_tag_normal),
        mainCount = mainCount,
        cloneCount = cloneCount,
        primaryLabel = resolve(R.string.lz_home_label_add_app),
        primaryRoute = null,
        primaryAction = HomePrimaryAction.OpenSpace,
    )
    SpaceHealth.NotCreated -> HomeUiModel(
        level = PrismLevel.Error,
        statusTitle = resolve(R.string.lz_home_status_notcreated_title),
        statusBody = resolve(R.string.lz_home_status_notcreated_body),
        tag = resolve(R.string.lz_home_tag_notcreated),
        mainCount = mainCount,
        cloneCount = cloneCount,
        primaryLabel = resolve(R.string.lz_home_label_create),
        primaryRoute = null,
        primaryAction = HomePrimaryAction.StartSetup,
    )
    SpaceHealth.Provisioning -> HomeUiModel(
        // 进行中（配置中）是无偏向的瞬时状态——Neutral，不渲染为「需要注意」。
        level = PrismLevel.Neutral,
        statusTitle = resolve(R.string.lz_home_status_provisioning_title),
        statusBody = resolve(R.string.lz_home_status_provisioning_body),
        tag = resolve(R.string.lz_home_tag_provisioning),
        mainCount = mainCount,
        cloneCount = cloneCount,
        primaryLabel = resolve(R.string.lz_home_label_provisioning),
        primaryRoute = null,
        primaryAction = HomePrimaryAction.OpenSettings,
    )
    SpaceHealth.Suspended -> HomeUiModel(
        level = PrismLevel.Warn,
        statusTitle = resolve(R.string.lz_home_status_suspended_title),
        statusBody = resolve(R.string.lz_home_status_suspended_body),
        tag = resolve(R.string.lz_home_tag_suspended),
        mainCount = mainCount,
        cloneCount = cloneCount,
        primaryLabel = resolve(R.string.lz_home_label_restore),
        primaryRoute = null,
        primaryAction = HomePrimaryAction.ActivateSpace,
    )
    SpaceHealth.Locked -> HomeUiModel(
        level = PrismLevel.Warn,
        statusTitle = resolve(R.string.lz_home_status_locked_title),
        statusBody = resolve(R.string.lz_home_status_locked_body),
        tag = resolve(R.string.lz_home_tag_locked),
        mainCount = mainCount,
        cloneCount = cloneCount,
        primaryLabel = resolve(R.string.lz_home_label_unlock),
        primaryRoute = null,
        primaryAction = HomePrimaryAction.ActivateSpace,
    )
    SpaceHealth.Checking -> HomeUiModel(
        // 检查中/未知统一归 Neutral：「不知道」不得染绿也不染黄。
        level = PrismLevel.Neutral,
        statusTitle = resolve(R.string.lz_home_status_checking_title),
        statusBody = resolve(R.string.lz_home_status_checking_body),
        tag = resolve(R.string.lz_home_tag_checking),
        mainCount = mainCount,
        cloneCount = cloneCount,
        primaryLabel = resolve(R.string.lz_home_label_checking),
        primaryRoute = null,
        primaryAction = HomePrimaryAction.OpenSettings,
    )
    SpaceHealth.NeedsRepair -> HomeUiModel(
        level = PrismLevel.Error,
        statusTitle = resolve(R.string.lz_home_status_needsrepair_title),
        statusBody = resolve(R.string.lz_home_status_needsrepair_body),
        tag = resolve(R.string.lz_home_tag_needsrepair),
        mainCount = mainCount,
        cloneCount = cloneCount,
        primaryLabel = resolve(R.string.lz_home_label_repair),
        primaryRoute = null,
        primaryAction = HomePrimaryAction.OpenSettings,
    )
}

// ---------------------------------------------------------------------------
// Pure mapper that adds info-row fields and the repair flag.
// Wraps mapHome(); Android-free, unit-testable.
//   showRepair: true for NotCreated/Suspended/NeedsRepair
// ---------------------------------------------------------------------------

internal fun mapHomeState(
    health: SpaceHealth,
    mainCount: Int,
    cloneCount: Int,
    capabilityText: String,
    versionName: String,
    androidText: String,
    deviceText: String,
    profileOwnerLabel: String = "",
    resolve: (Int) -> String,
): HomeUiModel {
    val base = mapHome(health, mainCount, cloneCount, resolve)
    return base.copy(
        capabilityText    = capabilityText,
        versionName       = versionName,
        androidText       = androidText,
        deviceText        = deviceText,
        showRepair        = health != SpaceHealth.Normal && health != SpaceHealth.Checking,
        profileOwnerLabel = profileOwnerLabel,
    )
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val spaceRepo: SpaceRepository by lazy { SpaceRepositoryProvider.get(getApplication()) }
    private val stateRepo: SpaceStateRepository by lazy { SpaceStateRepository(getApplication()) }
    private val capRepo: CapabilityRepository by lazy { CapabilityRepositoryProvider.get(getApplication()) }

    private val _uiState = MutableStateFlow<HomeUiModel?>(null)
    val uiState: StateFlow<HomeUiModel?> = _uiState

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo

    private val _pendingCrashReport = MutableStateFlow(false)
    val pendingCrashReport: StateFlow<Boolean> = _pendingCrashReport

    init {
        viewModelScope.launch {
            stateRepo.state.collectLatest { snapshot ->
                when (snapshot) {
                    SpaceSnapshot.Loading -> renderChecking()
                    is SpaceSnapshot.Failed -> renderChecking()
                    is SpaceSnapshot.Loaded -> render(snapshot.state)
                }
            }
        }
        viewModelScope.launch {
            // Show the still-undismissed pending prompt instantly, then refresh silently in the
            // background (throttled; failures never surface and keep the pending prompt).
            _updateInfo.value = UpdateChecker.pendingPrompt(getApplication())
            val decision = UpdateChecker.check(getApplication(), force = false)
            if (decision is UpdateDecision.Prompt) _updateInfo.value = decision.info
        }
        viewModelScope.launch {
            _pendingCrashReport.value = withContext(Dispatchers.IO) {
                CrashMarker.pending(getApplication()) != null
            }
        }
    }

    /** Send the pending crash scene via the standard diagnostics export + system share sheet. */
    fun sendCrashReport() {
        val context: Context = getApplication()
        viewModelScope.launch {
            try {
                val subject = PrismLocale.wrap(context).getString(R.string.lz_setvm_report_subject)
                val (fileName, intent) = DiagnosticsExporter.buildShare(
                    context,
                    subject = subject,
                    attachedText = { name -> PrismLocale.wrap(context).getString(R.string.lz_setvm_report_attached, name) },
                )
                CrashMarker.clear(context)
                _pendingCrashReport.value = false
                DiagnosticsExporter.launchShare(context, subject, intent)
                DiagnosticLog.i(TAG, "pending crash report shared file=$fileName")
            } catch (e: Exception) {
                // Keep the marker: a failed share must not destroy the only crash scene copy.
                DiagnosticLog.e(TAG, "pending crash report share failed", e)
                _pendingCrashReport.value = false
            }
        }
    }

    /** User declined: consume the marker and never prompt for this crash again. */
    fun dismissCrashReport() {
        CrashMarker.clear(getApplication())
        _pendingCrashReport.value = false
        DiagnosticLog.i(TAG, "pending crash report dismissed by user")
    }

    /** Dismiss the home update dialog: the same version never prompts again automatically. */
    fun dismissUpdate() {
        _updateInfo.value?.let { UpdateChecker.markDismissed(getApplication(), it.version) }
        _updateInfo.value = null
    }

    fun refresh() {
        viewModelScope.launch {
            if (stateRepo.refresh("home_explicit")) {
                (stateRepo.state.value as? SpaceSnapshot.Loaded)?.state?.let { render(it) }
            } else renderChecking()
        }
    }

    private suspend fun render(state: SpaceState) {
        _uiState.value = withContext(Dispatchers.IO) { loadState(state) }
    }

    private suspend fun renderChecking() {
        _uiState.value = withContext(Dispatchers.IO) { loadCheckingState() }
    }

    // Create/repair actions navigate to Settings, where provisioning and recovery live.
    fun repair(onNavigate: (String) -> Unit) {
        if (_uiState.value?.primaryAction == HomePrimaryAction.ActivateSpace)
            com.yzddmr6.prismspace.prism.compose.nav.AppLaunchSignals.signalActivateSpace()
        onNavigate(PrismRoutes.SETTINGS)
    }

    // Action for non-route primary buttons — kept for backward compat.
    fun onPrimary(onNavigate: (String) -> Unit) {
        onNavigate(PrismRoutes.SETTINGS)
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun loadState(state: SpaceState): HomeUiModel {
        val context: Context = getApplication()

        // Locale-aware string resolver — respects the user's chosen language (中/英),
        // even though these strings are built outside any @Composable.
        val resolve: (Int) -> String = { id -> PrismLocale.wrap(getApplication()).getString(id) }

        // Work-profile status flags used to derive the overview state.
        val profile = state.userId?.let(UserHandles::of)
        val profileOwner = runCatching {
            profile?.let { Users.isProfileManagedByPrism(context, it) } == true
        }.getOrDefault(false)
		val running = runCatching {
			profile?.let { Users.isProfileRunning(context, it) } == true
		}.getOrDefault(false)
		val quietMode = runCatching {
			profile?.let { Users.isProfileQuietModeEnabled(context, it) } == true
		}.getOrDefault(false)

		val health = spaceHealth(state)
        DiagnosticLog.d(
			TAG,
			"home state profile=${profile?.toId() ?: Users.NULL_ID} " +
				"profileOwner=$profileOwner running=$running quietMode=$quietMode state=$state health=$health",
		)

        // Counts — sourced via SpaceRepository (single source of truth; was: direct provider/Users)
        val mainCount = runCatching {
            spaceRepo.installedApps(spaceRepo.mainSpace())
                .count { app -> app.packageName != context.packageName && app.isInstalled && app.enabled }
        }.getOrElse { 0 }

        // Dual apps loaded once: the clone count, the overview avatar group and the pending-install
        // reconciliation all derive from this single query.
        val dualApps = if (profileOwner) {
            runCatching {
                spaceRepo.dualSpaces().flatMap { d ->
                    runCatching { spaceRepo.installedApps(d) }.getOrElse { emptyList() }
                }
            }.getOrElse { emptyList() }
        } else emptyList()
        val userClones = dualApps.filter { app ->
            app.isInstalled && app.shouldShowAsEnabled() && app.packageName != context.packageName &&
                // Count user apps and explicitly added system clones, not provisioned system tools.
                (!app.isSystem || UserCloneRegistry.contains(context, app.packageName))
        }
        val cloneCount = userClones.size
        val overviewClones = userClones.sortedBy { it.label.toString().lowercase() }.take(5)

        // Pending installs from the clone-preparation store (reconciled against real dual state).
        val pendingPkgs = runCatching {
            ClonePreparationStore.reconcileInstalled(context, dualApps.map { it.packageName }.toSet())
        }.getOrElse { emptySet() }
        val pendingLabels = pendingPkgs.map { pkg ->
            runCatching { Apps.of(context).getAppName(pkg).toString() }.getOrDefault(pkg)
        }.sorted()

        // 前往安装 gate — the same usability source as clone launch/uninstall/continue-install.
        val dual = spaceRepo.dualSpace()
        val usability = dual?.let { spaceRepo.usabilityOf(it) } ?: SpaceUsability.NotProvisioned
        val installGate = continueInstallGate(usability) { id, args -> PrismLocale.wrap(context).getString(id, *args) }

        // Same row model as the Files page, so "Sent · Dual space" reads identically on both surfaces.
        val recentTransferText = runCatching {
            TransferLedger.load(context).firstOrNull()?.let { record ->
                transferRowModel(record, prismResolver(context), currentIsParent = true)
                    .let { model -> "${model.title} · ${model.summary}" }
            }
        }.getOrNull()

        // This row must describe the same canonical state as the hero card. In particular, a
        // half-provisioned profile exists even when its launcher marker is missing; the legacy
        // ownership lookup must not turn that into the contradictory label "Not created".
		val profileOwnerLabel = resolve(profileStatusLabelRes(state))

        // Configured mode comes from the same source as Settings.
        val capabilityText = resolve(prismModeLabelRes(capRepo.selectedMode.value))

        // Version — Versions.name/code (Versions.java lines 9-17)
        val versionName = runCatching {
            val vn = com.yzddmr6.prismspace.util.Versions.name(context) ?: "?"
            val vc = com.yzddmr6.prismspace.util.Versions.code(context)
            "v$vn ($vc)"
        }.getOrElse { "v?" }

        // Android version and device
        val androidText = "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
        val deviceText = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

        return mapHomeState(
            health             = health,
            mainCount          = mainCount,
            cloneCount         = cloneCount,
            capabilityText     = capabilityText,
            versionName        = versionName,
            androidText        = androidText,
            deviceText         = deviceText,
            profileOwnerLabel  = profileOwnerLabel,
            resolve            = resolve,
        ).copy(
            pendingInstallLabels = pendingLabels,
            installEntryEnabled = installGate.enabled,
            installEntryGuidance = installGate.guidance,
            overviewClonePkgs = overviewClones.map { it.packageName },
            overviewCloneLabels = overviewClones.map { it.label.toString() },
            recentTransferText = recentTransferText,
        )
    }

    private fun loadCheckingState(): HomeUiModel {
        val context: Context = getApplication()
        val resolve: (Int) -> String = { id -> PrismLocale.wrap(context).getString(id) }
        return mapHomeState(
            health = SpaceHealth.Checking,
            mainCount = 0,
            cloneCount = 0,
            capabilityText = resolve(prismModeLabelRes(capRepo.selectedMode.value)),
            versionName = runCatching {
                "v${com.yzddmr6.prismspace.util.Versions.name(context) ?: "?"} (${com.yzddmr6.prismspace.util.Versions.code(context)})"
            }.getOrDefault("v?"),
            androidText = "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
            deviceText = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            resolve = resolve,
        )
    }

}

private const val TAG = "Prism.HomeVM"
