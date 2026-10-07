package com.yzddmr6.prismspace.prism.service

import com.yzddmr6.prismspace.engine.CrossProfile
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ShareTargetManifestContractTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun activity(name: String): Element {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("mobile/src/main/AndroidManifest.xml")).first(File::isFile)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val activities = factory.newDocumentBuilder().parse(manifest).getElementsByTagName("activity")
        return (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(androidNs, "name") == name }
    }

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test fun shareTargetDeclaresOnlyFileLikeTypes() {
        val receiver = activity("com.yzddmr6.prismspace.prism.ui.ImportToSpaceActivity")
        val mimeTypes = receiver.children("data").map { it.getAttributeNS(androidNs, "mimeType") }
        val actions = receiver.children("action").map { it.getAttributeNS(androidNs, "name") }

        assertEquals(
            listOf(
                "image/*", "video/*", "audio/*", "application/*", "font/*", "model/*",
                "text/csv", "text/html", "text/xml", "text/calendar", "text/vcard", "text/markdown",
            ),
            mimeTypes,
        )
        assertFalse("plain text shares must not offer the target", "text/plain" in mimeTypes)
        assertFalse("*/*" in mimeTypes)
        assertFalse("text/*" in mimeTypes)
        assertEquals(setOf("android.intent.action.SEND", "android.intent.action.SEND_MULTIPLE"), actions.toSet())
        assertEquals("@style/AppTheme.TransferSheet", receiver.getAttributeNS(androidNs, "theme"))
        assertEquals("true", receiver.getAttributeNS(androidNs, "exported"))
    }

    @Test fun transferOpenTrampolineIsNotExported() {
        val opener = activity("com.yzddmr6.prismspace.prism.transfer.TransferOpenActivity")

        assertEquals("false", opener.getAttributeNS(androidNs, "exported"))
        assertTrue(opener.children("intent-filter").isEmpty())
    }

    @Test fun receiverReadsNoBypassExtra() {
        val source = listOf(
            File("src/main/java/com/yzddmr6/prismspace/prism/ui/ImportToSpaceActivity.kt"),
            File("mobile/src/main/java/com/yzddmr6/prismspace/prism/ui/ImportToSpaceActivity.kt"),
        ).first(File::isFile).readText()

        assertFalse(source.contains("FORCE_OTHER_SPACE"))
        assertFalse(source.contains("getBooleanExtra"))
        assertTrue(source.contains("vm.startExternal("))
    }

    private fun transferSources(): Map<String, String> {
        val dir = listOf(
            File("src/main/java/com/yzddmr6/prismspace/prism/transfer"),
            File("mobile/src/main/java/com/yzddmr6/prismspace/prism/transfer"),
        ).first(File::isDirectory)
        return dir.listFiles().orEmpty().filter { it.extension == "kt" }.associate { it.name to it.readText() }
    }

    private fun manifestRoot(): Element {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("mobile/src/main/AndroidManifest.xml")).first(File::isFile)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(manifest).documentElement
    }

    /**
     * The only cross-profile entries of TransferOpenActivity are the two aliases, one per direction:
     * each carries exactly one PrismSpace profile category, so each user holds one local match.
     */
    @Test fun transferOpenAliasesAreTheOnlyCrossProfileEntries() {
        val aliases = manifestRoot().children("activity-alias")
            .filter { it.getAttributeNS(androidNs, "targetActivity") == "com.yzddmr6.prismspace.prism.transfer.TransferOpenActivity" }
            .associateBy { it.getAttributeNS(androidNs, "name") }
        assertEquals(setOf(CrossProfile.TRANSFER_OPEN_FROM_MAIN, CrossProfile.TRANSFER_OPEN_FROM_DUAL), aliases.keys)
        val expectedCategories = mapOf(
            CrossProfile.TRANSFER_OPEN_FROM_MAIN to setOf("android.intent.category.DEFAULT", CrossProfile.CATEGORY_MANAGED_PROFILE),
            CrossProfile.TRANSFER_OPEN_FROM_DUAL to setOf("android.intent.category.DEFAULT", CrossProfile.CATEGORY_PARENT_PROFILE),
        )
        aliases.forEach { (name, alias) ->
            assertEquals(name, "true", alias.getAttributeNS(androidNs, "exported"))
            val filters = alias.children("intent-filter")
            assertEquals(name, 1, filters.size)
            assertEquals(name, listOf(CrossProfile.ACTION_TRANSFER_OPEN),
                filters.single().children("action").map { it.getAttributeNS(androidNs, "name") })
            assertEquals(name, expectedCategories.getValue(name),
                filters.single().children("category").map { it.getAttributeNS(androidNs, "name") }.toSet())
            assertTrue(name, filters.single().children("data").isEmpty())
        }
        // Main space default: FromMain off (it lives in the dual space), FromDual on (provisioning turns it off there).
        assertEquals("false", aliases.getValue(CrossProfile.TRANSFER_OPEN_FROM_MAIN).getAttributeNS(androidNs, "enabled"))
        assertFalse(aliases.getValue(CrossProfile.TRANSFER_OPEN_FROM_DUAL).hasAttributeNS(androidNs, "enabled"))
    }

    /**
     * "Continue sharing in the other space" must never launch a third-party app across users: the
     * transfer package starts nothing as another user and has no platform direct start; the only
     * cross-space request is PrismSpace's own forwarded TRANSFER_OPEN.
     */
    @Test fun transferCrossSpaceStartsOnlyTargetPrismSpaceItself() {
        val sources = transferSources()
        sources.forEach { (name, text) ->
            assertFalse("$name must not start as another user", text.contains("startActivityAsUser"))
            assertFalse("$name must not use CrossProfileApps", text.contains("CrossProfileApps"))
        }
        val coordinator = sources.getValue("TransferOpenCoordinator.kt")
        assertTrue(coordinator.contains("TransferOpenActivity.forwardIntent("))
    }

    /** One route only: no platform direct start, no grant prompt, no bridge mailbox, no entry-page drain. */
    @Test fun mobileSourcesKeepTheSingleCrossSpaceRoute() {
        val root = listOf(File("src/main"), File("mobile/src/main")).first(File::isDirectory)
        val banned = listOf(
            "CrossProfileApps", "TransferOpenRequests", "ParentEntryLauncher", "QueueTransferOpen",
            "canInteractAcrossProfiles", "createRequestInteractAcrossProfilesIntent",
        )
        val offenders = root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }
            .flatMap { file -> val text = file.readText(); banned.filter { text.contains(it) }.map { "${file.name}:$it" } }
            .toList()
        assertEquals(emptyList<String>(), offenders)
        val definitions = root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }
            .filter { it.readText().contains("\"com.yzddmr6.prismspace.action.TRANSFER_OPEN\"") }.toList()
        assertEquals("ACTION_TRANSFER_OPEN is defined only in shared CrossProfile.kt", emptyList<File>(), definitions)
    }
}
