package com.yzddmr6.prismspace.prism.compose.nav

import android.content.Context
import android.preference.PreferenceManager
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Main-side "a space was just created, ask once" marker. It survives process restarts; whether the
 * picker is still due is decided only by the profile-side selection status (Pending).
 */
object SystemAppPickerPrompt {
    /** @param userId the created space when known (privileged path); null = first managed space. */
    data class Expectation(val userId: Int?)

    private const val KEY_EXPECTED = "system_app_picker_expected"
    private const val KEY_USER = "system_app_picker_user"
    private val state = MutableStateFlow<Expectation?>(null)
    @Volatile private var loaded = false

    fun expected(context: Context): StateFlow<Expectation?> {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                val prefs = prefs(context)
                state.value = if (prefs.getBoolean(KEY_EXPECTED, false))
                    Expectation(prefs.getInt(KEY_USER, -1).takeIf { it >= 0 }) else null
                loaded = true
            }
        }
        return state
    }

    @JvmStatic fun markExpected(context: Context, userId: Int?) {
        prefs(context).edit().putBoolean(KEY_EXPECTED, true).putInt(KEY_USER, userId ?: -1).apply()
        loaded = true
        state.value = Expectation(userId)
        DiagnosticLog.i(TAG, "picker_expected u=${userId ?: "first"}")
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_EXPECTED).remove(KEY_USER).apply()
        loaded = true
        state.value = null
    }

    @Suppress("DEPRECATION")
    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    private const val TAG = "Prism.SysAppPicker"
}
