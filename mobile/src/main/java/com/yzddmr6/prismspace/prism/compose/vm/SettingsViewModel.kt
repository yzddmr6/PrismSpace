package com.yzddmr6.prismspace.prism.compose.vm

import android.app.Application
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.analytics.DiagnosticSection
import com.yzddmr6.prismspace.controller.PrismAppControl
import com.yzddmr6.prismspace.controller.UserCloneRegistry
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.component.PrismLevel
import com.yzddmr6.prismspace.prism.compose.settings.ExperimentalFlags
import com.yzddmr6.prismspace.prism.compose.space.BridgeHealthRepository
import com.yzddmr6.prismspace.prism.compose.space.DeleteSpaceResult
import com.yzddmr6.prismspace.prism.compose.space.SpaceProvisioningEngine
import com.yzddmr6.prismspace.prism.compose.space.SpaceRepository
import com.yzddmr6.prismspace.prism.compose.space.SpaceRepositoryProvider
import com.yzddmr6.prismspace.prism.compose.space.SpaceUsability
import com.yzddmr6.prismspace.prism.model.CapabilityAvailability
import com.yzddmr6.prismspace.prism.model.CapabilityState
import com.yzddmr6.prismspace.prism.model.PrismRootStatus
import com.yzddmr6.prismspace.prism.model.PrismSettingsModeState
import com.yzddmr6.prismspace.prism.model.PrismShizukuAdbStatus
import com.yzddmr6.prismspace.prism.model.SettingsActionPlanner
import com.yzddmr6.prismspace.prism.service.CapabilityService
import com.yzddmr6.prismspace.prism.service.ProfileEntryLauncher
import com.yzddmr6.prismspace.setup.PrismSetup
import com.yzddmr6.prismspace.setup.SetupFlow
import com.yzddmr6.prismspace.shuttle.Shuttle
import com.yzddmr6.prismspace.shuttle.ShuttleOutcome
import com.yzddmr6.prismspace.shuttle.ShuttleProvider
import com.yzddmr6.prismspace.util.PrismLocale
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId
import eu.chainfire.libsuperuser.Shell
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

// ---------------------------------------------------------------------------
// Pure UI model — Android-free, fully unit-testable
// ---------------------------------------------------------------------------

/**
 * Per-mode row model with an active flag for Compose rendering.
 */
data class SettingsModeRow(
    val title: String,
    val summary: String,
    val statusLabel: String,
    val isActive: Boolean,
)

data class SettingsUiModel(
    val modeTitle: String,
    val modeBody: String,
    val level: PrismLevel,
    val profileOwnerReady: Boolean,
    val normalMode: SettingsModeRow,
    val shizukuAdbMode: SettingsModeRow,
    val rootMode: SettingsModeRow,
    // Feedback message shown below the screen (e.g. snapshot result)
    val feedbackMessage: String? = null,
    val feedbackIsError: Boolean = false,
    // Space suspended state
    val spaceSuspended: Boolean = false,
    // Single source of truth for which mode the user has selected
    val selectedMode: PrismMode = PrismMode.Normal,
    val experimentalMultiProfile: Boolean = false,
    // Non-null when an update check found a newer release.
    val updateInfo: UpdateInfo? = null,
    val spaceActionTitle: String = "",
    val spaceActionSummary: String = "",
    val spaceActionNeedsConfirmation: Boolean = true,
)

/** A newer GitHub release than the installed build. */
data class UpdateInfo(val version: String, val notes: String, val url: String)

/**
 * Compare dotted numeric versions ("0.0.2" vs "0.0.1"). Returns >0 if [a] is newer than [b],
 * 0 if equal, <0 if older. Non-numeric / missing segments are treated as 0. Pure & unit-testable.
 */
fun compareSemver(a: String, b: String): Int {
    fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
        .split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val pa = parts(a); val pb = parts(b)
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val d = (pa.getOrNull(i) ?: 0) - (pb.getOrNull(i) ?: 0)
        if (d != 0) return d
    }
    return 0
}

