package com.yzddmr6.prismspace.prism.compose

import com.yzddmr6.prismspace.prism.compose.vm.AppLaunchability
import com.yzddmr6.prismspace.prism.compose.vm.isDualNormalRow
import com.yzddmr6.prismspace.prism.compose.vm.mergeAllSystemRows
import com.yzddmr6.prismspace.prism.compose.vm.CloneFilter
import com.yzddmr6.prismspace.prism.compose.vm.SortOrder
import com.yzddmr6.prismspace.prism.compose.vm.SpaceAppInput
import com.yzddmr6.prismspace.prism.compose.vm.SpaceRow
import com.yzddmr6.prismspace.prism.compose.vm.SpaceSegment
import com.yzddmr6.prismspace.prism.compose.vm.applyListTransform
import com.yzddmr6.prismspace.prism.compose.vm.filterSystemAppRows
import com.yzddmr6.prismspace.prism.compose.vm.mapRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests client-side filter, sort and search logic in applyListTransform().
 */
class SpaceFilterSortTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun makeRow(
        pkg: String,
        label: String,
        system: Boolean = false,
        cloned: Boolean = false,
        segment: SpaceSegment = SpaceSegment.Main,
        loadIndex: Int = 0,
    ): SpaceRow {
        val input = SpaceAppInput(
            pkg = pkg,
            label = label,
            frozen = false,
            suspended = false,
            launchable = true,
            system = system,
            cloned = cloned,
            segment = segment,
        )
        return mapRows(listOf(input))[0].copy()
    }

    private val wechat = makeRow("com.tencent.mm", "微信", loadIndex = 0)
    private val twitter = makeRow("com.twitter.android", "X", cloned = true, loadIndex = 1)
    private val camera = makeRow("com.android.camera", "相机", system = true, loadIndex = 2)
    private val alipay = makeRow("com.eg.android.AlipayGphone", "支付宝", loadIndex = 3)
    private val telegram = makeRow("org.telegram.messenger", "Telegram", cloned = true, loadIndex = 4)

    private val mainRows = listOf(wechat, twitter, camera, alipay, telegram)

    private val dualX = makeRow("com.twitter.android", "X", segment = SpaceSegment.Dual, loadIndex = 0)
    private val dualTelegram = makeRow("org.telegram.messenger", "Telegram", segment = SpaceSegment.Dual, loadIndex = 1)
    private val dualRows = listOf(dualX, dualTelegram)

    @Test
    fun `system app search is empty until the user enters a query`() {
        assertTrue(filterSystemAppRows(listOf(camera), "  ").isEmpty())
    }

    @Test
    fun `system app search matches package and excludes non-system rows`() {
        val nonSystem = makeRow("com.android.fake", "Fake", system = false)
        val result = filterSystemAppRows(listOf(camera, nonSystem), "com.android")

        assertEquals(listOf(camera), result)
    }

    // -----------------------------------------------------------------------
    // Search / keyword matching
    // -----------------------------------------------------------------------

    @Test
    fun `keyword match on label case-insensitive`() {
        val result = applyListTransform(
            rows = mainRows,
            segment = SpaceSegment.Main,
            query = "wechat",
            sort = SortOrder.Name,
            cloneFilter = CloneFilter.All,
            showSystem = true,
        )
        // "wechat" matches neither the Chinese label nor the package name.
        assertTrue(result.isEmpty()) // "wechat" matches neither label nor pkg
    }

    @Test
    fun `keyword match on label`() {
        val result = applyListTransform(
            rows = mainRows,
            segment = SpaceSegment.Main,
            query = "Telegram",
            sort = SortOrder.Name,
            cloneFilter = CloneFilter.All,
            showSystem = true,
        )
        assertEquals(1, result.size)
        assertEquals("org.telegram.messenger", result[0].pkg)
    }

    @Test
    fun `keyword match is case-insensitive`() {
        val result = applyListTransform(
            rows = mainRows,
            segment = SpaceSegment.Main,
            query = "telegram",
            sort = SortOrder.Name,
            cloneFilter = CloneFilter.All,
            showSystem = true,
        )
        assertEquals(1, result.size)
        assertEquals("org.telegram.messenger", result[0].pkg)
    }

    @Test
    fun `keyword match on packageName`() {
        val result = applyListTransform(
            rows = mainRows,
            segment = SpaceSegment.Main,
            query = "tencent",
            sort = SortOrder.Name,
            cloneFilter = CloneFilter.All,
            showSystem = true,
        )
        assertEquals(1, result.size)
        assertEquals("com.tencent.mm", result[0].pkg)
    }

    @Test
    fun `empty query returns all rows`() {
        val result = applyListTransform(
            rows = mainRows,
            segment = SpaceSegment.Main,
            query = "",
            sort = SortOrder.Name,
            cloneFilter = CloneFilter.All,
            showSystem = true,
        )
        assertEquals(mainRows.size, result.size)
    }

    // -----------------------------------------------------------------------
    // Filter: hide system apps
    // -----------------------------------------------------------------------

    @Test
    fun `hide system apps when showSystem=false`() {
        val result = applyListTransform(
            rows = mainRows,
            segment = SpaceSegment.Main,
            query = "",
            sort = SortOrder.Name,
            cloneFilter = CloneFilter.All,
            showSystem = false,
        )
        assertTrue(result.none { it.system })
        assertTrue(result.none { it.pkg == "com.android.camera" })
    }

    @Test
    fun `show system apps when showSystem=true`() {
        val result = applyListTransform(
            rows = mainRows,
            segment = SpaceSegment.Main,
            query = "",
            sort = SortOrder.Name,
            cloneFilter = CloneFilter.All,
            showSystem = true,
        )
        assertTrue(result.any { it.pkg == "com.android.camera" })
    }

    @Test
    fun `dual segment keeps policy-enabled system rows regardless of the toggle`() {
        // Dual: system apps the policy keeps (e.g. Settings) are normal rows; the 显示全部系统应用
        // toggle merges in the rest upstream (mergeAllSystemRows), so this transform never drops them.
        val dualWithSystem = dualRows + makeRow("com.android.settings", "设置", system = true, segment = SpaceSegment.Dual)
        for (toggle in listOf(false, true)) {
            val result = applyListTransform(
                rows = dualWithSystem,
                segment = SpaceSegment.Dual,
                query = "",
                sort = SortOrder.Name,
                cloneFilter = CloneFilter.All,
                showSystem = toggle,
            )
            assertEquals(dualWithSystem.size, result.size)
            assertTrue(result.any { it.pkg == "com.android.settings" })
        }
    }

    @Test
    fun `dual show-all merges every installed system package once`() {
        val normal = listOf(input("com.user"), input("com.android.settings", system = true))
        val system = listOf(input("com.android.settings", system = true), input("com.miuix.editor", system = true),
            input("com.android.camera", system = true, policyHidden = true))
        val merged = mergeAllSystemRows(normal, system)
        assertEquals(listOf("com.user", "com.android.settings", "com.miuix.editor", "com.android.camera"), merged.map { it.pkg })
        assertEquals(normal, mergeAllSystemRows(normal, emptyList()))
    }

    @Test
    fun `dual normal rows are user apps plus kept system apps with a launcher entry`() {
        assertTrue(isDualNormalRow(system = false, shownAsEnabled = true, policyHidden = false, launchability = AppLaunchability.NoLauncherEntry))
        assertTrue(isDualNormalRow(system = true, shownAsEnabled = true, policyHidden = false, launchability = AppLaunchability.Launchable))
        assertTrue(isDualNormalRow(system = true, shownAsEnabled = true, policyHidden = false, launchability = AppLaunchability.Paused))
        assertFalse(isDualNormalRow(system = true, shownAsEnabled = false, policyHidden = true, launchability = AppLaunchability.Paused))
        assertFalse(isDualNormalRow(system = true, shownAsEnabled = true, policyHidden = false, launchability = AppLaunchability.NoLauncherEntry))
        assertFalse(isDualNormalRow(system = false, shownAsEnabled = false, policyHidden = false, launchability = AppLaunchability.Launchable))
    }

    private fun input(pkg: String, system: Boolean = false, policyHidden: Boolean = false) = SpaceAppInput(
        pkg = pkg, label = pkg, frozen = policyHidden, suspended = policyHidden, launchable = true, system = system,
        cloned = false, segment = SpaceSegment.Dual, policyHidden = policyHidden,
    )

    // -----------------------------------------------------------------------
    // Filter: cloneFilter (main segment only)
    // -----------------------------------------------------------------------

    @Test
    fun `filter only-cloned returns only cloned apps`() {
        val result = applyListTransform(
            rows = mainRows,
            segment = SpaceSegment.Main,
            query = "",
            sort = SortOrder.Name,
            cloneFilter = CloneFilter.Yes,
            showSystem = true,
        )
        assertTrue(result.all { it.cloned })
        assertEquals(2, result.size) // twitter + telegram are cloned
    }

    @Test
    fun `filter only-not-cloned returns only non-cloned apps`() {
        val result = applyListTransform(
            rows = mainRows,
            segment = SpaceSegment.Main,
            query = "",
            sort = SortOrder.Name,
            cloneFilter = CloneFilter.No,
            showSystem = true,
        )
        assertTrue(result.none { it.cloned })
    }

    @Test
    fun `filter all returns all apps`() {
        val result = applyListTransform(
            rows = mainRows,
            segment = SpaceSegment.Main,
            query = "",
            sort = SortOrder.Name,
            cloneFilter = CloneFilter.All,
            showSystem = true,
        )
        assertEquals(mainRows.size, result.size)
    }

    @Test
    fun `clone filter not applied for dual segment`() {
        val result = applyListTransform(
            rows = dualRows,
            segment = SpaceSegment.Dual,
            query = "",
            sort = SortOrder.Name,
            cloneFilter = CloneFilter.Yes, // should be ignored for dual
            showSystem = true,
        )
        assertEquals(dualRows.size, result.size)
    }

    // -----------------------------------------------------------------------
    // Sort: name
    // -----------------------------------------------------------------------

    @Test
    fun `name sort orders labels alphabetically`() {
        val rows = listOf(
            makeRow("com.b", "Banana"),
            makeRow("com.a", "Apple"),
            makeRow("com.c", "Cherry"),
        )
        val result = applyListTransform(
            rows = rows,
            segment = SpaceSegment.Main,
            query = "",
            sort = SortOrder.Name,
            cloneFilter = CloneFilter.All,
            showSystem = true,
        )
        assertEquals("Apple", result[0].label)
        assertEquals("Banana", result[1].label)
        assertEquals("Cherry", result[2].label)
    }

    // -----------------------------------------------------------------------
    // Sort: cloned (已添加优先; main segment only)
    // -----------------------------------------------------------------------

    @Test
    fun `cloned sort puts cloned apps first in main segment`() {
        val result = applyListTransform(
            rows = mainRows,
            segment = SpaceSegment.Main,
            query = "",
            sort = SortOrder.Cloned,
            cloneFilter = CloneFilter.All,
            showSystem = true,
        )
        val clonedCount = result.takeWhile { it.cloned }.size
        val nonClonedStarted = result.dropWhile { it.cloned }.any { it.cloned }
        assertTrue(clonedCount >= 2)
        assertTrue(!nonClonedStarted)
    }

    @Test
    fun `cloned sort falls back to name sort in dual segment`() {
        val rows = listOf(
            makeRow("com.b", "Banana", segment = SpaceSegment.Dual),
            makeRow("com.a", "Apple", segment = SpaceSegment.Dual),
        )
        val result = applyListTransform(
            rows = rows,
            segment = SpaceSegment.Dual,
            query = "",
            sort = SortOrder.Cloned, // not applicable for dual, should fall back to name
            cloneFilter = CloneFilter.All,
            showSystem = true,
        )
        assertEquals("Apple", result[0].label)
    }

    // -----------------------------------------------------------------------
    // Segment-appropriateness: filters & cloned-sort only apply to main
    // -----------------------------------------------------------------------

    @Test
    fun `dual segment ignores cloneFilter and the main-only system filter`() {
        val rowsWithSystem = dualRows + makeRow("com.sys", "Sys", system = true, segment = SpaceSegment.Dual)
        val result = applyListTransform(
            rows = rowsWithSystem,
            segment = SpaceSegment.Dual,
            query = "",
            sort = SortOrder.Name,
            cloneFilter = CloneFilter.Yes,   // cloneFilter stays main-only → ignored for dual
            showSystem = false,              // dual system rows are chosen upstream, not filtered here
        )
        assertEquals(rowsWithSystem.size, result.size)
    }
}
