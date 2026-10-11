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
    val vendorProfileType: VendorProfileType = VendorProfileType.Unknown,
)

/** Platform user type of a profile, as read from the public API (API 35+); [Unknown] below it or
 *  when unreadable. Only [Clone] and [Private] change classification: they are never PrismSpace's. */
enum class VendorProfileType { Unknown, Managed, Clone, Private, Other }

/**
 * The single ownership partition of the visible non-parent profiles produced by
 * [SpaceStateClassifier.ownership]. Ids are in rank order (foreign: by user id). [managed] is the
 * set every "which dual space" consumer works on; [primary] is the one classified state target.
 */
data class ProfileOwnership(
    val primary: Int?,
    val owned: List<Int>,
    val surplus: List<Int>,
    val foreign: List<Int>,
    val managed: List<Int>,
) {
    fun diagnosticLine(): String =
        "primary=${primary ?: "-"} owned=$owned surplus=$surplus foreign=$foreign"

    companion object {
        val EMPTY = ProfileOwnership(null, emptyList(), emptyList(), emptyList(), emptyList())
    }
}

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

    /** Clone / private profiles are never PrismSpace's, whatever is installed in them. */
    fun isForeign(profile: SpaceProfileFacts, prismPackage: String): Boolean =
        profile.vendorProfileType == VendorProfileType.Clone ||
            profile.vendorProfileType == VendorProfileType.Private ||
            !hasPrismOwnershipEvidence(profile, prismPackage)

    /** Provisioning finished here (marker) or PrismSpace is the confirmed profile owner. */
    fun isOwned(profile: SpaceProfileFacts, prismPackage: String): Boolean =
        profile.ownershipMarkerPresent || profile.profileOwnerPackage == prismPackage

    /**
     * The only ownership ranking. Foreign profiles are dropped first. Ownership evidence outranks
     * every other signal, a marker is the strongest per-Prism evidence, package presence is the
     * recovery evidence, and user id is only a stable tie-breaker. Android's profile enumeration
     * order is never trusted.
     */
    fun rank(facts: SpaceFacts, prismPackage: String): List<SpaceProfileFacts> =
        facts.profiles.filterNot { isForeign(it, prismPackage) }.sortedWith(
            compareByDescending<SpaceProfileFacts> { hasPrismOwnershipEvidence(it, prismPackage) }
                .thenByDescending { it.ownershipMarkerPresent }
                .thenByDescending { it.prismPackagePresent }
                .thenBy { it.userId },
        )

    /** The one profile every "current dual space" consumer targets; null when none is ours. */
    fun primary(facts: SpaceFacts, prismPackage: String): SpaceProfileFacts? =
        rank(facts, prismPackage).firstOrNull()

    /** Every provisioned profile (several in the experimental multi-space mode), rank order. */
    fun owned(facts: SpaceFacts, prismPackage: String): List<SpaceProfileFacts> =
        rank(facts, prismPackage).filter { isOwned(it, prismPackage) }

    /** Profiles with our evidence that are neither provisioned nor the primary (e.g. a second
     *  package-only profile). Never a target of any action. */
    fun surplus(facts: SpaceFacts, prismPackage: String): List<SpaceProfileFacts> {
        val ranked = rank(facts, prismPackage)
        val primary = ranked.firstOrNull()
        return ranked.filter { it.userId != primary?.userId && !isOwned(it, prismPackage) }
    }

    /** owned ∪ {primary}, rank order. A package-only primary stays managed so half-provisioning
     *  repair can still probe and resume it over the bridge. */
    fun managed(facts: SpaceFacts, prismPackage: String): List<SpaceProfileFacts> {
        val ranked = rank(facts, prismPackage)
        val primary = ranked.firstOrNull()
        return ranked.filter { it.userId == primary?.userId || isOwned(it, prismPackage) }
    }

    fun foreign(facts: SpaceFacts, prismPackage: String): List<SpaceProfileFacts> =
        facts.profiles.filter { isForeign(it, prismPackage) }.sortedBy { it.userId }

    fun ownership(facts: SpaceFacts, prismPackage: String): ProfileOwnership = ProfileOwnership(
        primary = primary(facts, prismPackage)?.userId,
        owned = owned(facts, prismPackage).map { it.userId },
        surplus = surplus(facts, prismPackage).map { it.userId },
        foreign = foreign(facts, prismPackage).map { it.userId },
        managed = managed(facts, prismPackage).map { it.userId },
    )

    /**
     * Classify the [primary] candidate by its first failed invariant. Without any candidate the
     * state is [SpaceState.ForeignProfile] for the lowest foreign id, or [SpaceState.NoProfile].
     * The returned state's userId always equals [primary]'s.
     */
    fun classify(facts: SpaceFacts, prismPackage: String): SpaceState {
        val profile = rank(facts, prismPackage).firstOrNull()
            ?: return foreign(facts, prismPackage).firstOrNull()
                ?.let { SpaceState.ForeignProfile(it.userId, it.profileOwnerPackage) }
                ?: SpaceState.NoProfile

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
