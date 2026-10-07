package com.yzddmr6.prismspace.prism.compose.nav

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow

object AppLaunchSignals {
    private val activationChannel = Channel<Unit>(capacity = Channel.CONFLATED)
    val activateSpace: Flow<Unit> = activationChannel.receiveAsFlow()
    fun signalActivateSpace() { activationChannel.trySend(Unit) }

    // "去启用" from the clone install-method selector (which lives in a Fragment, not the Compose
    // NavHost): jump to Settings and auto-open the run-mode guide so the user can enable Shizuku/Root.
    private val runModeChannel = Channel<Unit>(capacity = Channel.CONFLATED)
    val openRunMode: Flow<Unit> = runModeChannel.receiveAsFlow()
    fun signalOpenRunMode() { runModeChannel.trySend(Unit) }

    // Multi-select on the Space screen owns the whole screen (batch bar at top + per-app checks).
    // The global 4-tab bottom nav must hide so it doesn't compete with the batch context. SpaceScreen
    // mirrors its multi-select state here; PrismNavHost observes it to gate the bottom bar.
    private val _multiSelectActive = MutableStateFlow(false)
    val multiSelectActive: StateFlow<Boolean> = _multiSelectActive
    fun setMultiSelectActive(active: Boolean) { _multiSelectActive.value = active }

    // Home「添加分身」→ Space tab with the MAIN segment selected (the clone source list).
    // Nonce StateFlow (not a Channel): the Space screen may be recreated after the signal and must
    // still see it once — collectors acknowledge via a remembered last-handled nonce.
    private val _openSpaceMainSegment = MutableStateFlow(0)
    val openSpaceMainSegment: StateFlow<Int> = _openSpaceMainSegment
    fun signalOpenSpaceMainSegment() { _openSpaceMainSegment.value += 1 }

    // Settings「系统应用」→ Space tab (PrismNavHost switches tabs), dual segment + system-apps
    // view open (SpaceScreen consumes the same nonce).
    private val _openSpaceSystemApps = MutableStateFlow(0)
    val openSpaceSystemApps: StateFlow<Int> = _openSpaceSystemApps
    fun signalOpenSpaceSystemApps() { _openSpaceSystemApps.value += 1 }

    // Space screen「添加系统应用」→ the system-app selection page (PrismNavHost navigates).
    // One-shot: a recreated Activity must never replay an old request (device 9.6 crash / stale space).
    data class SystemAppPickerRequest(val userId: Int, val origin: String)
    private val openSystemAppPickerSignal = OneShotSignal<SystemAppPickerRequest>()
    val openSystemAppPicker: Flow<SystemAppPickerRequest> = openSystemAppPickerSignal.requests
    fun signalOpenSystemAppPicker(userId: Int, origin: String) {
        openSystemAppPickerSignal.emit(SystemAppPickerRequest(userId, origin))
    }
}