/** The GitHub repo whose Releases drive the in-app update check. */
private const val GITHUB_REPO = "yzddmr6/PrismSpace"

// ---------------------------------------------------------------------------
// Pure mapper — maps raw flags + PrismSettingsModeState to SettingsUiModel.
// ---------------------------------------------------------------------------

/**
 * Pure mapper: given boolean flags, builds the SettingsUiModel.
 * selectedMode is the user's explicit choice.
 * isActive on each row reflects selectedMode, not live re-detection.
 * Gating (shizukuAvailable/rootAvailable) only determines what modes CAN be
 * selected — it doesn't retroactively change the checkmark once selected.
 */
internal fun mapSettingsUiModel(
    profileOwner: Boolean,
    shizukuAuthorized: Boolean,
    shizukuAvailable: Boolean,
    modeState: PrismSettingsModeState,
    capabilityState: CapabilityState,
    selectedMode: PrismMode = PrismMode.Normal,
    res: StringResolver = zhFallback,
): SettingsUiModel {
    // Mode card body depends on profile-owner and Shizuku authorization state.
    val modeBody = when {
        !profileOwner -> res(R.string.lz_setvm_mode_body_not_created, emptyArray())
        shizukuAuthorized -> res(R.string.lz_setvm_mode_body_shizuku, emptyArray())
        else -> res(R.string.lz_setvm_mode_body_normal, emptyArray())
    }
    val level = when {
        !profileOwner -> PrismLevel.Error
        shizukuAuthorized -> PrismLevel.Ok
        else -> PrismLevel.Ok
    }

    // isActive follows the USER's selectedMode choice (single source of truth).
    // Capability availability is still used for gating, but not for the checkmark.
    val shizukuCapable = capabilityState.shizuku is CapabilityAvailability.Available
    val rootCapable = capabilityState.root is CapabilityAvailability.Available

    return SettingsUiModel(
        modeTitle = res(R.string.lz_setvm_mode_title, emptyArray()),
        modeBody = modeBody,
        level = level,
        profileOwnerReady = profileOwner,
        normalMode = SettingsModeRow(
            title = modeState.normal.title,
            summary = modeState.normal.summary,
            statusLabel = modeState.normal.status,
            isActive = selectedMode == PrismMode.Normal,
        ),
        shizukuAdbMode = SettingsModeRow(
            title = modeState.shizukuAdb.title,
            summary = modeState.shizukuAdb.summary,
            statusLabel = modeState.shizukuAdb.status,
            isActive = selectedMode == PrismMode.Shizuku && shizukuCapable,
        ),
        rootMode = SettingsModeRow(
            title = modeState.root.title,
            summary = modeState.root.summary,
            statusLabel = modeState.root.status,
            isActive = selectedMode == PrismMode.Root && rootCapable,
        ),
        selectedMode = selectedMode,
        spaceActionTitle = if (profileOwner) {
            res(R.string.lz_set_repair_title, emptyArray())
        } else {
            res(R.string.lz_set_create_title, emptyArray())
        },
        spaceActionSummary = if (profileOwner) {
            res(R.string.lz_set_repair_summary, emptyArray())
        } else {
            res(R.string.lz_set_create_summary, emptyArray())
        },
        spaceActionNeedsConfirmation = profileOwner,
    )
}

// ---------------------------------------------------------------------------
// ViewModel backing the Settings screen.
// ---------------------------------------------------------------------------

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val SHIZUKU_PERMISSION_REQUEST = 1601
private const val PRISM_PROBE_PACKAGE = "com.yzddmr6.prismprobe"
private const val BYTES_PER_MB = 1024L * 1024L

