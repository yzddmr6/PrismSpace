package com.yzddmr6.prismspace.prism.transfer

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.engine.CrossProfile
import com.yzddmr6.prismspace.util.Modules
import com.yzddmr6.prismspace.util.Users

/**
 * Brings the main-space PrismSpace UI to the foreground from inside the dual space. Mirrors the two
 * routes of the engine's `launchMainActivityInOwnerUser`: LauncherApps first, then the
 * MAIN + PARENT_PROFILE intent forwarder (managed → parent, FLAG_PARENT_CAN_ACCESS_MANAGED).
 */
internal object ParentEntryLauncher {
    private const val TAG = "Prism.TransferOpen"

    fun start(context: Context): Boolean {
        val parent = runCatching { Users.parentProfile }.getOrNull() ?: return false
        val launched = runCatching {
            val activity = Modules.getMainLaunchActivity(context)
            val apps = context.getSystemService(LauncherApps::class.java)
            if (apps != null && apps.isActivityEnabled(activity, parent)) {
                apps.startMainActivity(activity, parent, null, null)
                DiagnosticLog.i(TAG, "parent entry launched route=launcher_apps")
                true
            } else {
                false
            }
        }.onFailure { DiagnosticLog.w(TAG, "parent entry launcher-apps route failed", it) }.getOrDefault(false)
        if (launched) return true
        return runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            CrossProfile.decorateIntentForActivityInParentProfile(context, intent)
            context.startActivity(intent)
            DiagnosticLog.i(TAG, "parent entry launched route=intent_forwarder")
            true
        }.onFailure { DiagnosticLog.w(TAG, "parent entry forwarder route failed", it) }.getOrDefault(false)
    }
}
