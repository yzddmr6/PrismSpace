package com.yzddmr6.prismspace.prism.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import android.widget.Toast
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.settings.PrismSettingsActivity
import com.yzddmr6.prismspace.util.PrismLocale

/**
 * Trampoline that runs INSIDE the dual space (work profile), reached from the main space through
 * the PROFILE_DOWNLOADS cross-profile forwarding (see ProfileDownloadsOpener.openInstallEntry). It
 * always opens the dual-space PrismSpace entry, where a cloned APK suite is installed through a
 * foreground PackageInstaller session (split packages included).
 *
 * Translucent + noHistory + finishes immediately.
 */
class ProfileDownloadsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            // With or without EXTRA_OPEN_INSTALL_ENTRY the only remaining destination is the entry.
            openProfileEntryForForegroundInstall()
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "Unable to open profile downloads", e)
        } finally {
            finish()
        }
    }

    private fun openProfileEntryForForegroundInstall() {
        val component = ComponentName(this, PrismSettingsActivity::class.java)
        packageManager.setComponentEnabledSetting(
            component,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        startActivity(Intent(this, PrismSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Toast.makeText(
            this,
            PrismLocale.wrap(this).getString(R.string.lz_pf_install_use_prismspace),
            Toast.LENGTH_LONG,
        ).show()
    }

    private companion object {
        private const val TAG = "Prism.ProfileDownloads"
    }
}
