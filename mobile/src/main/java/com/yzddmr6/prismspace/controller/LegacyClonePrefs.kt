package com.yzddmr6.prismspace.controller

import android.content.Context
import android.preference.PreferenceManager
import androidx.annotation.WorkerThread
import com.yzddmr6.prismspace.analytics.DiagnosticLog

/**
 * One-time removal of the retired main-space clone registry; whether a system package is the
 * user's 分身 now lives profile-side (the policy target, see `ProfileAppEntry.policyEnabled`).
 * Idempotent without a marker: once the key is gone, later launches do nothing and log nothing.
 */
object LegacyClonePrefs {

    internal const val KEY = "prism_user_cloned_pkgs"

    /** Entries to report when the retired key is present; null when there is nothing to purge. */
    internal fun registryPurgeCount(present: Boolean, stored: Set<String>?): Int? =
        if (!present) null else stored?.size ?: 0

    @WorkerThread
    fun purge(context: Context) {
        @Suppress("DEPRECATION")
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        val n = registryPurgeCount(prefs.contains(KEY), runCatching { prefs.getStringSet(KEY, null) }.getOrNull())
            ?: return
        prefs.edit().remove(KEY).apply()
        DiagnosticLog.i(TAG, "registry.purged keys=$n")
    }

    private const val TAG = "Prism.Housekeeping"
}
