package com.yzddmr6.prismspace.prism.compose.space

import com.yzddmr6.prismspace.space.SpaceBridgeCause
import com.yzddmr6.prismspace.space.SpaceState
import com.yzddmr6.prismspace.space.SpaceStateClassifier

enum class SpacePresentationKind {
    Checking,
    Unavailable,
    Missing,
    Orphan,
    Provisioning,
    Incomplete,
    Inactive,
    Locked,
    BridgeUnavailable,
    Ready,
}

/** One lossless semantic projection shared by all screens; copy and layout remain screen-owned. */
data class SpacePresentation(
    val kind: SpacePresentationKind,
    val state: SpaceState?,
    val lastKnown: SpaceState? = null,
    val recovery: SpaceRecoveryPlan? = null,
    val bridgeCause: SpaceBridgeCause? = null,
) {
    val isReady: Boolean get() = kind == SpacePresentationKind.Ready
    /** Whether a PrismSpace-owned profile exists. Foreign profiles belong to other apps/system
     *  features and never count. */
    val hasProfile: Boolean get() = state != null && !SpaceStateClassifier.ownProfileAbsent(state)
    val permitsStateChange: Boolean get() = state != null
}

fun presentSpace(snapshot: SpaceSnapshot): SpacePresentation = when (snapshot) {
    SpaceSnapshot.Loading -> SpacePresentation(SpacePresentationKind.Checking, state = null)
    is SpaceSnapshot.Failed -> SpacePresentation(
        SpacePresentationKind.Unavailable,
        state = null,
        lastKnown = snapshot.lastKnown,
    )
    is SpaceSnapshot.Loaded -> presentSpace(snapshot.state)
}

fun presentSpace(state: SpaceState): SpacePresentation = when (state) {
    SpaceState.NoProfile -> SpacePresentation(
        SpacePresentationKind.Missing,
        state,
        recovery = SpaceRecoveryPlan.StartSetup,
    )
    /** A foreign profile is not PrismSpace's to repair or remove; the space simply does not
     *  exist yet, so the honest recovery is creating one. */
    is SpaceState.ForeignProfile -> SpacePresentation(
        SpacePresentationKind.Missing,
        state,
        recovery = SpaceRecoveryPlan.StartSetup,
    )
    is SpaceState.OrphanProfile -> SpacePresentation(
        SpacePresentationKind.Orphan,
        state,
        recovery = SpaceRecoveryPlan.OpenSystemProfileSettings(state.userId),
    )
    is SpaceState.Provisioning -> SpacePresentation(
        SpacePresentationKind.Provisioning,
        state,
        recovery = SpaceRecoveryPlan.WaitForProvisioning(state.userId),
    )
    is SpaceState.HalfProvisioned -> SpacePresentation(
        SpacePresentationKind.Incomplete,
        state,
        recovery = if (state.resumable) SpaceRecoveryPlan.RepairIncrementally(state.userId)
        else SpaceRecoveryPlan.ActivateThenOpenEntry(state.userId),
    )
    is SpaceState.Inactive -> SpacePresentation(
        SpacePresentationKind.Inactive,
        state,
        recovery = SpaceRecoveryPlan.Activate(state.userId),
    )
    is SpaceState.Locked -> SpacePresentation(
        SpacePresentationKind.Locked,
        state,
        recovery = SpaceRecoveryPlan.OpenProfileUnlock(state.userId),
    )
    is SpaceState.BridgeDown -> SpacePresentation(
        SpacePresentationKind.BridgeUnavailable,
        state,
        recovery = SpaceRecoveryPlan.ReconnectBridge(state.userId),
        bridgeCause = state.cause,
    )
    is SpaceState.Healthy -> SpacePresentation(
        SpacePresentationKind.Ready,
        state,
        recovery = SpaceRecoveryPlan.AlreadyReady(state.userId),
    )
}
