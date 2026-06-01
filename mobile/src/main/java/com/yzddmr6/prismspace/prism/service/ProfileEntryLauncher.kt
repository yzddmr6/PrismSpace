package com.yzddmr6.prismspace.prism.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.settings.PrismSettingsActivity
import com.yzddmr6.prismspace.util.Users.Companion.toId

internal object ProfileEntryLauncher {
    fun component(packageName: String): ComponentName =
        ComponentName(packageName, PrismSettingsActivity::class.java.name)

    fun start(context: Context, profile: UserHandle): Boolean =
        runCatching {
            val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return false
            val component = component(context.packageName)
            if (!launcherApps.isActivityEnabled(component, profile)) {
                DiagnosticLog.w(TAG, "profile entry disabled component=$component user=${profile.toId()}")
                return false
            }
            launcherApps.startMainActivity(component, profile, null, null)
            DiagnosticLog.i(TAG, "profile entry launched component=$component user=${profile.toId()}")
            true
        }.onFailure {
            DiagnosticLog.w(TAG, "profile entry launch failed user=${profile.toId()}", it)
        }.getOrDefault(false)

    private const val TAG = "Prism.ProfileEntry"
}
