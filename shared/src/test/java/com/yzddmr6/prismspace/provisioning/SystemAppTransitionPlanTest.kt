package com.yzddmr6.prismspace.provisioning

import com.yzddmr6.prismspace.provisioning.SystemAppStep.Enable
import com.yzddmr6.prismspace.provisioning.SystemAppStep.Hide
import com.yzddmr6.prismspace.provisioning.SystemAppStep.Suspend
import com.yzddmr6.prismspace.provisioning.SystemAppStep.Unhide
import com.yzddmr6.prismspace.provisioning.SystemAppStep.Unsuspend
import com.yzddmr6.prismspace.provisioning.TargetState.Companion.AVAILABLE
import com.yzddmr6.prismspace.provisioning.TargetState.Companion.UNAVAILABLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemAppTransitionPlanTest {

    private fun state(installed: Boolean = true, hidden: Boolean = false, suspended: Boolean = false) =
        PackageState(installed, hidden, suspended)

    /** Applies steps the way a well-behaved DPM would. */
    private fun PackageState?.after(steps: List<SystemAppStep>): PackageState? = steps.fold(this) { s, step ->
        when (step) {
            Enable -> (s ?: state(installed = false)).copy(installed = true)
            Unhide -> s!!.copy(hidden = false)
            Hide -> s!!.copy(hidden = true)
            Unsuspend -> s!!.copy(suspended = false)
            Suspend -> s!!.copy(suspended = true)
        }
    }

    // --- Migrated from PR-A planCriticalAppConvergence -------------------------------------

    @Test fun suspendedCriticalAppIsUnsuspended() {
        assertEquals(listOf(Unsuspend), stepsFor(AVAILABLE, state(hidden = false, suspended = true)))
    }

    @Test fun hiddenAndSuspendedCriticalAppIsFullyRestored() {
        assertEquals(listOf(Unhide, Unsuspend), stepsFor(AVAILABLE, state(hidden = true, suspended = true)))
    }

    @Test fun healthyCriticalAppNeedsNoRepair() {
        assertTrue(stepsFor(AVAILABLE, state()).isEmpty())
    }

    // --- Plan rules -------------------------------------------------------------------------

    @Test fun missingPackageIsEnabledFirst() {
        assertEquals(listOf(Enable), stepsFor(AVAILABLE, null))
        assertEquals(listOf(Enable, Unhide, Unsuspend), stepsFor(AVAILABLE, state(installed = false, hidden = true, suspended = true)))
    }

    @Test fun unavailableWritesBothDimensionsInOnePass() {
        assertEquals(listOf(Hide, Suspend), stepsFor(UNAVAILABLE, state()))
        assertEquals(listOf(Suspend), stepsFor(UNAVAILABLE, state(hidden = true)))
        assertEquals(listOf(Hide), stepsFor(UNAVAILABLE, state(suspended = true)))
    }

    @Test fun unavailableNeverInstallsAPackage() {
        assertTrue(stepsFor(UNAVAILABLE, null).isEmpty())
        assertTrue(stepsFor(UNAVAILABLE, state(installed = false)).isEmpty())
    }

    @Test fun planIsIdempotent() {
        val targets = mapOf("a" to AVAILABLE, "u" to UNAVAILABLE, "gms" to AVAILABLE)
        val current = mapOf("a" to state(hidden = true, suspended = true), "u" to state(), "gms" to state(suspended = true))
        val plan = planSystemAppTransitions(targets, current, emptyMap(), setOf("gms"), emptySet())
        val after = plan.associate { it.pkg to current[it.pkg].after(it.steps) }
        plan.forEach { assertTrue(it.pkg, after[it.pkg].satisfies(targets.getValue(it.pkg))) }
        val lastApplied = targets.mapValues { it.value.kind() }
        val second = planSystemAppTransitions(targets, after, lastApplied, setOf("gms"), emptySet())
        assertTrue(second.all { it.steps.isEmpty() })
    }

    @Test fun criticalPackagesConvergeEveryPass() {
        val targets = mapOf("gms" to AVAILABLE)
        val plan = planSystemAppTransitions(targets, mapOf("gms" to state(suspended = true)),
            mapOf("gms" to SystemAppTarget.Available), setOf("gms"), emptySet())
        assertEquals(listOf(Unsuspend), plan.single().steps)
    }

    @Test fun userFrozenPackageIsSkippedWhenTargetUnchanged() {
        val targets = mapOf("camera" to AVAILABLE)
        val lastApplied = mapOf("camera" to SystemAppTarget.Available)
        assertTrue(packagesToConverge(targets, lastApplied, emptySet(), emptySet()).isEmpty())
        assertTrue(planSystemAppTransitions(targets, mapOf("camera" to state(hidden = true)), lastApplied, emptySet(), emptySet()).isEmpty())
    }

    @Test fun forceOverridesTheUnchangedSkip() {
        val targets = mapOf("camera" to AVAILABLE)
        val plan = planSystemAppTransitions(targets, mapOf("camera" to state(hidden = true)),
            mapOf("camera" to SystemAppTarget.Available), emptySet(), setOf("camera"))
        assertEquals(listOf(Unhide), plan.single().steps)
    }

    @Test fun changedTargetIsWritten() {
        val targets = mapOf("browser" to UNAVAILABLE)
        val plan = planSystemAppTransitions(targets, mapOf("browser" to state()),
            mapOf("browser" to SystemAppTarget.Available), emptySet(), emptySet())
        assertEquals(listOf(Hide, Suspend), plan.single().steps)
    }

    @Test fun seedingOnlyForCompletedSpacesWithoutStoredStatus() {
        assertFalse(needsPolicySeeding(0, null))
        assertFalse(needsPolicySeeding(2, null))
        assertTrue(needsPolicySeeding(3, null))
        assertTrue(needsPolicySeeding(11, null))
        SelectionStatus.values().forEach { assertFalse(needsPolicySeeding(11, it)) }
    }

    @Test fun satisfiesMatchesTargets() {
        assertTrue(state().satisfies(AVAILABLE))
        assertFalse(state(hidden = true).satisfies(AVAILABLE))
        assertFalse(null.satisfies(AVAILABLE))
        assertTrue(state(hidden = true, suspended = true).satisfies(UNAVAILABLE))
        assertFalse(state(hidden = true).satisfies(UNAVAILABLE))
        assertTrue(state(installed = false).satisfies(UNAVAILABLE))
    }
}
