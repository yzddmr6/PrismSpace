package com.yzddmr6.prismspace.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_BOOT_COMPLETED
import android.content.Intent.ACTION_MY_PACKAGE_REPLACED
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.yzddmr6.prismspace.PrismNameManager
import com.yzddmr6.prismspace.settings.profile.PrismProfileEntryScreen
import com.yzddmr6.prismspace.shuttle.ShuttleProvider
import com.yzddmr6.prismspace.util.DevicePolicies
import com.yzddmr6.prismspace.util.PrismLocale
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.enableComponent

/**
 * Profile-side LAUNCHER entry for PrismSpace (work-profile "棱镜-双开空间" icon).
 *
 * Why this exists: Android DPM requires the ProfileOwner to expose at least one
 * LAUNCHER activity in the managed profile. This activity serves three roles:
 *
 *   1. Required visible icon for ProfileOwner (system constraint)
 *   2. Profile-side identifier for `Users.refreshUsers()` — that lookup compares the
 *      activity NAME (not class string) against parent-profile LauncherActivity to
 *      detect PrismSpace-managed profiles
 *   3. UX entry point — tapping shows a Compose status page and offers a "返回主空间"
 *      cross-profile bounce (best-effort)
 *
 * UI: pure Compose ComponentActivity. No databinding, Fragment, or PreferenceFragment.
 */
class PrismSettingsActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(PrismLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sync this profile's display name back to the parent so parent-side UI
        // shows the renamed value promptly.
        if (! Users.isParentProfile()) {
            runCatching { ShuttleProvider.initializeFromProfileForeground(this) }
                .onFailure { Log.w(TAG, "Shuttle foreground initialize failed", it) }
            runCatching { PrismNameManager.syncNameToParentProfile(this) }
                .onFailure { Log.w(TAG, "syncNameToParentProfile failed", it) }
        }
        setContent { PrismProfileEntryScreen() }
    }

    /**
     * One-time enabler that runs on USER_INITIALIZE / MY_PACKAGE_REPLACED in managed profile.
     *
     * Enables [PrismSettingsActivity] (which is android:enabled="false" by default in manifest)
     * and disables LauncherActivity in the profile so the work-profile launcher only shows the
     * intended PrismSettings icon (one icon, label "棱镜-双开空间").
     */
    class Enabler : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            if (
                intent.action != ACTION_BOOT_COMPLETED &&
                intent.action != ACTION_MY_PACKAGE_REPLACED &&
                intent.action != Intent.ACTION_USER_INITIALIZE
            ) return
            if (Users.isParentProfile()) return     // Should never happen
            if (! DevicePolicies(context).isProfileOwner) return        // Profile managed by other app

            Log.i(TAG, "Enabling ${PrismSettingsActivity::class.java.simpleName}")
            context.enableComponent<PrismSettingsActivity>()
            // Disable the parent-profile launcher trampoline in this managed profile so it
            // does not appear in the work-profile launcher.
            runCatching {
                context.packageManager.setComponentEnabledSetting(
                    android.content.ComponentName(context, com.yzddmr6.prismspace.LauncherActivity::class.java),
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP)
            }.onFailure { Log.w(TAG, "Failed to disable LauncherActivity in profile", it) }
        }
    }
}

private const val TAG = "Prism.ISA"
