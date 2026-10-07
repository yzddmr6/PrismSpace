package com.yzddmr6.prismspace.provisioning

/** A user's explicit per-space choice for one system package. */
enum class SystemAppOverride { Enabled, Disabled }

/** Profile-side selection progress. Gates whether the built-in default set is in effect. */
enum class SelectionStatus { Pending, Deferred, Confirmed }

/** Platform facts about one package, read inside the profile the policy applies to. */
data class PackageFact(
    val pkg: String,
    /** ApplicationInfo.FLAG_SYSTEM, read in this profile. */
    val isSystem: Boolean,
    /** Declares MAIN/LAUNCHER, disabled components included (same scope as AOSP provisioning). */
    val hasLauncherEntry: Boolean,
    /** ApplicationInfo.FLAG_INSTALLED for this profile user. */
    val installed: Boolean,
)

data class PolicyInputs(
    /** Default set already gated by [SelectionStatus], see [effectiveDefaultSet]. */
    val defaultSet: Set<String>,
    /** Critical packages (PR-A invariant): always enabled, unhidden and unsuspended. */
    val criticalSet: Set<String>,
    /** System input methods: never hidden or suspended by the policy, and never forced on. */
    val exemptSet: Set<String>,
    val overrides: Map<String, SystemAppOverride>,
    val facts: List<PackageFact>,
)

data class TargetState(val enabled: Boolean, val hidden: Boolean, val suspended: Boolean) {
    companion object {
        val AVAILABLE = TargetState(enabled = true, hidden = false, suspended = false)
        /** "Removed" semantics of managed-profile provisioning: hidden + suspended; enabled=false means "do not install". */
        val UNAVAILABLE = TargetState(enabled = false, hidden = true, suspended = true)
    }
}

/** Coarse persisted form of a [TargetState]. */
enum class SystemAppTarget { Available, Unavailable }

fun TargetState.kind(): SystemAppTarget =
    if (this == TargetState.AVAILABLE) SystemAppTarget.Available else SystemAppTarget.Unavailable

/**
 * The single source of the per-package target state of system apps in a dual space.
 * Rules are evaluated by priority, the first match wins (design §1.1, R1–R8).
 */
object SystemAppPolicy {

    /** Only packages the policy manages are returned; an absent package means "do not touch". */
    fun evaluate(inputs: PolicyInputs): Map<String, TargetState> {
        val facts = inputs.facts.associateBy(PackageFact::pkg)
        val candidates = LinkedHashSet<String>().apply {
            addAll(inputs.criticalSet)
            addAll(inputs.overrides.keys)
            addAll(inputs.defaultSet)
            addAll(facts.keys)
        }
        val result = LinkedHashMap<String, TargetState>()
        for (pkg in candidates) {
            val fact = facts[pkg]
            val target = when {
                pkg in inputs.criticalSet -> TargetState.AVAILABLE                                      // R1
                fact != null && !fact.isSystem -> null                                                  // R2
                pkg in inputs.exemptSet -> null                                                         // R3
                inputs.overrides[pkg] == SystemAppOverride.Disabled ->                                  // R4
                    if (fact?.installed == true) TargetState.UNAVAILABLE else null
                inputs.overrides[pkg] == SystemAppOverride.Enabled -> TargetState.AVAILABLE             // R5
                pkg in inputs.defaultSet -> TargetState.AVAILABLE                                       // R6
                fact != null && fact.isSystem && fact.hasLauncherEntry && fact.installed ->            // R7
                    TargetState.UNAVAILABLE
                else -> null                                                                            // R8
            }
            if (target != null) result[pkg] = target
        }
        return result
    }
}
