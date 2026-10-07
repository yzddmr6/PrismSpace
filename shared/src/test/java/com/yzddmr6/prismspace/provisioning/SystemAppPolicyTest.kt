package com.yzddmr6.prismspace.provisioning

import com.yzddmr6.prismspace.provisioning.SystemAppOverride.Disabled
import com.yzddmr6.prismspace.provisioning.SystemAppOverride.Enabled
import com.yzddmr6.prismspace.provisioning.TargetState.Companion.AVAILABLE
import com.yzddmr6.prismspace.provisioning.TargetState.Companion.UNAVAILABLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemAppPolicyTest {

    private data class Case(
        val name: String,
        val inputs: PolicyInputs,
        val pkg: String,
        val expected: TargetState?,
    )

    private fun fact(pkg: String, system: Boolean = true, launcher: Boolean = true, installed: Boolean = true) =
        PackageFact(pkg, system, launcher, installed)

    private fun inputs(
        defaults: Set<String> = emptySet(),
        critical: Set<String> = emptySet(),
        exempt: Set<String> = emptySet(),
        overrides: Map<String, SystemAppOverride> = emptyMap(),
        facts: List<PackageFact> = emptyList(),
    ) = PolicyInputs(defaults, critical, exempt, overrides, facts)

    @Test fun rulesByPriority() {
        val cases = listOf(
            Case("critical wins over a Disabled override",
                inputs(critical = setOf("s"), overrides = mapOf("s" to Disabled), facts = listOf(fact("s"))), "s", AVAILABLE),
            Case("critical without fact is still available",
                inputs(critical = setOf("gms")), "gms", AVAILABLE),
            Case("non-system fact is untouched even when overridden",
                inputs(overrides = mapOf("u" to Enabled), facts = listOf(fact("u", system = false))), "u", null),
            Case("non-system fact is untouched even in the default set",
                inputs(defaults = setOf("com.android.chrome"), facts = listOf(fact("com.android.chrome", system = false))),
                "com.android.chrome", null),
            Case("system IME is exempt",
                inputs(exempt = setOf("ime"), facts = listOf(fact("ime"))), "ime", null),
            Case("system IME is exempt even when overridden",
                inputs(exempt = setOf("ime"), overrides = mapOf("ime" to Disabled), facts = listOf(fact("ime"))), "ime", null),
            Case("Disabled override on installed package",
                inputs(overrides = mapOf("b" to Disabled), facts = listOf(fact("b"))), "b", UNAVAILABLE),
            Case("Disabled override on uninstalled package is untouched",
                inputs(overrides = mapOf("b" to Disabled), facts = listOf(fact("b", installed = false))), "b", null),
            Case("Disabled override without fact is untouched",
                inputs(overrides = mapOf("b" to Disabled)), "b", null),
            Case("Disabled override beats the default set",
                inputs(defaults = setOf("b"), overrides = mapOf("b" to Disabled), facts = listOf(fact("b"))), "b", UNAVAILABLE),
            Case("Enabled override",
                inputs(overrides = mapOf("c" to Enabled), facts = listOf(fact("c"))), "c", AVAILABLE),
            Case("Enabled override on uninstalled package installs it",
                inputs(overrides = mapOf("c" to Enabled), facts = listOf(fact("c", installed = false))), "c", AVAILABLE),
            Case("default package without fact is available (presence decided by the applier)",
                inputs(defaults = setOf("com.android.camera2")), "com.android.camera2", AVAILABLE),
            Case("no launcher and in no set is untouched",
                inputs(facts = listOf(fact("provider", launcher = false))), "provider", null),
            Case("other launcher system package is unavailable",
                inputs(facts = listOf(fact("market"))), "market", UNAVAILABLE),
            Case("uninstalled launcher system package is untouched",
                inputs(facts = listOf(fact("market", installed = false))), "market", null),
        )
        cases.forEach { case ->
            assertEquals(case.name, case.expected, SystemAppPolicy.evaluate(case.inputs)[case.pkg])
            if (case.expected == null) assertFalse(case.name, case.pkg in SystemAppPolicy.evaluate(case.inputs))
        }
    }

    @Test fun defaultSetIsGatedBySelectionStatus() {
        assertTrue(effectiveDefaultSet(null).isEmpty())
        assertTrue(effectiveDefaultSet(SelectionStatus.Pending).isEmpty())
        assertTrue(effectiveDefaultSet(SelectionStatus.Deferred).isEmpty())
        assertEquals(SystemAppDefaults.packages, effectiveDefaultSet(SelectionStatus.Confirmed))
    }

    @Test fun confirmingFlipsHyperOsCameraContactsAndBrowser() {
        // HyperOS test device: these three are installed, launcher-capable system packages that old
        // provisioning hid. Pending/Deferred keep them unavailable (R7); Confirmed enables them (R6).
        val facts = listOf("com.android.camera", "com.android.contacts", "com.android.browser").map { fact(it) }
        for (status in listOf(SelectionStatus.Pending, SelectionStatus.Deferred)) {
            val targets = SystemAppPolicy.evaluate(inputs(defaults = effectiveDefaultSet(status), facts = facts))
            facts.forEach { assertEquals("$status ${it.pkg}", UNAVAILABLE, targets[it.pkg]) }
        }
        val confirmed = SystemAppPolicy.evaluate(inputs(defaults = effectiveDefaultSet(SelectionStatus.Confirmed), facts = facts))
        facts.forEach { assertEquals(it.pkg, AVAILABLE, confirmed[it.pkg]) }
    }

    @Test fun defaultsCoverFileManagersThatAreAlsoCritical() {
        val critical = setOf("com.android.fileexplorer")
        val targets = SystemAppPolicy.evaluate(inputs(
            defaults = effectiveDefaultSet(SelectionStatus.Pending),
            critical = critical,
            facts = listOf(fact("com.android.fileexplorer")),
        ))
        assertEquals(AVAILABLE, targets["com.android.fileexplorer"])
    }

    @Test fun targetKinds() {
        assertEquals(SystemAppTarget.Available, AVAILABLE.kind())
        assertEquals(SystemAppTarget.Unavailable, UNAVAILABLE.kind())
    }
}
