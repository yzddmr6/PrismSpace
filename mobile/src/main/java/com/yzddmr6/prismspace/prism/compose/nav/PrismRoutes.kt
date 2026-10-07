package com.yzddmr6.prismspace.prism.compose.nav

object PrismRoutes {
    const val HOME     = "home"
    const val SPACE    = "space"
    const val FILES    = "files"
    const val SETTINGS = "settings"
    /** Not a top-level tab, so the bottom bar hides while it is shown. */
    const val SYSTEM_APP_PICKER = "system_app_picker/{userId}?origin={origin}"

    fun systemAppPicker(userId: Int, origin: String) = "system_app_picker/$userId?origin=$origin"

    val topLevel = listOf(HOME, SPACE, FILES, SETTINGS)
}

/** Picker opened right after a space was created; finishing it lands on Home. */
const val SYSTEM_APP_PICKER_ORIGIN_SETUP = "setup"
/** Picker opened from the Space screen's view panel; finishing it returns there. */
const val SYSTEM_APP_PICKER_ORIGIN_SPACE = "space"
