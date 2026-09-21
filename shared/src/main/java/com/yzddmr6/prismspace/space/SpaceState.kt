package com.yzddmr6.prismspace.space

/** Android-free facts for one profile in the current profile group. */
data class SpaceProfileFacts(
    val userId: Int,
    val prismPackagePresent: Boolean,
    val ownershipMarkerPresent: Boolean,
    val provisioningActive: Boolean = false,
    val running: Boolean = false,
    val unlocked: Boolean = false,
    val quietMode: Boolean = false,
    val bridgeReady: Boolean? = null,
    val bridgeCause: SpaceBridgeCause = SpaceBridgeCause.NotChecked,
    val profileOwner: Boolean? = null,
    val provisionComplete: Boolean? = null,
    val profileOwnerPackage: String? = null,
)

data class SpaceFacts(val profiles: List<SpaceProfileFacts>)

enum class SpaceBridgeCause { NotChecked, PermissionDenied, ProfileUnavailable, ProviderUnavailable, TimedOut, Failed }

sealed interface SpaceState {
    val userId: Int?

    data object NoProfile : SpaceState { override val userId: Int? = null }
    data class OrphanProfile(override val userId: Int) : SpaceState
    /** A profile that carries no PrismSpace ownership evidence (e.g. MIUI XSpace, another DPC's
     *  work profile, Samsung Secure Folder). Never repaired, blocked or deleted by PrismSpace. */
    data class ForeignProfile(override val userId: Int, val ownerPackage: String? = null) : SpaceState
    data class Provisioning(override val userId: Int) : SpaceState
    data class HalfProvisioned(override val userId: Int, val resumable: Boolean) : SpaceState
    data class Locked(override val userId: Int) : SpaceState
    data class Inactive(override val userId: Int) : SpaceState
    data class BridgeDown(override val userId: Int, val cause: SpaceBridgeCause) : SpaceState
    data class Healthy(override val userId: Int) : SpaceState
}

object SpaceStateClassifier {
    /** A profile is PrismSpace's responsibility only when positive ownership evidence exists.
     *  Bare profile presence proves nothing: MIUI XSpace (user 999), OEM clone users, Secure
     *  Folder and other DPCs' work profiles all appear in the same profile group, and must
     *  never be treated as PrismSpace orphans.
     *  Profile names are deliberately NOT evidence: they are not readable without fragile
     *  hidden-API access, and vendor-generic names ("工作资料") prove nothing anyway. A root-flow
     *  leftover without any of these signals degrades to foreign — creation then surfaces the
     *  honest system managed-profile cap instead of a false repair loop. */
    fun hasPrismOwnershipEvidence(profile: SpaceProfileFacts, prismPackage: String): Boolean =
        profile.prismPackagePresent || profile.ownershipMarkerPresent ||
            profile.profileOwnerPackage == prismPackage

    /** True when no PrismSpace-owned profile exists: nothing at all, or only foreign profiles
     *  (vendor clone users, other DPCs). Creation is allowed and no repair applies. */
    fun ownProfileAbsent(state: SpaceState?): Boolean =
        state == SpaceState.NoProfile || state is SpaceState.ForeignProfile

    /**
     * Select one deterministic PrismSpace candidate, then classify by the first failed invariant.
     * Ownership evidence outranks every other signal: without it a profile is foreign, never an
     * orphan. A marker is the strongest per-Prism evidence, package presence is the recovery
     * evidence, and user id is only a stable tie-breaker. Android's profile enumeration order is
     * never trusted.
     */
    fun classify(facts: SpaceFacts, prismPackage: String): SpaceState {
        val profile = facts.profiles.sortedWith(
            compareByDescending<SpaceProfileFacts> { hasPrismOwnershipEvidence(it, prismPackage) }
                .thenByDescending { it.ownershipMarkerPresent }
                .thenByDescending { it.prismPackagePresent }
                .thenBy { it.userId },
        ).firstOrNull() ?: return SpaceState.NoProfile

        if (!hasPrismOwnershipEvidence(profile, prismPackage)) {
            return SpaceState.ForeignProfile(profile.userId, profile.profileOwnerPackage)
        }
        if (!profile.prismPackagePresent) return SpaceState.OrphanProfile(profile.userId)
        if (profile.provisioningActive) return SpaceState.Provisioning(profile.userId)
        if (!profile.ownershipMarkerPresent) {
            val resumable = profile.bridgeReady == true && profile.profileOwner == true
            return SpaceState.HalfProvisioned(profile.userId, resumable)
        }
        if (profile.quietMode || !profile.running) return SpaceState.Inactive(profile.userId)
        if (!profile.unlocked) return SpaceState.Locked(profile.userId)
        if (profile.bridgeReady != true) return SpaceState.BridgeDown(profile.userId, profile.bridgeCause)
        return SpaceState.Healthy(profile.userId)
    }
}
