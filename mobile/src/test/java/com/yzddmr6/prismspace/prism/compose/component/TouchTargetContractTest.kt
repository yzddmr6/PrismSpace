package com.yzddmr6.prismspace.prism.compose.component

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Touch-target contract: the 48dp floor lives in the component layer (`PrismTextButton` wrapper,
 * `PrismMinTouchTarget` token) and the in-scope screens consume it — no scattered 48.dp literals
 * or raw sub-48dp TextButtons at the reviewed call sites.
 */
class TouchTargetContractTest {

    @Test fun prismTextButtonAppliesTheMinimumTouchHeight() {
        val source = readSource("mobile/src/main/java/com/yzddmr6/prismspace/prism/compose/component/PrismTextButton.kt")

        assertTrue(source.contains("TextButton("))
        assertTrue(source.contains("defaultMinSize(minHeight = PrismMinTouchTarget)"))
        assertTrue(!source.contains("48.dp"))
    }

    @Test fun reviewedScreensHaveNoRawTextButtons() {
        // 组件层 + 本次列出的屏幕层行内文字按钮全部经 PrismTextButton；其余屏幕层随 Change 4 收敛。
        listOf(
            "mobile/src/main/java/com/yzddmr6/prismspace/prism/compose/screen/SpaceScreen.kt",
            "mobile/src/main/java/com/yzddmr6/prismspace/prism/compose/screen/FilesScreen.kt",
            "mobile/src/main/java/com/yzddmr6/prismspace/prism/compose/screen/SettingsScreen.kt",
            "mobile/src/main/java/com/yzddmr6/prismspace/setup/compose/PrismSetupScreen.kt",
            "mobile/src/main/java/com/yzddmr6/prismspace/prism/compose/component/AppActionSheet.kt",
            "mobile/src/main/java/com/yzddmr6/prismspace/prism/compose/component/TransferSheet.kt",
            "mobile/src/main/java/com/yzddmr6/prismspace/prism/compose/component/TransferHistoryList.kt",
        ).forEach { path ->
            val source = readSource(path)
            val raw = Regex("(?<!Prism)TextButton\\(").findAll(source).count()
            assertEquals("$path still has raw TextButton call sites", 0, raw)
        }
    }

    @Test fun settingsFeedbackCopyButtonUsesTheWrapper() {
        val source = readSource("mobile/src/main/java/com/yzddmr6/prismspace/prism/compose/screen/SettingsScreen.kt")

        assertTrue(source.contains("PrismTextButton(onClick = copyFeedbackAccount)"))
        assertTrue(Regex("(?<!Prism)TextButton\\(onClick = copyFeedbackAccount").find(source) == null)
    }

    @Test fun componentLayerTargetsUseTheTouchToken() {
        listOf(
            "mobile/src/main/java/com/yzddmr6/prismspace/prism/compose/component/SpaceSegmentChips.kt",
            "mobile/src/main/java/com/yzddmr6/prismspace/prism/compose/component/DeleteSpaceSheets.kt",
            "mobile/src/main/java/com/yzddmr6/prismspace/prism/compose/component/ModeGuideSheet.kt",
            "mobile/src/main/java/com/yzddmr6/prismspace/prism/compose/component/TransferSheet.kt",
            "mobile/src/main/java/com/yzddmr6/prismspace/prism/compose/component/TransferHistoryList.kt",
        ).forEach { path ->
            val source = readSource(path)
            assertTrue("$path must reference PrismMinTouchTarget", source.contains("PrismMinTouchTarget"))
            assertTrue("$path must not pin the old 44dp chip height", !source.contains("44.dp"))
        }
    }

    private fun readSource(path: String): String =
        listOf(File(path), File(path.removePrefix("mobile/"))).first(File::isFile).readText()
}
