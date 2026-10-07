package com.yzddmr6.prismspace.provisioning

import android.content.Context

/** Profile-side persisted policy state of one dual space (the single source of truth). */
data class SystemAppPolicyState(
    val status: SelectionStatus? = null,
    val overrides: Map<String, SystemAppOverride> = emptyMap(),
    val lastApplied: Map<String, SystemAppTarget> = emptyMap(),
)

interface SystemAppPolicyPersistence {
    fun read(): SystemAppPolicyState
    /** @return whether the state was durably written. */
    fun write(state: SystemAppPolicyState): Boolean
}

/** SharedPreferences("system_app_policy") inside the profile user the applier runs in. */
class SharedPrefsSystemAppPolicyPersistence(context: Context) : SystemAppPolicyPersistence {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): SystemAppPolicyState {
        val status = prefs.getString(KEY_STATUS, null)?.let { name ->
            SelectionStatus.values().firstOrNull { it.name == name }
        }
        val overrides = LinkedHashMap<String, SystemAppOverride>()
        prefs.getStringSet(KEY_OVERRIDES_ON, emptySet()).orEmpty().forEach { overrides[it] = SystemAppOverride.Enabled }
        prefs.getStringSet(KEY_OVERRIDES_OFF, emptySet()).orEmpty().forEach { overrides[it] = SystemAppOverride.Disabled }
        val lastApplied = LinkedHashMap<String, SystemAppTarget>()
        prefs.getStringSet(KEY_APPLIED_AVAILABLE, emptySet()).orEmpty().forEach { lastApplied[it] = SystemAppTarget.Available }
        prefs.getStringSet(KEY_APPLIED_UNAVAILABLE, emptySet()).orEmpty().forEach { lastApplied[it] = SystemAppTarget.Unavailable }
        return SystemAppPolicyState(status, overrides, lastApplied)
    }

    override fun write(state: SystemAppPolicyState): Boolean = prefs.edit()
        .putString(KEY_STATUS, state.status?.name)
        .putStringSet(KEY_OVERRIDES_ON, state.overrides.filterValues { it == SystemAppOverride.Enabled }.keys.toSet())
        .putStringSet(KEY_OVERRIDES_OFF, state.overrides.filterValues { it == SystemAppOverride.Disabled }.keys.toSet())
        .putStringSet(KEY_APPLIED_AVAILABLE, state.lastApplied.filterValues { it == SystemAppTarget.Available }.keys.toSet())
        .putStringSet(KEY_APPLIED_UNAVAILABLE, state.lastApplied.filterValues { it == SystemAppTarget.Unavailable }.keys.toSet())
        .commit()

    private companion object {
        const val FILE = "system_app_policy"
        const val KEY_STATUS = "status"
        const val KEY_OVERRIDES_ON = "overrides_on"
        const val KEY_OVERRIDES_OFF = "overrides_off"
        const val KEY_APPLIED_AVAILABLE = "last_applied_available"
        const val KEY_APPLIED_UNAVAILABLE = "last_applied_unavailable"
    }
}
