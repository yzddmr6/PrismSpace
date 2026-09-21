package com.yzddmr6.prismspace.setup.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Retained state for [com.yzddmr6.prismspace.setup.SetupActivity].
 *
 * Survives configuration changes (rotation, dark-mode toggle) via the standard
 * ViewModelStore. The sealed UI state does not survive process death, but the
 * primitive "waiting for managed provisioning" fact does via [SavedStateHandle]
 * so a system-owned provisioning hand-off cannot strand the recreated setup UI.
 *
 * This is intentionally distinct from
 * [com.yzddmr6.prismspace.setup.SetupViewModel], which holds provisioning
 * business logic rather than lifecycle UI state.
 */
class SetupStateViewModel(private val savedState: SavedStateHandle) : ViewModel() {

    private val _uiState = MutableStateFlow<SetupUiState>(SetupUiState.Welcome)
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    /** Sticky across rotation. */
    var incompleteSetupAcked: Boolean = false

    var provisioningLaunched: Boolean
        get() = savedState[KEY_PROVISIONING_LAUNCHED] ?: false
        set(value) { savedState[KEY_PROVISIONING_LAUNCHED] = value }

    /** Elapsed-realtime stamp of the last provisioning launch, or null when unknown. The result
     *  code alone cannot tell a system refusal from a user cancellation; the time the user had to
     *  interact can, so it must survive process death together with [provisioningLaunched]. */
    val provisioningLaunchedAt: Long? get() = savedState.get<Long>(KEY_PROVISIONING_LAUNCHED_AT)

    /** True when this launch happened despite a pre-flight that said the platform disallows it. */
    val provisioningPrecheckRefused: Boolean get() = savedState.get<Boolean>(KEY_PRECHECK_REFUSED) ?: false

    fun beginProvisioning(launchedAtElapsedMs: Long? = null, precheckRefused: Boolean = false) {
        provisioningLaunched = true
        if (launchedAtElapsedMs != null) savedState[KEY_PROVISIONING_LAUNCHED_AT] = launchedAtElapsedMs
        else savedState.remove<Long>(KEY_PROVISIONING_LAUNCHED_AT)
        savedState[KEY_PRECHECK_REFUSED] = precheckRefused
        savedState.get<Long>(KEY_CONVERGENCE_DEADLINE)?.let { savedState.remove<Long>(KEY_CONVERGENCE_DEADLINE) }
    }

    /** Elapsed time since the launch, or null when the launch stamp is unknown. */
    fun provisioningElapsed(nowElapsedMs: Long): Long? =
        provisioningLaunchedAt?.let { (nowElapsedMs - it).coerceAtLeast(0L) }

    fun convergenceRemaining(nowElapsedMs: Long, timeoutMs: Long): Long {
        val deadline = savedState.get<Long>(KEY_CONVERGENCE_DEADLINE)
            ?: (nowElapsedMs + timeoutMs).also { savedState[KEY_CONVERGENCE_DEADLINE] = it }
        return (deadline - nowElapsedMs).coerceAtLeast(0L)
    }

    fun consumeProvisioningLaunched(): Boolean {
        if (!provisioningLaunched) return false
        provisioningLaunched = false
        savedState.remove<Long>(KEY_CONVERGENCE_DEADLINE)
        savedState.remove<Long>(KEY_PROVISIONING_LAUNCHED_AT)
        savedState.remove<Boolean>(KEY_PRECHECK_REFUSED)
        return true
    }

    fun setUiState(next: SetupUiState) {
        _uiState.value = next
    }

    internal companion object {
        const val KEY_PROVISIONING_LAUNCHED = "provisioning_launched"
        const val KEY_CONVERGENCE_DEADLINE = "provisioning_convergence_deadline"
        const val KEY_PROVISIONING_LAUNCHED_AT = "provisioning_launched_at"
        const val KEY_PRECHECK_REFUSED = "provisioning_precheck_refused"
    }
}
