package com.yzddmr6.prismspace.prism.compose.vm

import android.content.pm.ApplicationInfo
import com.yzddmr6.prismspace.bridge.SystemAppChoice
import com.yzddmr6.prismspace.bridge.SystemAppOverrideChange
import com.yzddmr6.prismspace.bridge.SystemAppSelectionEntry
import com.yzddmr6.prismspace.provisioning.SelectionStatus
import com.yzddmr6.prismspace.provisioning.SystemAppDefaults
import com.yzddmr6.prismspace.provisioning.SystemAppTarget

/** A main-space launcher app the user may bring into the dual space by enabling its system package. */
data class SystemAppCandidate(
    val pkg: String,
    val label: String,
    val inDefault: Boolean,
    /** Current profile-side policy target; null = not managed / not reported. */
    val target: SystemAppTarget?,
)

/** A /data/app OEM preinstall: needs the normal copy + user-confirmed install path. */
data class PreinstalledCandidate(val pkg: String, val label: String)

/** One main-space launcher app as seen by the picker (Android-free input). */
data class MainLauncherApp(
    val pkg: String,
    val label: String,
    val flags: Int,
    val sourceDir: String?,
    val installer: String?,
    val installedInDual: Boolean,
)

/** Output of [commitSelection]: override changes for the profile policy plus preinstalls to stage. */
data class SelectionCommit(val changes: List<SystemAppOverrideChange>, val prepare: List<String>)

/**
 * HyperOS ships OEM apps (gallery, calendar, clock…) as non-system packages directly under
 * /data/app/<Name>/ with no installer, while user installs live in /data/app/~~<rand>/<pkg>-<rand>/
 * (or legacy /data/app/<pkg>-N/). Only the former is "preinstalled" [推测 for other ROMs].
 */
fun isOemDataAppPreinstall(flags: Int, sourceDir: String?, packageName: String, installer: String?): Boolean {
    if (flags and ApplicationInfo.FLAG_SYSTEM != 0) return false
    if (!installer.isNullOrEmpty()) return false
    val path = sourceDir ?: return false
    val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
    if (parent.substringBeforeLast('/', missingDelimiterValue = "") != "/data/app") return false
    val dir = parent.substringAfterLast('/')
    return dir.isNotEmpty() && !dir.startsWith("~~") && !dir.startsWith("$packageName-")
}

/** Splits main-space launcher apps into the two picker groups; critical packages are never offered. */
fun pickerGroups(
    mainApps: List<MainLauncherApp>,
    entries: List<SystemAppSelectionEntry>,
    selfPackage: String,
): Pair<List<SystemAppCandidate>, List<PreinstalledCandidate>> {
    val byPkg = entries.associateBy { it.pkg }
    val system = ArrayList<SystemAppCandidate>()
    val preinstalled = ArrayList<PreinstalledCandidate>()
    mainApps.filter { it.pkg != selfPackage }.distinctBy { it.pkg }.forEach { app ->
        if (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) {
            val entry = byPkg[app.pkg]
            if (entry?.critical == true) return@forEach
            system += SystemAppCandidate(app.pkg, app.label, entry?.inDefault ?: (app.pkg in SystemAppDefaults.packages), entry?.target)
        } else if (!app.installedInDual && isOemDataAppPreinstall(app.flags, app.sourceDir, app.pkg, app.installer)) {
            preinstalled += PreinstalledCandidate(app.pkg, app.label)
        }
    }
    return system.sortedBy { it.label.lowercase() } to preinstalled.sortedBy { it.label.lowercase() }
}

/** First-run (Pending, or never initialized) proposes the default set; later visits show reality. */
fun preselectedSystemApps(status: SelectionStatus?, candidates: List<SystemAppCandidate>): Set<String> =
    if (status == null || status == SelectionStatus.Pending) candidates.filter { it.inDefault }.mapTo(LinkedHashSet()) { it.pkg }
    else candidates.filter { it.target == SystemAppTarget.Available }.mapTo(LinkedHashSet()) { it.pkg }

/**
 * Overrides only record deviations from the rule result under [statusAfter]: a choice equal to
 * "default set ∧ confirmed" is cleared, anything else is an explicit Enabled/Disabled.
 * Preinstalls are never pre-checked by the caller; every checked one is staged for install.
 */
fun commitSelection(
    statusAfter: SelectionStatus,
    candidates: List<SystemAppCandidate>,
    checked: Set<String>,
    preinstalledChecked: Set<String> = emptySet(),
): SelectionCommit {
    val changes = candidates.map { candidate ->
        val desired = candidate.pkg in checked
        val ruleResult = statusAfter == SelectionStatus.Confirmed && candidate.inDefault
        val choice = when {
            desired == ruleResult -> SystemAppChoice.Clear
            desired -> SystemAppChoice.Enabled
            else -> SystemAppChoice.Disabled
        }
        SystemAppOverrideChange(candidate.pkg, choice)
    }
    return SelectionCommit(changes, preinstalledChecked.toList())
}

/** Picker phases. A failed commit returns to [Ready] with an error; the profile status never advances. */
sealed interface SystemAppPickerPhase {
    data object Waiting : SystemAppPickerPhase
    data class NotReady(val reason: String) : SystemAppPickerPhase
    data object Ready : SystemAppPickerPhase
    data object Submitting : SystemAppPickerPhase
    data object Done : SystemAppPickerPhase
}

data class SystemAppPickerUiState(
    val phase: SystemAppPickerPhase = SystemAppPickerPhase.Waiting,
    val status: SelectionStatus? = null,
    val system: List<SystemAppCandidate> = emptyList(),
    val preinstalled: List<PreinstalledCandidate> = emptyList(),
    val checked: Set<String> = emptySet(),
    val preinstalledChecked: Set<String> = emptySet(),
    val error: String? = null,
)

object SystemAppPickerReducer {
    fun loaded(state: SystemAppPickerUiState, status: SelectionStatus?, system: List<SystemAppCandidate>,
               preinstalled: List<PreinstalledCandidate>) = state.copy(
        phase = SystemAppPickerPhase.Ready, status = status, system = system, preinstalled = preinstalled,
        checked = preselectedSystemApps(status, system), preinstalledChecked = emptySet(), error = null,
    )

    fun toggle(state: SystemAppPickerUiState, pkg: String): SystemAppPickerUiState = when {
        state.phase != SystemAppPickerPhase.Ready -> state
        state.system.any { it.pkg == pkg } -> state.copy(checked = state.checked.toggled(pkg))
        state.preinstalled.any { it.pkg == pkg } -> state.copy(preinstalledChecked = state.preinstalledChecked.toggled(pkg))
        else -> state
    }

    fun submitting(state: SystemAppPickerUiState) =
        if (state.phase == SystemAppPickerPhase.Ready) state.copy(phase = SystemAppPickerPhase.Submitting, error = null) else state

    /** @param confirmedStatus the profile status reported after a successful commit; ignored on failure. */
    fun committed(state: SystemAppPickerUiState, success: Boolean, error: String?, confirmedStatus: SelectionStatus?) =
        if (success) state.copy(phase = SystemAppPickerPhase.Done, status = confirmedStatus, error = null)
        else state.copy(phase = SystemAppPickerPhase.Ready, error = error)

    private fun Set<String>.toggled(pkg: String) = if (pkg in this) this - pkg else this + pkg
}
