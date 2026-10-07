package com.yzddmr6.prismspace.prism.compose.vm

import android.content.pm.LauncherApps
import com.yzddmr6.prismspace.data.PrismAppInfo
import com.yzddmr6.prismspace.data.PrismAppListProvider
import com.yzddmr6.prismspace.util.Users.Companion.isParentProfile

/** One launchability verdict for rows, the action sheet, launch and shortcuts; never keyed on "is system". */
enum class AppLaunchability { Launchable, Paused, NoLauncherEntry }

/**
 * @param hasEnabledLauncherEntry whether the package has an enabled launcher activity in its own
 *        space; null when unknown (no fresh source covers a hidden package).
 */
fun resolveLaunchability(hasEnabledLauncherEntry: Boolean?, hidden: Boolean, suspended: Boolean): AppLaunchability = when {
    hasEnabledLauncherEntry == false -> AppLaunchability.NoLauncherEntry
    hidden || suspended -> AppLaunchability.Paused         // Entry true or unknown: offer resume, re-evaluate afterwards.
    hasEnabledLauncherEntry == true -> AppLaunchability.Launchable
    else -> AppLaunchability.NoLauncherEntry
}

/**
 * Launcher-entry fact for one dual-space package. Visible packages are read from LauncherApps
 * now; a hidden package is invisible there, so the profile-side snapshot carried by the app list
 * page (enabled entries only, hidden packages included) is used instead.
 */
internal fun dualLauncherEntry(app: PrismAppInfo, visibleLauncherPackages: Set<String>?): Boolean? {
    // An action entry (Settings without a launcher component on HyperOS) is an entry as well.
    if (dualEntryAction(app) != null) return true
    return when {
        !app.isHidden && visibleLauncherPackages != null -> app.packageName in visibleLauncherPackages
        !app.isHidden -> hasLauncherActivity(app)
        else -> PrismAppListProvider.getInstance(app.context()).snapshotLauncherEntry(app.user, app.packageName)
    }
}

/** Profile-side action entry of a dual-space package without a launcher activity, if any. */
internal fun dualEntryAction(app: PrismAppInfo): String? =
    if (app.user.isParentProfile()) null
    else PrismAppListProvider.getInstance(app.context()).snapshotEntryAction(app.user, app.packageName)

internal fun hasLauncherActivity(app: PrismAppInfo): Boolean? = runCatching {
    app.context().getSystemService(LauncherApps::class.java)!!.getActivityList(app.packageName, app.user).isNotEmpty()
}.getOrNull()

/** Fresh launchability of [app]; the parent user keeps its own launcher resolution. */
internal fun currentLaunchability(app: PrismAppInfo): AppLaunchability =
    if (app.user.isParentProfile()) {
        if (app.isLaunchable) AppLaunchability.Launchable else AppLaunchability.NoLauncherEntry
    } else resolveLaunchability(dualLauncherEntry(app, null), app.isHidden, app.isSuspended)
