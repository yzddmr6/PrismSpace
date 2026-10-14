package com.yzddmr6.prismspace.settings.profile

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * #29: InxLocker re-addressed the session's CONFIRM_INSTALL to an InstallerX that only the main space
 * has; the start failed with ActivityNotFoundException, was only logged, and the user saw nothing.
 */
class ProfileApkInstallerHandoffTest {

    private val source = File("src/main/java/com/yzddmr6/prismspace/settings/profile/ProfileApkInstaller.kt").readText()

    private fun body(of: String, until: String) = source.substringAfter(of).substringBefore(until)

    @Test fun theConfirmationReachesTheDesignatedInstallerWhenItsRouteLeadsNowhere() {
        val pendingUserAction = body("PackageInstaller.STATUS_PENDING_USER_ACTION ->", "PackageInstaller.STATUS_SUCCESS ->")
        assertTrue(pendingUserAction.contains("showConfirmation("))
        val start = body("private fun startAddressed(", "private fun addresseeActivity(")
        // As addressed first, then the addressee's own activity in this user, explicitly.
        assertTrue(start.indexOf("c.startActivity(intent)") < start.indexOf("addresseeActivity(c, intent)"))
        assertTrue(start.contains("setComponent("))
    }

    @Test fun aConfirmationNothingCanShowEndsTheSessionWithAnExplanation() {
        val show = body("private fun showConfirmation(", "private fun startAddressed(")
        assertTrue(show.contains("abandon(c, sessionId)"))
        assertTrue(show.contains("ProfileInstallOutcome.ConfirmUnavailable("))
        val screen = File("src/main/java/com/yzddmr6/prismspace/settings/profile/PrismProfileEntryScreen.kt").readText()
        assertTrue(screen.contains("is ProfileInstallOutcome.ConfirmUnavailable ->"))
    }

    @Test fun aRepeatedTapIsAnsweredBeforeAnythingIsStagedAgain() {
        val install = body("fun install(context: Context, pkg: String, label: String) {", "createSession(")
        assertTrue(install.indexOf("resumeInFlight(") in 0 until install.indexOf("PendingSession(uris)"))
        assertTrue(body("private fun release(", "\n\n").contains("isStatusFor("))
    }

    @Test fun handOffCopyExistsInEveryLocaleWithTheInstallerPlaceholder() {
        val catalogs = mapOf(
            "values" to "values/strings_pf.xml",
            "values-zh" to "values-zh/strings_pf.xml",
            "values-zh-rTW" to "values-zh-rTW/strings_pf_transfer.xml",
        ).mapValues { (_, path) -> stringsIn(File("src/main/res/$path")) }
        catalogs.forEach { (locale, strings) ->
            listOf("lz_pf_install_confirm_direct", "lz_pf_install_confirm_unavailable_title").forEach { key ->
                assertTrue("$locale missing $key", !strings[key].isNullOrBlank())
            }
            assertEquals("$locale placeholder", 1, strings["lz_pf_install_confirm_unavailable_body"]?.split("%1\$s")?.size?.minus(1))
        }
    }

    private fun stringsIn(resourceFile: File): Map<String, String> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resourceFile).documentElement
        return (0 until root.childNodes.length).mapNotNull { index ->
            val element = root.childNodes.item(index) as? Element ?: return@mapNotNull null
            if (element.tagName != "string") return@mapNotNull null
            element.getAttribute("name") to element.textContent
        }.toMap()
    }
}
