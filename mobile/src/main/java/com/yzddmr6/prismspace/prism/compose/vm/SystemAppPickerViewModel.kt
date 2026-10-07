package com.yzddmr6.prismspace.prism.compose.vm

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.os.Build
import android.os.SystemClock
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.bridge.SelectionFinish
import com.yzddmr6.prismspace.bridge.SystemAppChoice
import com.yzddmr6.prismspace.controller.PrismAppClones
import com.yzddmr6.prismspace.controller.SystemAppSelectionClient
import com.yzddmr6.prismspace.data.PrismAppListProvider
import com.yzddmr6.prismspace.data.helper.installed
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.nav.SYSTEM_APP_PICKER_ORIGIN_SETUP
import com.yzddmr6.prismspace.prism.compose.nav.SYSTEM_APP_PICKER_ORIGIN_SPACE
import com.yzddmr6.prismspace.prism.compose.nav.SystemAppPickerPrompt
import com.yzddmr6.prismspace.prism.compose.space.SpaceStateRepository
import com.yzddmr6.prismspace.prism.compose.space.SpaceUsability
import com.yzddmr6.prismspace.prism.compose.space.spaceUsabilityFromState
import com.yzddmr6.prismspace.prism.service.ProfileBridgeResult
import com.yzddmr6.prismspace.prism.service.SpaceIconLoader
import com.yzddmr6.prismspace.prism.service.profileBridgeFailureMessage
import com.yzddmr6.prismspace.provisioning.SelectionStatus
import com.yzddmr6.prismspace.util.LauncherAppsCompat
import com.yzddmr6.prismspace.util.PrismLocale
import com.yzddmr6.prismspace.util.UserHandles
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "选择系统应用" page. Waits for the dual space to become usable, reads the profile-side
 * selection (the only source of truth), and commits through the system app policy. Preinstalled
 * OEM apps go through the normal copy + user-confirmed install path, never a silent install.
 */
class SystemAppPickerViewModel(app: Application, savedState: SavedStateHandle) : AndroidViewModel(app) {

    val userId: Int = savedState.get<Int>(ARG_USER_ID) ?: savedState.get<String>(ARG_USER_ID)?.toIntOrNull() ?: -1
    val origin: String = savedState.get<String>(ARG_ORIGIN) ?: SYSTEM_APP_PICKER_ORIGIN_SPACE

    private val _state = MutableStateFlow(SystemAppPickerUiState())
    val state: StateFlow<SystemAppPickerUiState> = _state
    val icons = SpaceIconLoader(app, viewModelScope)

    init { load() }

    fun load() {
        _state.value = SystemAppPickerUiState()
        viewModelScope.launch {
            val context: Context = getApplication()
            val usability = awaitUsable()
            if (usability != SpaceUsability.Usable) {
                DiagnosticLog.i(TAG, "picker_not_ready u=$userId usability=$usability")
                _state.value = _state.value.copy(phase = SystemAppPickerPhase.NotReady(usability.name))
                return@launch
            }
            val result = withContext(Dispatchers.IO) { SystemAppSelectionClient.readSelection(context, userId) }
            val selection = (result as? ProfileBridgeResult.Value)?.value
            if (selection == null) {
                DiagnosticLog.i(TAG, "picker_not_ready u=$userId usability=bridge:${result.javaClass.simpleName}")
                _state.value = _state.value.copy(phase = SystemAppPickerPhase.NotReady("bridge"))
                return@launch
            }
            val (system, preinstalled) = withContext(Dispatchers.IO) {
                pickerGroups(mainLauncherApps(context), selection.entries, context.packageName)
            }
            val next = SystemAppPickerReducer.loaded(_state.value, selection.status, system, preinstalled)
            _state.value = next
            DiagnosticLog.i(TAG, "picker_open u=$userId origin=$origin status=${selection.status} system=${system.size}" +
                " preinstalled=${preinstalled.size} preselected=${next.checked.size}")
        }
    }

    fun toggle(pkg: String) { _state.value = SystemAppPickerReducer.toggle(_state.value, pkg) }

