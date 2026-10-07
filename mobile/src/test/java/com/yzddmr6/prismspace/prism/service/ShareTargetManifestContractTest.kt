package com.yzddmr6.prismspace.prism.service

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
}
