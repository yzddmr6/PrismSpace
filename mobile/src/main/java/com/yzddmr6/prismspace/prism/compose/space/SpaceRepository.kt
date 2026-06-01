package com.yzddmr6.prismspace.prism.compose.space

import com.yzddmr6.prismspace.data.PrismAppInfo

enum class PrismSpaceKind { Main, Dual }

/** Canonical, repo-owned space descriptor. Carries the raw userId (UserHandle resolved inside the impl).
 *  Intentionally separate from the android-free sealed `prism.model.Space` (do NOT pollute that). */
data class PrismSpace(
    val id: String,
    val userId: Int,
    val kind: PrismSpaceKind,
    val displayName: String,
)

/** Single source of truth for "which spaces exist" + per-space app listing.
 *  The ONLY abstraction VMs/clone-target-enum may depend on (no direct Users/AppListProvider elsewhere). */
interface SpaceRepository {
    fun spaces(): List<PrismSpace>
    fun mainSpace(): PrismSpace
    fun dualSpace(): PrismSpace?
    /** ALL managed-profile (Dual) spaces, ordered by userId ascending. N=1 ⇒ single element. */
    fun dualSpaces(): List<PrismSpace>
    /** Stable lookup by PrismSpace.id (any kind, including "main"). */
    fun space(id: String): PrismSpace?
    /** Cheap CE-unlock-aware usability of the given space; bridge state comes from cache only. */
    fun usabilityOf(space: PrismSpace): SpaceUsability
    fun installedApps(space: PrismSpace): Collection<PrismAppInfo>
    /** Replaces PrismAppClones `targets.size` magic; == 1(main)+#managed-profiles. */
    fun cloneTargetSpaceCount(): Int
}