    fun confirm(activity: FragmentActivity) {
        val current = _state.value
        if (current.phase != SystemAppPickerPhase.Ready) return
        _state.value = SystemAppPickerReducer.submitting(current)
        val context: Context = getApplication()
        val res = prismResolver(context)
        val commit = commitSelection(SelectionStatus.Confirmed, current.system, current.checked, current.preinstalledChecked)
        fun names(choice: SystemAppChoice) = commit.changes.filter { it.choice == choice }.joinToString(",", "[", "]") { it.pkg }
        DiagnosticLog.i(TAG, "picker_commit u=$userId enable=${names(SystemAppChoice.Enabled)} disable=${names(SystemAppChoice.Disabled)}" +
            " clear=${names(SystemAppChoice.Clear)} prepare=${commit.prepare.joinToString(",", "[", "]")}")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                SystemAppSelectionClient.apply(context, userId, commit.changes, SelectionFinish.Confirm)
            }
            val report = (result as? ProfileBridgeResult.Value)?.value
            if (report == null) {
                // Status is unchanged profile-side; stay here and say so instead of reporting success.
                _state.value = SystemAppPickerReducer.committed(_state.value, success = false, confirmedStatus = null,
                    error = profileBridgeFailureMessage(context, result, res(R.string.lz_sysapp_picker_unavailable, emptyArray())))
                return@launch
            }
            SystemAppSelectionClient.record(context, userId, report, chosen = current.checked)
            val counts = if (commit.prepare.isEmpty()) BatchCloneCounts(0, 0, 0) else runBatchClone(
                commit.prepare,
                BatchClonePort { pkg ->
                    val app = PrismAppListProvider.getInstance(context)[pkg, Users.parentProfile]
                        ?: return@BatchClonePort BatchCloneResult.Failed()
                    PrismAppClones(activity, this@SystemAppPickerViewModel, app).requestForBatch(forceNormalPreparation = true)
                },
                activate = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching {
                        Users.requestQuietModeDisabled(context, UserHandles.of(userId))
                    }.getOrDefault(false) else false
                },
            )
            val enabled = report.available.count { it in current.checked }
            val absent = report.absent.count { it in current.checked }
            val failed = report.failed.count { it in current.checked } + counts.failed
            DiagnosticLog.i(TAG, "picker_result u=$userId available=$enabled absent=$absent failed=${report.failed.size}" +
                " prepared=${counts.prepared + counts.installed} prepareFailed=${counts.failed}")
            val summary = res(R.string.lz_sysapp_picker_result, arrayOf(enabled, counts.prepared + counts.installed))
            val issues = if (absent + failed > 0) res(R.string.lz_sysapp_picker_result_issues, arrayOf(absent, failed)) else null
            AppFeedbackBus.emit(ActionFeedback(listOfNotNull(summary, issues).joinToString("；"), isError = issues != null))
            SystemAppPickerPrompt.clear(context)
            _state.value = SystemAppPickerReducer.committed(_state.value, success = true, error = null,
                confirmedStatus = SelectionStatus.Confirmed)
        }
    }

    /** 稍后: ends a pending first-run prompt without touching any non-critical system app. */
    fun later() {
        val current = _state.value
        val context: Context = getApplication()
        if (current.phase == SystemAppPickerPhase.Submitting) return
        if (origin != SYSTEM_APP_PICKER_ORIGIN_SETUP || current.phase != SystemAppPickerPhase.Ready) {
            // Nothing to record (opened from the Space screen, or the space is not reachable yet).
            if (origin == SYSTEM_APP_PICKER_ORIGIN_SETUP) SystemAppPickerPrompt.clear(context)
            _state.value = current.copy(phase = SystemAppPickerPhase.Done)
            return
        }
        _state.value = SystemAppPickerReducer.submitting(current)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                SystemAppSelectionClient.apply(context, userId, emptyList(), SelectionFinish.Defer)
            }
            if (result is ProfileBridgeResult.Value && result.value != null) {
                DiagnosticLog.i(TAG, "picker_defer u=$userId")
                SystemAppPickerPrompt.clear(context)
                _state.value = SystemAppPickerReducer.committed(_state.value, success = true, error = null,
                    confirmedStatus = SelectionStatus.Deferred)
            } else _state.value = SystemAppPickerReducer.committed(_state.value, success = false, confirmedStatus = null,
                error = profileBridgeFailureMessage(context, result, prismResolver(context)(R.string.lz_sysapp_picker_unavailable, emptyArray())))
        }
    }

    private suspend fun awaitUsable(): SpaceUsability {
        val repo = SpaceStateRepository(getApplication())
        val deadline = SystemClock.elapsedRealtime() + WAIT_MS
        var usability = SpaceUsability.Unknown
        while (true) {
            runCatching { repo.refresh("system_app_picker") }
            usability = spaceUsabilityFromState(repo.currentState(), userId)
            if (usability == SpaceUsability.Usable || SystemClock.elapsedRealtime() >= deadline) return usability
            delay(POLL_MS)
        }
    }

    private fun mainLauncherApps(context: Context): List<MainLauncherApp> {
        val pm = PrismLocale.wrap(context).packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val profile = UserHandles.of(userId)
        val apps = LauncherAppsCompat(context)
        return pm.queryIntentActivities(launcher, 0).mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .map { info ->
                val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
                MainLauncherApp(
                    pkg = info.packageName,
                    label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.packageName),
                    flags = info.flags,
                    sourceDir = info.sourceDir,
                    installer = if (system) null else installerOf(context, info.packageName),
                    installedInDual = !system && apps.getApplicationInfoNoThrows(info.packageName, MATCH_UNINSTALLED_PACKAGES, profile)
                        ?.installed == true,
                )
            }
    }

    private fun installerOf(context: Context, pkg: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.packageManager.getInstallSourceInfo(pkg).installingPackageName
        else @Suppress("DEPRECATION") context.packageManager.getInstallerPackageName(pkg)
    }.getOrElse { "unknown" }      // Unknown provenance is never treated as an OEM preinstall.

    companion object {
        const val ARG_USER_ID = "userId"
        const val ARG_ORIGIN = "origin"
        private const val WAIT_MS = 20_000L
        private const val POLL_MS = 1_500L
        private const val TAG = "Prism.SysAppPicker"
    }
}
