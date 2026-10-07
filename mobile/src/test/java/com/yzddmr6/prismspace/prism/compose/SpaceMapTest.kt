package com.yzddmr6.prismspace.prism.compose

import com.yzddmr6.prismspace.prism.compose.vm.AppLaunchability
import com.yzddmr6.prismspace.prism.compose.vm.DualFreezeAction
import com.yzddmr6.prismspace.prism.compose.vm.SpaceAppInput
import com.yzddmr6.prismspace.prism.compose.vm.dualFreezeAction
import com.yzddmr6.prismspace.prism.compose.vm.SpaceRowAction
import com.yzddmr6.prismspace.prism.compose.vm.SpaceSegment
import com.yzddmr6.prismspace.prism.compose.vm.SystemMembershipAction
import com.yzddmr6.prismspace.prism.compose.vm.systemMembershipAction
import com.yzddmr6.prismspace.prism.compose.vm.mapRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests mapRows(), the pure mapper that drives Space screen cards.
 */
class SpaceMapTest {

    private fun dualInput(
        pkg: String = "com.example.app",
        label: String = "Test App",
        frozen: Boolean = false,
        suspended: Boolean = false,
        launchable: Boolean = true,
        system: Boolean = false,
        cloned: Boolean = false,
        prepared: Boolean = false,
        critical: Boolean = false,
        launchability: AppLaunchability? = null,
        policyHidden: Boolean = false,
    ) = SpaceAppInput(
        pkg = pkg, label = label, frozen = frozen, suspended = suspended,
        launchable = launchable, system = system, cloned = cloned, prepared = prepared,
        segment = SpaceSegment.Dual,
        critical = critical,
        launchability = launchability,
        policyHidden = policyHidden,
    )

    private fun mainInput(
        pkg: String = "com.example.main",
        label: String = "Main App",
        frozen: Boolean = false,
        suspended: Boolean = false,
        launchable: Boolean = true,
        system: Boolean = false,
        cloned: Boolean = false,
        prepared: Boolean = false,
    ) = SpaceAppInput(
        pkg = pkg, label = label, frozen = frozen, suspended = suspended,
        launchable = launchable, system = system, cloned = cloned, prepared = prepared,
        segment = SpaceSegment.Main,
    )

    // --- Dual segment ---

    @Test
    fun `dual frozen app gets chipText 已暂停 and Resume action`() {
        val rows = mapRows(listOf(dualInput(frozen = true)))
        assertEquals(1, rows.size)
        assertEquals("已暂停", rows[0].chipText)
        assertFalse(rows[0].chipOk)
        assertEquals(SpaceRowAction.Resume, rows[0].primaryAction)
    }

    @Test
    fun `dual suspended app reads paused and offers Resume`() {
        val row = mapRows(listOf(dualInput(suspended = true))).single()
        assertEquals("已暂停", row.chipText)
        assertEquals(SpaceRowAction.Resume, row.primaryAction)
    }

    @Test
    fun `dual healthy app carries no tag and offers Open`() {
        // 健康行不贴「运行中」类标签；行内主动作 = 打开。
        val rows = mapRows(listOf(dualInput(frozen = false)))
        assertEquals(1, rows.size)
        assertEquals(null, rows[0].chipText)
        assertEquals(SpaceRowAction.Open, rows[0].primaryAction)
    }

    @Test
    fun `dual healthy system app offers Open like any other app`() {
        // Launchability never keys on "is system": Settings in the dual space gets an Open button.
        val row = mapRows(listOf(dualInput(pkg = "com.android.settings", system = true,
            launchability = AppLaunchability.Launchable))).single()
        assertEquals("系统应用", row.chipText)
        assertEquals(SpaceRowAction.Open, row.primaryAction)
        assertTrue(row.launchable)
    }

    @Test
    fun `dual paused system app reads paused and offers Resume`() {
        val row = mapRows(listOf(dualInput(system = true, frozen = true, launchability = AppLaunchability.Paused))).single()
        assertEquals("已暂停", row.chipText)
        assertEquals(SpaceRowAction.Resume, row.primaryAction)
    }

    @Test
    fun `dual package without launcher entry reads 无界面 and has no action`() {
        val row = mapRows(listOf(dualInput(pkg = "com.google.ar.core", launchable = false,
            launchability = AppLaunchability.NoLauncherEntry))).single()
        assertEquals("无界面", row.chipText)
        assertEquals(null, row.primaryAction)
        assertFalse(row.launchable)
        val legacy = mapRows(listOf(dualInput(launchable = false))).single()
        assertEquals("无界面", legacy.chipText)
        assertEquals(null, legacy.primaryAction)
    }

    @Test
    fun `dual policy-hidden system app reads 未添加 and offers 添加`() {
        val row = mapRows(listOf(dualInput(pkg = "com.android.camera", system = true, frozen = true, suspended = true,
            launchability = AppLaunchability.Paused, policyHidden = true))).single()
        assertEquals("未添加", row.chipText)
        assertEquals(SpaceRowAction.AddSystemApp, row.primaryAction)
        assertFalse(row.launchable)
        assertEquals(SystemMembershipAction.Add, systemMembershipAction(row))
    }

