package com.yzddmr6.prismspace.prism.compose.vm

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.controller.UserCloneRegistry
import com.yzddmr6.prismspace.prism.compose.component.PrismLevel
import com.yzddmr6.prismspace.prism.compose.space.SpaceRepository
import com.yzddmr6.prismspace.prism.compose.space.SpaceRepositoryProvider
import com.yzddmr6.prismspace.prism.compose.space.SpaceUsability
import com.yzddmr6.prismspace.prism.compose.nav.PrismRoutes
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.util.PrismLocale
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
// ---------------------------------------------------------------------------
// Health enum used by the home overview card.
// ---------------------------------------------------------------------------

enum class SpaceHealth { Normal, NotCreated, Suspended, Locked, NeedsRepair }

enum class HomePrimaryAction { OpenSpace, StartSetup, OpenSettings }

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
)

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
    SpaceHealth.Suspended -> HomeUiModel(
        level = PrismLevel.Warn,
        statusTitle = resolve(R.string.lz_home_status_suspended_title),
        statusBody = resolve(R.string.lz_home_status_suspended_body),
        tag = resolve(R.string.lz_home_tag_suspended),
        mainCount = mainCount,
        cloneCount = cloneCount,
        primaryLabel = resolve(R.string.lz_home_label_restore),
        primaryRoute = null,
        primaryAction = HomePrimaryAction.OpenSettings,
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
        showRepair        = health != SpaceHealth.Normal,
        profileOwnerLabel = profileOwnerLabel,
    )
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val spaceRepo: SpaceRepository by lazy { SpaceRepositoryProvider.get(getApplication()) }
    private val capRepo: CapabilityRepository by lazy { CapabilityRepositoryProvider.get(getApplication()) }

    private val _uiState = MutableStateFlow<HomeUiModel?>(null)
    val uiState: StateFlow<HomeUiModel?> = _uiState

    fun refresh() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { loadState() }
            _uiState.value = result
        }
    }

    // Create/repair actions navigate to Settings, where provisioning and recovery live.
    fun repair(onNavigate: (String) -> Unit) {
        onNavigate(PrismRoutes.SETTINGS)
    }

    // Action for non-route primary buttons — kept for backward compat.
    fun onPrimary(onNavigate: (String) -> Unit) {
        onNavigate(PrismRoutes.SETTINGS)
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun loadState(): HomeUiModel {
        val context: Context = getApplication()
        runCatching { Users.refreshUsers(context) }
            .onFailure { DiagnosticLog.w(TAG, "refresh users before home state failed", it) }

        // Locale-aware string resolver — respects the user's chosen language (中/英),
        // even though these strings are built outside any @Composable.
        val resolve: (Int) -> String = { id -> PrismLocale.wrap(getApplication()).getString(id) }

        // Work-profile status flags used to derive the overview state.
        val profile = Users.profile
        val profileOwner = runCatching {
            profile?.let { Users.isProfileManagedByPrism(context, it) } == true
        }.getOrDefault(false)
		val running = runCatching {
			profile?.let { Users.isProfileRunning(context, it) } == true
		}.getOrDefault(false)
		val quietMode = runCatching {
			profile?.let { Users.isProfileQuietModeEnabled(context, it) } == true
		}.getOrDefault(false)

		val health = when (spaceRepo.dualSpace()?.let { spaceRepo.usabilityOf(it) } ?: SpaceUsability.NotProvisioned) {
            SpaceUsability.NotProvisioned    -> SpaceHealth.NotCreated
            SpaceUsability.Suspended         -> SpaceHealth.Suspended
            SpaceUsability.LockedNeedsUnlock -> SpaceHealth.Locked
            SpaceUsability.BridgeNotReady    -> SpaceHealth.NeedsRepair
            SpaceUsability.Unknown           -> SpaceHealth.NeedsRepair
            SpaceUsability.Usable            -> SpaceHealth.Normal
        }
        DiagnosticLog.d(
			TAG,
			"home state profile=${profile?.toId() ?: Users.NULL_ID} " +
				"profileOwner=$profileOwner running=$running quietMode=$quietMode health=$health",
		)

        // Counts — sourced via SpaceRepository (single source of truth; was: direct provider/Users)
        val mainCount = runCatching {
            spaceRepo.installedApps(spaceRepo.mainSpace())
                .count { app -> app.packageName != context.packageName && app.isInstalled && app.enabled }
        }.getOrElse { 0 }

        val cloneCount = if (profileOwner) {
            runCatching {
                spaceRepo.dualSpaces().sumOf { d ->
                    runCatching {
                        // Exclude PrismSpace itself so the home count matches the space tab.
                        // 分身 count mirrors the Space tab: user clones only — third-party apps (always
                        // user-cloned in a profile) or system apps the user explicitly cloned. Hides the
                        // provisioning system apps so "X 分身" matches what the user actually created.
                        spaceRepo.installedApps(d).count { app ->
                            app.isInstalled && app.shouldShowAsEnabled() && app.packageName != context.packageName &&
                                // Count mirrors the dual list: user clones plus launchable system apps.
                                (!app.isSystem || UserCloneRegistry.contains(context, app.packageName) || app.isLaunchable) }
                    }.getOrElse { 0 }
                }
            }.getOrElse { 0 }
        } else 0

        // Profile Owner status label.
		val profileOwnerLabel = when {
			!profileOwner -> resolve(R.string.lz_home_profile_not_created)
			!running || quietMode -> resolve(R.string.lz_home_profile_suspended)
			else -> resolve(R.string.lz_home_profile_ready)
		}

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
        )
    }

}

private const val TAG = "Prism.HomeVM"
