package com.yzddmr6.prismspace.prism.compose

import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.component.PrismLevel
import com.yzddmr6.prismspace.prism.compose.vm.SpaceHealth
import com.yzddmr6.prismspace.prism.compose.vm.mapHomeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the extended HomeUiModel produced by mapHomeState().
 */
class HomeStateTest {

    // Fake string resolver maps the lz_home_ resource IDs that drive the
    // tags asserted below to their Chinese values; unknown IDs fall back to "".
    private val resolve: (Int) -> String = { id ->
        when (id) {
            R.string.lz_home_status_normal_title -> "双开空间正常"
            R.string.lz_home_status_normal_body -> "双开空间运行正常，可正常使用。"
            R.string.lz_home_tag_normal -> "正常"
            R.string.lz_home_tag_notcreated -> "未创建"
            R.string.lz_home_tag_suspended -> "已暂停"
            R.string.lz_home_tag_checking -> "检查中"
            R.string.lz_home_label_checking -> "打开设置"
            R.string.lz_home_tag_needsrepair -> "需要修复"
            R.string.lz_home_label_repair -> "修复双开空间"
            else -> ""
        }
    }

    // Normal: showRepair=false, level=Ok
    @Test
    fun `Normal - showRepair false and level Ok`() {
        val model = mapHomeState(
            health = SpaceHealth.Normal,
            mainCount = 5,
            cloneCount = 2,
            capabilityText = "普通模式可用",
            versionName = "v1.2.0 (100)",
            androidText = "16 (API 36)",
            deviceText = "Xiaomi arm64-v8a",
            resolve = resolve,
        )
        assertFalse("Normal health should not show repair button", model.showRepair)
        assertEquals(PrismLevel.Ok, model.level)
        assertEquals(5, model.mainCount)
        assertEquals(2, model.cloneCount)
    }

    // NotCreated: showRepair=true, level=Error
    @Test
    fun `NotCreated - showRepair true and level Error`() {
        val model = mapHomeState(
            health = SpaceHealth.NotCreated,
            mainCount = 3,
            cloneCount = 0,
            capabilityText = "普通模式可用",
            versionName = "v1.2.0 (100)",
            androidText = "16 (API 36)",
            deviceText = "Google arm64-v8a",
            resolve = resolve,
        )
        assertTrue("NotCreated should show repair button", model.showRepair)
        assertEquals(PrismLevel.Error, model.level)
        assertEquals("未创建", model.tag)
    }

    // Suspended: showRepair=true, level=Warn
    @Test
    fun `Suspended - showRepair true and level Warn`() {
        val model = mapHomeState(
            health = SpaceHealth.Suspended,
            mainCount = 4,
            cloneCount = 1,
            capabilityText = "普通模式可用",
            versionName = "v1.2.0 (100)",
            androidText = "13 (API 33)",
            deviceText = "Samsung arm64-v8a",
            resolve = resolve,
        )
        assertTrue("Suspended should show repair button", model.showRepair)
        assertEquals(PrismLevel.Warn, model.level)
        assertEquals("已暂停", model.tag)
    }

    @Test
    fun `Checking - showRepair true and level Warn`() {
        val model = mapHomeState(
            health = SpaceHealth.Checking,
            mainCount = 4,
            cloneCount = 1,
            capabilityText = "普通模式可用",
            versionName = "v1.2.0 (100)",
            androidText = "16 (API 36)",
            deviceText = "Xiaomi arm64-v8a",
            resolve = resolve,
        )
        assertTrue("Checking should keep the settings affordance visible", model.showRepair)
        assertEquals(PrismLevel.Warn, model.level)
        assertEquals("检查中", model.tag)
        assertEquals("打开设置", model.primaryLabel)
    }

    // NeedsRepair: showRepair=true, level=Error
    @Test
    fun `NeedsRepair - showRepair true and level Error`() {
        val model = mapHomeState(
            health = SpaceHealth.NeedsRepair,
            mainCount = 0,
            cloneCount = 0,
            capabilityText = "Root 模式可用",
            versionName = "v1.2.0 (120)",
            androidText = "14 (API 34)",
            deviceText = "Pixel arm64-v8a",
            resolve = resolve,
        )
        assertTrue("NeedsRepair should show repair button", model.showRepair)
        assertEquals(PrismLevel.Error, model.level)
        assertEquals("需要修复", model.tag)
        assertEquals("修复双开空间", model.primaryLabel)
    }

    // Info fields are passed through correctly
    @Test
    fun `Info fields passed through - capabilityText, versionName, androidText, deviceText`() {
        val model = mapHomeState(
            health = SpaceHealth.Normal,
            mainCount = 10,
            cloneCount = 3,
            capabilityText = "Shizuku 模式可用",
            versionName = "v2.0.0 (200)",
            androidText = "15 (API 35)",
            deviceText = "OnePlus arm64-v8a",
            resolve = resolve,
        )
        assertEquals("Shizuku 模式可用", model.capabilityText)
        assertEquals("v2.0.0 (200)", model.versionName)
        assertEquals("15 (API 35)", model.androidText)
        assertEquals("OnePlus arm64-v8a", model.deviceText)
    }

    // Normal status title and body
    @Test
    fun `Normal - correct statusTitle and statusBody`() {
        val model = mapHomeState(
            health = SpaceHealth.Normal,
            mainCount = 1,
            cloneCount = 0,
            capabilityText = "普通模式可用",
            versionName = "v1.2.0 (100)",
            androidText = "16 (API 36)",
            deviceText = "Xiaomi arm64-v8a",
            resolve = resolve,
        )
        assertEquals("双开空间正常", model.statusTitle)
        assertTrue(model.statusBody.isNotEmpty())
        assertEquals("正常", model.tag)
    }
}
