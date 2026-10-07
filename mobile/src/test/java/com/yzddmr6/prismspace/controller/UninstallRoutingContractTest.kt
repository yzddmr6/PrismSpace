package com.yzddmr6.prismspace.controller

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the issue-#6 contract on the caller side: no user-0 uninstall intent may survive anywhere
 * in the Space UI or PrismAppControl, and the uninstall diagnostics event carries the real
 * classification plus the launch outcome.
 */
class UninstallRoutingContractTest {

    @Test fun itemCategoryReflectsRealClassification() {
        assertEquals("system", uninstallItemCategory(true))
        assertEquals("user", uninstallItemCategory(false))
    }

    @Test fun launchContentCarriesOutcomeAndReason() {
        assertEquals("submitted", uninstallLaunchContent(true, null))
        assertEquals("failed:bridge_not_ready", uninstallLaunchContent(false, "bridge_not_ready"))
        assertEquals("failed:unknown", uninstallLaunchContent(false, null))
        assertEquals("failed:unknown", uninstallLaunchContent(false, "  "))
    }

    @Test fun appControlHasNoUserZeroUninstallOrSystemRemovalPath() {
        val source = readSource("mobile/src/main/java/com/yzddmr6/prismspace/controller/PrismAppControl.kt").codeOnly()

        // System packages leave a space through the system app policy, not a disable/uninstall dialog.
        assertFalse(source.contains("fun requestRemoval"))
        assertFalse(source.contains("ACTION_UNINSTALL_PACKAGE"))
        assertFalse(source.contains("ITEM_CATEGORY, \"system\""))
    }

    @Test fun spaceScreenHasNoUninstallIntentPath() {
        val source = readSource("mobile/src/main/java/com/yzddmr6/prismspace/prism/compose/screen/SpaceScreen.kt").codeOnly()

        assertFalse(source.contains("ACTION_UNINSTALL_PACKAGE"))
        assertFalse(source.contains("EXTRA_USER"))
        assertFalse(source.contains("pendingUninstallRequest"))
    }

    @Test fun uninstallLaunchGoesThroughProfileRoutedLauncher() {
        val source = readSource("mobile/src/main/java/com/yzddmr6/prismspace/prism/service/ProfileUninstallLauncher.kt").codeOnly()

        assertTrue(source.contains("RequestAppUninstall(packageName)"))
        assertFalse(source.contains("EXTRA_USER"))
    }

    private fun readSource(path: String): String =
        listOf(File(path), File(path.removePrefix("mobile/"))).first(File::isFile).readText()

    /** Strips comment lines so assertions target code, not prose mentioning the guarded tokens. */
    private fun String.codeOnly(): String = lineSequence()
        .map { it.trim() }
        .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        .joinToString("\n")
}
