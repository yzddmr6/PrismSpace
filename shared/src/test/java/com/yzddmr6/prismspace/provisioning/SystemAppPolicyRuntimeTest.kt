package com.yzddmr6.prismspace.provisioning

import com.yzddmr6.prismspace.bridge.ApplySystemAppSelection
import com.yzddmr6.prismspace.bridge.MAX_SYSTEM_APP_SELECTION_CHANGES
import com.yzddmr6.prismspace.bridge.SelectionFinish
import com.yzddmr6.prismspace.bridge.SystemAppChoice
import com.yzddmr6.prismspace.bridge.SystemAppOverrideChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SystemAppPolicyRuntimeTest {

    private class FakePort(
        val device: MutableMap<String, PackageState>,
        /** Packages the parent user carries as system packages, i.e. enableSystemApp can install them. */
        private val enableable: Set<String>,
        /** Packages the platform refuses to suspend (HyperOS contacts). */
        private val unsuspendable: Set<String> = emptySet(),
    ) : SystemAppStatePort {
        var writes = 0
        override fun state(pkg: String) = device[pkg]
        override fun enable(pkg: String): Boolean {
            writes++
            if (pkg !in enableable) return false
            device[pkg] = (device[pkg] ?: PackageState(installed = false, hidden = false, suspended = false)).copy(installed = true)
            return true
        }
        override fun setHidden(pkg: String, hidden: Boolean): Boolean {
            writes++; device[pkg] = device.getValue(pkg).copy(hidden = hidden); return true
        }
        override fun setSuspended(pkg: String, suspended: Boolean): Boolean {
            writes++
            if (suspended && pkg in unsuspendable) return false
            device[pkg] = device.getValue(pkg).copy(suspended = suspended); return true
        }
    }

    private val launcher = setOf(CAMERA, CONTACTS, BROWSER, MMS, SETTINGS, IME)
    private val system = setOf(CAMERA, CONTACTS, BROWSER, MMS, SETTINGS, IME, PROVIDER, GMS)
    private val logs = ArrayList<String>()
    private var entryActions: Map<String, String> = emptyMap()

    private fun engine(port: FakePort, persistence: SystemAppPolicyPersistence) = SystemAppPolicyEngine(
        persistence = persistence,
        facts = { extra ->
            val known = port.device.keys + extra.filter { it in system }
            SystemAppFacts(
                facts = known.filter { it in system }.map { pkg ->
                    PackageFact(pkg, isSystem = true, hasLauncherEntry = pkg in launcher, installed = port.device[pkg]?.installed == true)
                },
                critical = setOf(SETTINGS, GMS),
                exempt = setOf(IME),
                enabledLauncherPackages = launcher,
                entryActions = entryActions,
            )
        },
        port = port,
        log = { logs += it },
        userId = 22,
    )

    private fun visible() = PackageState(installed = true, hidden = false, suspended = false)
    private fun removed() = PackageState(installed = true, hidden = true, suspended = true)

    private fun freshDevice() = mutableMapOf(
        CAMERA to visible(), CONTACTS to visible(), BROWSER to visible(), MMS to visible(),
        SETTINGS to visible(), IME to visible(), PROVIDER to visible(),
        GMS to PackageState(installed = true, hidden = false, suspended = true),
    )

    @Test fun freshConvergeHidesLauncherSystemAppsAndKeepsCriticalAvailable() {
        val port = FakePort(freshDevice(), system)
        val store = InMemorySystemAppPolicyPersistence()
        engine(port, store).converge(ConvergeReason.Provision, provisionState = 0)

        assertEquals(SelectionStatus.Pending, store.read().status)
        listOf(CAMERA, CONTACTS, BROWSER, MMS).forEach { assertEquals(it, removed(), port.device[it]) }
        assertEquals(visible(), port.device[SETTINGS])
        assertEquals(visible(), port.device[GMS])          // PR-A: critical packages are unsuspended.
        assertEquals(visible(), port.device[IME])          // Exempt.
        assertEquals(visible(), port.device[PROVIDER])     // No launcher entry: never touched.
        assertTrue(logs.any { it.startsWith("policy_inputs u=22 reason=provision status=Pending") })
        assertTrue(logs.any { it.startsWith("policy_step pkg=$CAMERA target=U from=1,0,0 steps=Hide,Suspend result=ok") })
    }

    @Test fun secondConvergeMakesNoWrites() {
        val port = FakePort(freshDevice(), system)
        val store = InMemorySystemAppPolicyPersistence()
        engine(port, store).converge(ConvergeReason.Provision, 0)
        port.writes = 0
        val report = engine(port, store).converge(ConvergeReason.Incremental, 12)
        assertEquals(0, port.writes)
        assertEquals(0, report.applied)
    }

    @Test fun confirmEnablesDefaultsAndReportsAbsentPackages() {
        val device = freshDevice()
        device.remove(CAMERA)      // Not installed for this profile (system wizard path), but present on device.
        val port = FakePort(device, system + CAMERA)
        val store = InMemorySystemAppPolicyPersistence()
        engine(port, store).converge(ConvergeReason.Provision, 0)

        val report = engine(port, store).applySelection(
            listOf(SystemAppOverrideChange(CAMERA, SystemAppChoice.Clear), SystemAppOverrideChange(CONTACTS, SystemAppChoice.Clear)),
            SelectionFinish.Confirm, provisionState = 12,
        )
        assertEquals(SelectionStatus.Confirmed, store.read().status)
        assertEquals(visible(), port.device[CAMERA])
        assertEquals(visible(), port.device[CONTACTS])
        assertEquals(visible(), port.device[BROWSER])      // Default flipped by the status change.
        assertEquals(removed(), port.device[MMS])
        assertTrue(CAMERA in report.available)
        assertTrue(BROWSER in report.available)
        // Aliases that do not exist on this device: enableSystemApp fails → absent, never "failed".
        assertTrue(report.failed.isEmpty())
        assertTrue("com.android.camera2" in report.absent)
    }

    @Test fun addedAppSurvivesUpgradeConvergenceAndKeepsUserFreeze() {
        val port = FakePort(freshDevice(), system)
        val store = InMemorySystemAppPolicyPersistence()
        engine(port, store).converge(ConvergeReason.Provision, 0)
        engine(port, store).applySelection(listOf(SystemAppOverrideChange(CAMERA, SystemAppChoice.Enabled)), null, 12)
        assertEquals(visible(), port.device[CAMERA])

        engine(port, store).converge(ConvergeReason.Incremental, 12)
        assertEquals(visible(), port.device[CAMERA])      // Upgrade does not revert the addition.

        port.device[CAMERA] = port.device.getValue(CAMERA).copy(hidden = true)     // User freezes the camera.
        engine(port, store).converge(ConvergeReason.Incremental, 12)
        assertTrue(port.device.getValue(CAMERA).hidden)    // Convergence never thaws a user freeze.
    }

    @Test fun removedDefaultIsNotReEnabledByConvergence() {
        val port = FakePort(freshDevice(), system)
        val store = InMemorySystemAppPolicyPersistence()
        engine(port, store).converge(ConvergeReason.Provision, 0)
        val report = engine(port, store).applySelection(
            listOf(SystemAppOverrideChange(BROWSER, SystemAppChoice.Disabled)), SelectionFinish.Confirm, 12)
        assertTrue(BROWSER in report.unavailable)
        assertEquals(removed(), port.device[BROWSER])
        engine(port, store).converge(ConvergeReason.Repair, 12)
        assertEquals(removed(), port.device[BROWSER])
    }

    @Test fun criticalOverridesAreIgnored() {
        val port = FakePort(freshDevice(), system)
        val store = InMemorySystemAppPolicyPersistence()
        val report = engine(port, store).applySelection(
            listOf(SystemAppOverrideChange(SETTINGS, SystemAppChoice.Disabled)), null, 0)
        assertEquals(listOf(SETTINGS), report.ignoredCritical)
        assertNull(store.read().overrides[SETTINGS])
        assertEquals(visible(), port.device[SETTINGS])
    }

    @Test fun seedingIsZeroDiffAndDeferred() {
        val device = mutableMapOf(
            CAMERA to removed(),                                                          // provisioning-removed
            CONTACTS to PackageState(installed = true, hidden = true, suspended = false),  // old contacts special case
            MMS to visible(),
            BROWSER to PackageState(installed = true, hidden = false, suspended = true),   // whole-space pause
            SETTINGS to visible(), IME to visible(),
            GMS to PackageState(installed = true, hidden = false, suspended = true),
        )
        val port = FakePort(device, system)
        val store = InMemorySystemAppPolicyPersistence()
        val before = device.toMap()
        engine(port, store).converge(ConvergeReason.Incremental, provisionState = 11)

        val state = store.read()
        assertEquals(SelectionStatus.Deferred, state.status)
        assertEquals(SystemAppOverride.Disabled, state.overrides[CAMERA])
        assertEquals(SystemAppOverride.Enabled, state.overrides[CONTACTS])
        assertEquals(SystemAppOverride.Enabled, state.overrides[MMS])
        assertEquals(SystemAppOverride.Enabled, state.overrides[BROWSER])
        assertFalse(SETTINGS in state.overrides)
        assertFalse(IME in state.overrides)
        // Only the critical invariant changed anything.
        (before.keys - GMS).forEach { assertEquals(it, before[it], device[it]) }
        assertEquals(visible(), device[GMS])
        assertEquals(1, port.writes)     // The GMS unsuspend.
        assertTrue(logs.any { it == "policy_seed u=22 enabled=3 disabled=1" })
    }

    @Test fun deferOnlyEndsAPendingPrompt() {
        val port = FakePort(freshDevice(), system)
        val store = InMemorySystemAppPolicyPersistence()
        engine(port, store).converge(ConvergeReason.Provision, 0)
        port.writes = 0
        engine(port, store).applySelection(emptyList(), SelectionFinish.Defer, 12)
        assertEquals(SelectionStatus.Deferred, store.read().status)
        assertEquals(0, port.writes)        // "Later" changes nothing besides the critical set.

        engine(port, store).applySelection(emptyList(), SelectionFinish.Confirm, 12)
        engine(port, store).applySelection(emptyList(), SelectionFinish.Defer, 12)
        assertEquals(SelectionStatus.Confirmed, store.read().status)
    }

    @Test fun selectionPageListsLauncherSystemAppsDefaultsAndCriticalFlags() {
        val port = FakePort(freshDevice(), system)
        val store = InMemorySystemAppPolicyPersistence()
        val page = engine(port, store).selectionPage(provisionState = 0, pageIndex = 0, pageSize = 500)
        assertEquals(SelectionStatus.Pending, page.status)
        val byPkg = page.entries.associateBy { it.pkg }
        assertTrue(byPkg.getValue(SETTINGS).critical)
        assertTrue(byPkg.getValue(GMS).critical)     // Critical packages are always flagged, launcher or not.
        assertTrue(byPkg.getValue(CAMERA).inDefault)
        assertFalse(byPkg.getValue(MMS).inDefault)
        assertFalse(PROVIDER in byPkg)
        assertFalse(page.hasMore)
        assertEquals(page.entries.map { it.pkg }.sorted(), page.entries.map { it.pkg })
        val first = engine(port, store).selectionPage(0, 0, 1)
        assertEquals(1, first.entries.size)
        assertTrue(first.hasMore)
    }

    @Test fun listSnapshotMarksPolicyHiddenPackages() {
        val port = FakePort(freshDevice(), system)
        val store = InMemorySystemAppPolicyPersistence()
        engine(port, store).converge(ConvergeReason.Provision, 0)
        val snapshot = engine(port, store).listSnapshot(12)
        assertTrue(CAMERA in snapshot.policyHidden)
        assertFalse(SETTINGS in snapshot.policyHidden)
        assertFalse(IME in snapshot.policyHidden)
    }

    @Test fun absentCriticalPackageIsLoggedOnlyOnce() {
        val device = freshDevice().apply { remove(GMS) }      // A critical package this device does not have.
        val port = FakePort(device, system - GMS)
        val store = InMemorySystemAppPolicyPersistence()
        engine(port, store).converge(ConvergeReason.Provision, 0)
        assertEquals(1, logs.count { it.startsWith("policy_step pkg=$GMS") && it.endsWith("result=absent") })
        logs.clear()
        engine(port, store).converge(ConvergeReason.Incremental, 12)
        assertTrue(logs.none { it.startsWith("policy_step pkg=$GMS") })
    }

    @Test fun listSnapshotCarriesActionEntries() {
        entryActions = mapOf(SETTINGS to "android.settings.SETTINGS")
        val snapshot = engine(FakePort(freshDevice(), system), InMemorySystemAppPolicyPersistence()).listSnapshot(0)
        assertEquals("android.settings.SETTINGS", snapshot.entryActions[SETTINGS])
    }

    @Test fun refusedSuspendIsAppliedAsHiddenOnlyAndNotRetried() {
        val port = FakePort(freshDevice(), system, unsuspendable = setOf(CONTACTS))
        val store = InMemorySystemAppPolicyPersistence()
        engine(port, store).converge(ConvergeReason.Provision, 0)
        assertEquals(PackageState(installed = true, hidden = true, suspended = false), port.device[CONTACTS])
        assertTrue(logs.any { it.startsWith("policy_step pkg=$CONTACTS") && it.endsWith("result=hidden_only") })
        assertTrue(logs.none { it.contains("result=failed") })
        assertEquals(SystemAppTarget.Unavailable, store.read().lastApplied[CONTACTS])

        logs.clear(); port.writes = 0
        engine(port, store).converge(ConvergeReason.Incremental, 12)
        assertEquals(0, port.writes)                          // Not retried on every pass.
        assertTrue(logs.none { it.startsWith("policy_step pkg=$CONTACTS") })

        // A user removal reports the package as unavailable, not failed.
        engine(port, store).applySelection(listOf(SystemAppOverrideChange(CONTACTS, SystemAppChoice.Enabled)), null, 12)
        val report = engine(port, store).applySelection(listOf(SystemAppOverrideChange(CONTACTS, SystemAppChoice.Disabled)), null, 12)
        assertEquals(listOf(CONTACTS), report.unavailable)
        assertTrue(report.failed.isEmpty())
    }

    @Test fun selectionChangesAreBounded() {
        ApplySystemAppSelection(List(MAX_SYSTEM_APP_SELECTION_CHANGES) { SystemAppOverrideChange("p$it", SystemAppChoice.Clear) }, null)
        try {
            ApplySystemAppSelection(List(MAX_SYSTEM_APP_SELECTION_CHANGES + 1) { SystemAppOverrideChange("p$it", SystemAppChoice.Clear) }, null)
            fail("Oversized selection must be rejected")
        } catch (expected: IllegalArgumentException) {}
    }

    private companion object {
        const val CAMERA = "com.android.camera"
        const val CONTACTS = "com.android.contacts"
        const val BROWSER = "com.android.browser"
        const val MMS = "com.android.mms"
        const val SETTINGS = "com.android.settings"
        const val IME = "com.sohu.inputmethod.sogou.xiaomi"
        const val PROVIDER = "com.android.providers.media"
        const val GMS = "com.google.android.gms"
    }
}
