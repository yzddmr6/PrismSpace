package com.yzddmr6.prismspace.prism.compose.nav

import androidx.navigation.NavHostController

/** One step of leaving the system-app selection page. */
enum class PickerNavStep { PopAllPickers, ShowHome }

/**
 * Leaving the picker always removes every picker entry first, so a tab switch can never save it
 * into a tab's back stack (and restore it later on top of Home, or reuse it for another space).
 * After creation the page hands over to Home; from the Space page it just reveals that page.
 */
fun planSystemAppPickerExit(origin: String): List<PickerNavStep> =
    if (origin == SYSTEM_APP_PICKER_ORIGIN_SETUP) listOf(PickerNavStep.PopAllPickers, PickerNavStep.ShowHome)
    else listOf(PickerNavStep.PopAllPickers)

/** Pops every picker destination (inclusive); it never belongs to a tab's saved state. */
internal fun NavHostController.popSystemAppPickers() {
    repeat(MAX_PICKER_POPS) {
        if (!popBackStack(PrismRoutes.SYSTEM_APP_PICKER, inclusive = true)) return
    }
}

/** Always a fresh destination bound to [userId]/[origin]: no singleTop reuse of another space's page. */
internal fun NavHostController.openSystemAppPicker(userId: Int, origin: String) {
    popSystemAppPickers()
    navigate(PrismRoutes.systemAppPicker(userId, origin))
}

internal fun NavHostController.exitSystemAppPicker(origin: String) {
    planSystemAppPickerExit(origin).forEach { step ->
        when (step) {
            PickerNavStep.PopAllPickers -> popSystemAppPickers()
            PickerNavStep.ShowHome -> navigateToTab(PrismRoutes.HOME)
        }
    }
}

private const val MAX_PICKER_POPS = 8