/** Centralized PrismSpace repository URL. */
const val PRISM_RELEASES_URL = "https://github.com/yzddmr6/PrismSpace"

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val spaceRepo: SpaceRepository by lazy { SpaceRepositoryProvider.get(getApplication()) }
    private val bridgeHealthRepo: BridgeHealthRepository by lazy { BridgeHealthRepository(getApplication()) }
    private val capRepo: CapabilityRepository by lazy { CapabilityRepositoryProvider.get(getApplication()) }

    private val _uiState = MutableStateFlow<SettingsUiModel?>(null)
    val uiState: StateFlow<SettingsUiModel?> = _uiState

    // Selected mode is owned by CapabilityRepository and shared with Home.
    val selectedMode: StateFlow<PrismMode> get() = capRepo.selectedMode

    // ---------------------------------------------------------------------------
    // Capability refresh
    // ---------------------------------------------------------------------------

    fun refreshCapabilities() {
        viewModelScope.launch {
            val model = withContext(Dispatchers.IO) { buildUiModel() }
            _uiState.value = model
        }
    }

    // ---------------------------------------------------------------------------
    // Shizuku action handling
    // ---------------------------------------------------------------------------

    /**
     * Calls SettingsActionPlanner.shizukuAction and dispatches:
     *   OpenManager → launches Shizuku manager app
     *   RequestPermission → Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
     *   Refresh → refreshCapabilities()
     */
    fun handleShizukuAction() {
        val available = isShizukuAvailable()
        val authorized = isShizukuAuthorized(available)
        val action = SettingsActionPlanner.shizukuAction(available, authorized)
        when (action) {
            com.yzddmr6.prismspace.prism.model.ShizukuSettingsAction.OpenManager -> openShizukuManager()
            com.yzddmr6.prismspace.prism.model.ShizukuSettingsAction.RequestPermission -> requestShizukuPermission()
            com.yzddmr6.prismspace.prism.model.ShizukuSettingsAction.Refresh -> refreshCapabilities()
        }
    }

    // ---------------------------------------------------------------------------
    // Normal mode is always selectable.
    // ---------------------------------------------------------------------------
    fun setNormalMode() {
        capRepo.setSelectedMode(PrismMode.Normal)
        setFeedback(str(R.string.lz_setvm_switched_normal), isError = false)
        refreshCapabilities()
    }

    // ---------------------------------------------------------------------------
    // If Shizuku is authorized, set selected mode to Shizuku via CapabilityRepository
    // and emits success feedback; otherwise keeps current mode + emits error feedback.
    // Returns true if authorized (used by ModeGuideSheet button).
    // ---------------------------------------------------------------------------
    /**
     * 检查更新: query the GitHub Releases API for the latest release, compare its tag_name to the
     * installed versionName, and surface the update dialog (notes + release page) when newer.
     */
    fun checkForUpdate() {
        setFeedback(str(R.string.lz_setvm_update_checking), isError = false)
        viewModelScope.launch {
            val release = withContext(Dispatchers.IO) { fetchLatestRelease() }
            val ctx: Context = getApplication()
            val current = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
                .getOrNull() ?: "0.0.0"
            when {
                release == null -> setFeedback(str(R.string.lz_setvm_update_failed), isError = true)
                compareSemver(release.version, current) > 0 ->
                    _uiState.value = _uiState.value?.copy(updateInfo = UpdateInfo(
                        release.version.trim().removePrefix("v").removePrefix("V"), release.notes, release.url))
                else -> setFeedback(str(R.string.lz_setvm_update_latest, current), isError = false)
            }
        }
    }

    /** Dismiss the update dialog (用户点「稍后」). */
    fun dismissUpdate() { _uiState.value = _uiState.value?.copy(updateInfo = null) }

    private fun fetchLatestRelease(): ReleaseInfo? = runCatching {
        val conn = (java.net.URL("https://api.github.com/repos/$GITHUB_REPO/releases/latest").openConnection()
            as java.net.HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PrismSpace")
        }
        try {
            if (conn.responseCode != 200) return null
            val json = org.json.JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            ReleaseInfo(
                version = json.optString("tag_name"),
                notes = json.optString("body").ifBlank { json.optString("name") },
                url = json.optString("html_url").ifBlank { "https://github.com/$GITHUB_REPO/releases" })
        } finally { conn.disconnect() }
    }.getOrNull()

    private data class ReleaseInfo(val version: String, val notes: String, val url: String)

    fun checkShizuku(): Boolean {
        val available = isShizukuAvailable()
        val authorized = isShizukuAuthorized(available)
        if (authorized) {
            capRepo.setSelectedMode(PrismMode.Shizuku)
            setFeedback(str(R.string.lz_setvm_shizuku_connected), isError = false)
            refreshCapabilities()
        } else {
            setFeedback(str(R.string.lz_setvm_shizuku_not_ready), isError = true)
        }
        return authorized
    }

    // ---------------------------------------------------------------------------
    // Root detection/request flow.
    // For detection: runs Shell.SU.run("id") — same libsuperuser Shell pattern.
    // If root grant succeeds, reports availability; if not, reports unavailable.
    // (The codebase has no stored root-enable toggle; this detects/requests root.)
    // ---------------------------------------------------------------------------
    // If root is granted, set selected mode to Root via CapabilityRepository + success feedback;
    // otherwise keeps current mode + error feedback. Never marks Root active unless granted.
    fun requestRoot() {
        viewModelScope.launch {
            setFeedback(str(R.string.lz_setvm_root_requesting), isError = false)
            val result = withContext(Dispatchers.IO) {
                try {
                    // libsuperuser returns stdout only when su execution was granted.
                    val out = Shell.SU.run("id")
                    out != null && out.isNotEmpty()
                } catch (e: Exception) {
                    false
                }
            }
            if (result) {
                capRepo.setSelectedMode(PrismMode.Root)
                setFeedback(str(R.string.lz_setvm_root_granted), isError = false)
            } else {
                // Keep current mode unchanged — do NOT switch to Root on failure
                setFeedback(str(R.string.lz_setvm_root_denied), isError = true)
            }
            refreshCapabilities()
        }
    }

    // ---------------------------------------------------------------------------
    // Freeze or unfreeze every user-facing app in the dual space.
    // The package list is sourced via SpaceRepository.
    // ---------------------------------------------------------------------------
    fun suspendSpace(suspend: Boolean) {
        viewModelScope.launch {
            val context: Context = getApplication()
            val result: SuspendResult = withContext(Dispatchers.IO) {
                try {
                    val dual = spaceRepo.dualSpace() ?: return@withContext SuspendResult.NoSpace
                    // Only the user's cloned apps participate in a space freeze.
                    // Exclude (a) system apps auto-provisioned into the work profile
                    // (settings/dialer/Play/files/…) — infrastructure DPM refuses to
                    // suspend, and (b) PrismSpace itself — the profile owner can't suspend
                    // its own admin package.
                    val self = context.packageName
                    // Operate on the user's 分身 only — same definition as the Space tab / count
                    // third-party clones, plus system apps the user explicitly cloned.
                    val apps = spaceRepo.installedApps(dual)
                        .filter {
                            it.isInstalled && it.packageName != self &&
                                (!it.isSystem || UserCloneRegistry.contains(context, it.packageName))
                        }
                        .toList()
                    if (apps.isEmpty()) return@withContext SuspendResult.NoApps
                    // 冻结整个空间 uses the same profile-owner freeze path as per-app 冻结,
                    // keeping badges and recovery behavior consistent.
                    val failed = PrismAppControl.setSpaceFrozen(apps, suspend)
                    when {
                        failed == null -> SuspendResult.NoSpace          // profile not ready
                        failed.isEmpty() -> SuspendResult.Ok
                        failed.size >= apps.size -> SuspendResult.AllFail  // nothing succeeded
                        else -> SuspendResult.PartialFail(failed.size)
                    }
                } catch (e: Exception) { SuspendResult.Error(e.message ?: e.javaClass.simpleName) }
            }
            when (result) {
                SuspendResult.Ok -> {
                    setFeedback(
                        if (suspend) str(R.string.lz_setvm_space_suspended)
                        else str(R.string.lz_setvm_space_resumed),
                        isError = false,
                    )
                    val current = _uiState.value ?: return@launch
                    _uiState.value = current.copy(spaceSuspended = suspend)
                }
                SuspendResult.NoSpace -> setFeedback(
                    str(R.string.lz_setvm_space_not_ready), isError = true)
                SuspendResult.NoApps -> setFeedback(
                    if (suspend) str(R.string.lz_setvm_no_apps_to_suspend)
                    else str(R.string.lz_setvm_no_apps_to_resume), isError = true)
                // All failed → explain the cause + prerequisite (not a vague "partially failed").
                SuspendResult.AllFail -> setFeedback(
                    if (suspend) str(R.string.lz_setvm_all_fail_freeze)
                    else str(R.string.lz_setvm_all_fail_unfreeze), isError = true)
                is SuspendResult.PartialFail -> setFeedback(
                    if (suspend) str(R.string.lz_setvm_partial_fail_suspend, result.count)
                    else str(R.string.lz_setvm_partial_fail_resume, result.count), isError = true)
                is SuspendResult.Error -> setFeedback(
                    if (suspend) str(R.string.lz_setvm_suspend_failed, result.detail)
                    else str(R.string.lz_setvm_resume_failed, result.detail), isError = true)
            }
        }
    }

    private sealed class SuspendResult {
        object Ok : SuspendResult()
        object NoSpace : SuspendResult()
        object NoApps : SuspendResult()
        object AllFail : SuspendResult()
        data class PartialFail(val count: Int) : SuspendResult()
        data class Error(val detail: String) : SuspendResult()
    }

    fun setExperimentalMultiProfile(enabled: Boolean) {
        ExperimentalFlags.setMultiProfileEnabled(getApplication(), enabled)
        _uiState.value = _uiState.value?.copy(experimentalMultiProfile = enabled)
    }

    // ---------------------------------------------------------------------------
    // Create/repair entry point. When the profile is absent, this opens the normal setup
    // wizard. When it exists but is paused/locked, it asks Android to bring that profile back.
    // ---------------------------------------------------------------------------
    fun repairSpace(context: Context) {
        viewModelScope.launch {
            val appContext = context.applicationContext
            val outcome = withContext(Dispatchers.IO) {
                runCatching { Users.refreshUsers(appContext) }
                    .onFailure { DiagnosticLog.w(TAG, "refresh users before repair failed", it) }
                val profile = Users.profile ?: return@withContext RepairSpaceOutcome.StartSetup
                val dual = spaceRepo.dualSpace() ?: return@withContext RepairSpaceOutcome.StartSetup
                val usability = spaceRepo.usabilityOf(dual).let { current ->
                    if (current == SpaceUsability.Unknown || current == SpaceUsability.BridgeNotReady) {
                        runCatching { bridgeHealthRepo.refreshHealth(profile) }
                            .onFailure { DiagnosticLog.w(TAG, "refresh bridge health before repair failed", it) }
                        spaceRepo.usabilityOf(dual)
                    } else {
                        current
                    }
                }
                when (usability) {
                    SpaceUsability.NotProvisioned ->
                        RepairSpaceOutcome.StartSetup
                    SpaceUsability.Suspended,
                    SpaceUsability.LockedNeedsUnlock ->
                        RepairSpaceOutcome.Activate(profile)
                    SpaceUsability.BridgeNotReady ->
                        RepairSpaceOutcome.RepairBridge(profile)
                    SpaceUsability.Unknown ->
                        RepairSpaceOutcome.RepairBridge(profile)
                    SpaceUsability.Usable ->
                        RepairSpaceOutcome.AlreadyReady
                }
            }
            when (outcome) {
                RepairSpaceOutcome.StartSetup -> {
                    setFeedback(str(R.string.lz_setvm_opening_setup), isError = false)
                    SetupFlow.open(context)
                }
				is RepairSpaceOutcome.Activate -> {
					setFeedback(str(R.string.lz_setvm_repairing), isError = false)
					val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
						runCatching { Users.requestQuietModeDisabled(context, outcome.profile) }
							.onFailure { DiagnosticLog.e(TAG, "profile activation failed", it) }
							.getOrDefault(false)
					} else {
						false
					}
                    if (ok) {
                        setFeedback(str(R.string.lz_setvm_space_resumed), isError = false)
                    } else {
                        setFeedback(str(R.string.lz_setvm_repair_failed, str(R.string.lz_setvm_profile_activation_failed)), isError = true)
                    }
                    refreshCapabilities()
                }
                is RepairSpaceOutcome.RepairBridge -> {
                    setFeedback(str(R.string.lz_setvm_bridge_repairing), isError = false)
                    val opened = ProfileEntryLauncher.start(context, outcome.profile)
                    if (opened) {
                        setFeedback(str(R.string.lz_setvm_bridge_repair_opened_profile), isError = false)
                    } else {
                        setFeedback(str(R.string.lz_setvm_repair_failed, str(R.string.lz_setvm_profile_activation_failed)), isError = true)
                    }
                    refreshCapabilities()
                }
                RepairSpaceOutcome.AlreadyReady -> {
                    setFeedback(str(R.string.lz_setvm_space_already_ready), isError = false)
                    refreshCapabilities()
                }
            }
        }
    }

    // Delete is exposed from Settings, but still delegates to the existing space deletion path.
    fun deleteDualSpace(activity: Activity?) {
        if (activity == null) {
            setFeedback(str(R.string.lz_space_delete_target_missing), isError = true)
            return
        }
        val res: StringResolver = prismResolver(getApplication())
        viewModelScope.launch {
            setFeedback(res(R.string.lz_vm_deleting_space, emptyArray()), isError = false)
            val space = withContext(Dispatchers.IO) {
                runCatching { Users.refreshUsers(getApplication()) }
                    .onFailure { DiagnosticLog.w(TAG, "refresh users before settings delete failed", it) }
                spaceRepo.dualSpace()
            }
            if (space == null) {
                setFeedback(res(R.string.lz_space_delete_target_missing, emptyArray()), isError = true)
                refreshCapabilities()
                return@launch
            }
            val result: DeleteSpaceResult =
                if (space.userId == Users.currentId())
                    DeleteSpaceResult.FellBackToSelfDestroy(PrismSetup.destroyProfileDirect(activity))
                else SpaceProvisioningEngine.deleteSpace(getApplication(), space)
            val fb = provisioningFeedback(result, res)
            setFeedback(fb.message, isError = fb.isError)
            if (fb.routeToSystemRemoval) PrismSetup.promptManualRemoval(activity)
            refreshCapabilities()
        }
    }

    fun exportLogs(context: Context) {
        viewModelScope.launch {
            setFeedback(str(R.string.lz_setvm_sharing_report), isError = false)
            try {
                val (fileName, uri) = withContext(Dispatchers.IO) {
                    DiagnosticLog.i(TAG, "diagnostic export start")
                    val profileSections = collectProfileDiagnostics(context.applicationContext)
                    val file = DiagnosticLog.createExportFile(
                        context,
                        extraSections = profileSections,
                        includeLogcat = true,
                    )
                    file.name to DiagnosticLog.shareUri(context, file)
                }
                val intent = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, str(R.string.lz_setvm_report_subject))
                    .putExtra(Intent.EXTRA_TEXT, str(R.string.lz_setvm_report_attached, fileName))
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .apply { clipData = ClipData.newUri(context.contentResolver, fileName, uri) }
                context.startActivity(
                    Intent.createChooser(intent, str(R.string.lz_setvm_report_subject))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
                DiagnosticLog.i(TAG, "diagnostic export share sheet opened file=$fileName")
            } catch (e: Exception) {
                DiagnosticLog.e(TAG, "diagnostic export failed", e)
                setFeedback(str(R.string.lz_setvm_export_failed, e.message ?: e.javaClass.simpleName), isError = true)
            }
        }
    }

    // Alias retained for existing callers.
    fun exportDiagnostics(context: Context) = exportLogs(context)

    // ---------------------------------------------------------------------------
    // PrismProbe launcher.
    // ---------------------------------------------------------------------------

    fun runProbe(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(PRISM_PROBE_PACKAGE)
        if (intent == null) {
            setFeedback(str(R.string.lz_setvm_probe_not_installed), isError = true)
            return
        }
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            setFeedback(str(R.string.lz_setvm_probe_open_failed, e.message ?: e.javaClass.simpleName), isError = true)
        }
    }

    // ---------------------------------------------------------------------------
    // One-shot performance snapshot. No background polling.
    // ---------------------------------------------------------------------------

    fun performanceSnapshot() {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MB
        val maxMb = runtime.maxMemory() / BYTES_PER_MB
        val threads = Thread.activeCount()
        val cores = runtime.availableProcessors()
        val msg = str(R.string.lz_setvm_perf_snapshot, usedMb, maxMb, threads, cores)
        setFeedback(msg, isError = false)
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    private fun buildUiModel(): SettingsUiModel {
        val context: Context = getApplication()
        runCatching { Users.refreshUsers(context) }
            .onFailure { DiagnosticLog.w(TAG, "refresh users before settings state failed", it) }
        val shizukuAvailable = isShizukuAvailable()
        val shizukuAuthorized = isShizukuAuthorized(shizukuAvailable)
        val profileOwner = Users.profile?.let { Users.isProfileManagedByPrism(context, it) } == true
        DiagnosticLog.d(
            TAG,
            "settings state profile=${Users.profile?.toId() ?: Users.NULL_ID} " +
                "profileOwner=$profileOwner shizukuAvailable=$shizukuAvailable shizukuAuthorized=$shizukuAuthorized",
        )

        val modeState = PrismSettingsModeState.from(
            shizuku = when {
                shizukuAuthorized -> PrismShizukuAdbStatus.Ready
                shizukuAvailable -> PrismShizukuAdbStatus.WaitingAuthorization
                else -> PrismShizukuAdbStatus.NotRunning
            },
            root = PrismRootStatus.NotDetected,
            res = prismResolver(context),
        )

        // Settings-local capability detection for mode-status display.
        // Home shows the configured mode via CapabilityRepository.
        val capabilityState = CapabilityService().buildState(
            profileOwner = profileOwner,
            shizukuAvailable = shizukuAvailable,
            shizukuReady = shizukuAuthorized,
            adbReady = false,
            rootDetected = false,
            rootEnabled = false,
        )

        val current = _uiState.value
        // Pass the configured mode so isActive always reflects the user's choice.
        val result = mapSettingsUiModel(
            profileOwner = profileOwner,
            shizukuAuthorized = shizukuAuthorized,
            shizukuAvailable = shizukuAvailable,
            modeState = modeState,
            capabilityState = capabilityState,
            selectedMode = capRepo.selectedMode.value,
            res = prismResolver(getApplication()),
        )
        // Preserve existing feedback message + spaceSuspended if any
        return result.copy(
            feedbackMessage = current?.feedbackMessage,
            feedbackIsError = current?.feedbackIsError ?: false,
            spaceSuspended = current?.spaceSuspended ?: false,
            experimentalMultiProfile = ExperimentalFlags.isMultiProfileEnabled(getApplication()),
        )
    }

    private sealed interface RepairSpaceOutcome {
        data object StartSetup : RepairSpaceOutcome
        data class Activate(val profile: android.os.UserHandle) : RepairSpaceOutcome
        data class RepairBridge(val profile: android.os.UserHandle) : RepairSpaceOutcome
        data object AlreadyReady : RepairSpaceOutcome
    }

    private fun collectProfileDiagnostics(context: Context): List<DiagnosticSection> {
        val profile = Users.profile ?: return listOf(DiagnosticSection(
            title = "Dual-space diagnostic snapshot",
            body = "No managed profile is currently known to the main space.",
        ))
        val health = ShuttleProvider.health(context, profile)
        val healthSection = DiagnosticSection(
            title = "Dual-space shuttle health user=${profile.toId()}",
            body = health.diagnosticLine(),
        )
        return try {
            val descriptorOutcome = Shuttle(context, to = profile).invokeOutcomeWithin(timeoutMs = 4_500L) {
                DiagnosticLog.openSnapshotDescriptor(this)
            }
            val body = when (descriptorOutcome) {
                is ShuttleOutcome.Value -> descriptorOutcome.value?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
                } ?: "Dual-space diagnostic snapshot returned no descriptor."
                is ShuttleOutcome.NotReady ->
                    "Dual-space diagnostic snapshot unavailable: shuttle is not ready (${descriptorOutcome.cause})."
                ShuttleOutcome.TimedOut ->
                    "Dual-space diagnostic snapshot unavailable: shuttle timed out."
                is ShuttleOutcome.Failed ->
                    "Failed to collect dual-space diagnostics: ${descriptorOutcome.error.message ?: descriptorOutcome.error.javaClass.simpleName}"
                is ShuttleOutcome.Skipped ->
                    "Dual-space diagnostic snapshot skipped: ${descriptorOutcome.reason}"
            }
            listOf(healthSection, DiagnosticSection(
                title = "Dual-space diagnostic snapshot user=${profile.toId()}",
                body = body,
            ))
        } catch (e: Exception) {
            DiagnosticLog.e(TAG, "dual-space diagnostic collection failed", e)
            listOf(healthSection, DiagnosticSection(
                title = "Dual-space diagnostic snapshot user=${profile.toId()}",
                body = "Failed to collect dual-space diagnostics: ${e.message ?: e.javaClass.simpleName}",
            ))
        }
    }

    // Launch the Shizuku manager if installed.
    private fun openShizukuManager() {
        val context: Context = getApplication()
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (intent == null) {
            setFeedback(str(R.string.lz_setvm_shizuku_not_installed), isError = true)
            return
        }
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: android.content.ActivityNotFoundException) {
            setFeedback(str(R.string.lz_setvm_shizuku_open_failed), isError = true)
        }
    }

    // Request Shizuku permission in-process.
    private fun requestShizukuPermission() {
        try {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
            setFeedback(str(R.string.lz_setvm_shizuku_permission_requested), isError = false)
        } catch (e: RuntimeException) {
            setFeedback(str(R.string.lz_setvm_shizuku_permission_failed, e.message ?: e.javaClass.simpleName), isError = true)
        }
    }

    /** Clears the feedback message after it has been shown (called by the UI layer). */
    fun clearFeedback() {
        val current = _uiState.value ?: return
        _uiState.value = current.copy(feedbackMessage = null, feedbackIsError = false)
    }

    /** Locale-aware string resolution following the user's in-app language override. */
    private fun str(resId: Int, vararg args: Any): String =
        PrismLocale.wrap(getApplication()).getString(resId, *args)

    private fun setFeedback(message: String, isError: Boolean) {
        val current = _uiState.value
        if (current != null) {
            _uiState.value = current.copy(feedbackMessage = message, feedbackIsError = isError)
        } else {
            // state not yet loaded; create a minimal placeholder
            _uiState.value = SettingsUiModel(
                modeTitle = "", modeBody = "", level = PrismLevel.Ok,
                profileOwnerReady = false,
                normalMode = SettingsModeRow("", "", "", false),
                shizukuAdbMode = SettingsModeRow("", "", "", false),
                rootMode = SettingsModeRow("", "", "", false),
                feedbackMessage = message,
                feedbackIsError = isError,
                selectedMode = capRepo.selectedMode.value,
            )
        }
        AppFeedbackBus.emit(ActionFeedback(message, isError))
    }

    private fun isShizukuAvailable(): Boolean = ShizukuUtil.isAvailable()

    private fun isShizukuAuthorized(available: Boolean = isShizukuAvailable()): Boolean =
        ShizukuUtil.isAuthorized()
}

private const val TAG = "Prism.SettingsVM"