    @Test
    fun `paused outranks no-ui and system tags`() {
        val row = mapRows(listOf(dualInput(system = true, suspended = true, launchability = AppLaunchability.NoLauncherEntry))).single()
        assertEquals("已暂停", row.chipText)
        assertEquals(null, row.primaryAction)
    }

    @Test
    fun `system membership actions follow criticality and launcher entry`() {
        fun action(critical: Boolean, launchability: AppLaunchability, system: Boolean = true) =
            systemMembershipAction(mapRows(listOf(dualInput(system = system, critical = critical, launchability = launchability))).single())
        assertEquals(SystemMembershipAction.Remove, action(critical = false, launchability = AppLaunchability.Launchable))
        assertEquals(SystemMembershipAction.None, action(critical = true, launchability = AppLaunchability.Launchable))
        assertEquals(SystemMembershipAction.None, action(critical = false, launchability = AppLaunchability.NoLauncherEntry))
        assertEquals(SystemMembershipAction.None, action(critical = false, launchability = AppLaunchability.Launchable, system = false))
    }

    @Test
    fun `critical package classification reaches the row model`() {
        assertTrue(mapRows(listOf(dualInput(system = true, critical = true))).single().critical)
    }

    @Test
    fun `critical package offers no freeze action`() {
        val row = mapRows(listOf(dualInput(system = true, critical = true))).single()
        assertEquals(DualFreezeAction.KeptAvailable, dualFreezeAction(row))
    }

    @Test
    fun `paused critical package left by an older version can still be unfrozen`() {
        assertEquals(DualFreezeAction.Unfreeze,
            dualFreezeAction(mapRows(listOf(dualInput(system = true, critical = true, suspended = true))).single()))
        assertEquals(DualFreezeAction.Unfreeze,
            dualFreezeAction(mapRows(listOf(dualInput(system = true, critical = true, frozen = true))).single()))
    }

    @Test
    fun `non-critical system package keeps freeze action`() {
        assertEquals(DualFreezeAction.Freeze, dualFreezeAction(mapRows(listOf(dualInput(system = true))).single()))
    }

    @Test
    fun `dual frozen takes priority regardless of launchable`() {
        val rows = mapRows(listOf(dualInput(frozen = true, launchable = false)))
        assertEquals("已暂停", rows[0].chipText)
        assertEquals(SpaceRowAction.Resume, rows[0].primaryAction)
    }

    @Test
    fun `dual segment field preserved`() {
        val rows = mapRows(listOf(dualInput()))
        assertEquals(SpaceSegment.Dual, rows[0].segment)
    }

    // --- Main segment ---

    @Test
    fun `main cloned app carries no tag and no action`() {
        // 已双开是健康态，不贴标签；行内无下一步动作。
        val rows = mapRows(listOf(mainInput(cloned = true)))
        assertEquals(null, rows[0].chipText)
        assertEquals(null, rows[0].primaryAction)
    }

    @Test
    fun `main non-cloned app offers 添加分身 without a tag`() {
        val rows = mapRows(listOf(mainInput(cloned = false)))
        assertEquals(null, rows[0].chipText)
        assertEquals(SpaceRowAction.AddClone, rows[0].primaryAction)
    }

    @Test
    fun `main prepared app is pending with 去安装 action`() {
        val row = mapRows(listOf(mainInput(cloned = false, prepared = true))).single()
        assertEquals("待安装", row.chipText)
        assertFalse(row.chipOk)
        assertTrue(row.prepared)
        assertEquals(SpaceRowAction.ContinueInstall, row.primaryAction)
    }

    @Test
    fun `main segment field preserved`() {
        val rows = mapRows(listOf(mainInput()))
        assertEquals(SpaceSegment.Main, rows[0].segment)
    }

    // --- Field pass-through ---

    @Test
    fun `pkg and label pass through`() {
        val rows = mapRows(listOf(dualInput(pkg = "com.foo.bar", label = "Foo Bar")))
        assertEquals("com.foo.bar", rows[0].pkg)
        assertEquals("Foo Bar", rows[0].label)
    }

    @Test
    fun `frozen field passes through`() {
        val rows = mapRows(listOf(dualInput(frozen = true)))
        assertTrue(rows[0].frozen)
    }

    @Test
    fun `cloned field passes through`() {
        val rows = mapRows(listOf(mainInput(cloned = true)))
        assertTrue(rows[0].cloned)
    }

    @Test
    fun `empty list returns empty`() {
        val rows = mapRows(emptyList())
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `multiple apps mapped independently`() {
        val inputs = listOf(
            dualInput(pkg = "com.a", frozen = true),
            dualInput(pkg = "com.b", frozen = false),
            mainInput(pkg = "com.c", cloned = true),
        )
        val rows = mapRows(inputs)
        assertEquals(3, rows.size)
        assertEquals("已暂停", rows[0].chipText)
        assertEquals(null, rows[1].chipText)   // healthy dual row carries no tag
        assertEquals(null, rows[2].chipText)   // cloned main row carries no tag
        assertEquals(SpaceRowAction.Resume, rows[0].primaryAction)
        assertEquals(SpaceRowAction.Open, rows[1].primaryAction)
        assertEquals(null, rows[2].primaryAction)
    }
}
