package com.yzddmr6.prismspace.prism.compose.nav

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemAppPickerNavigationTest {

    @Test fun setupExitPopsEveryPickerBeforeShowingHome() {
        // Device evidence 9.6 issue B: navigateToTab(HOME) with the picker still on top saved it into
        // Home's state and restored it again. Popping first means nothing picker-shaped can be saved.
        assertEquals(listOf(PickerNavStep.PopAllPickers, PickerNavStep.ShowHome), planSystemAppPickerExit(SYSTEM_APP_PICKER_ORIGIN_SETUP))
    }

    @Test fun spaceExitOnlyRevealsTheSpacePage() {
        assertEquals(listOf(PickerNavStep.PopAllPickers), planSystemAppPickerExit(SYSTEM_APP_PICKER_ORIGIN_SPACE))
        assertEquals(listOf(PickerNavStep.PopAllPickers), planSystemAppPickerExit("unknown"))
    }

    @Test fun routeCarriesUserAndOrigin() {
        assertEquals("system_app_picker/23?origin=setup", PrismRoutes.systemAppPicker(23, SYSTEM_APP_PICKER_ORIGIN_SETUP))
    }

    @Test fun pickerIsNeverReusedOrCapturedByTabState() {
        val navigation = source("prism/compose/nav/SystemAppPickerNavigation.kt")
        val open = navigation.substringAfter("fun NavHostController.openSystemAppPicker").substringBefore("internal fun")
        // Issue A: singleTop matched a stale picker of another (deleted) space; always pop and push fresh.
        assertTrue(open.indexOf("popSystemAppPickers()") in 0 until open.indexOf("navigate("))
        assertFalse(open.contains("launchSingleTop"))

        val host = source("prism/compose/nav/PrismNavHost.kt")
        val tab = host.substringAfter("fun NavHostController.navigateToTab").substringBefore("\n}")
        assertTrue(tab.indexOf("popSystemAppPickers()") in 0 until tab.indexOf("navigate(route)"))
        assertFalse(host.contains("navigate(PrismRoutes.systemAppPicker"))
        assertTrue(host.contains("exitSystemAppPicker(origin)"))
    }

    private fun source(path: String): String = listOf(
        File("mobile/src/main/java/com/yzddmr6/prismspace/$path"),
        File("src/main/java/com/yzddmr6/prismspace/$path"),
    ).first(File::isFile).readText()
}
