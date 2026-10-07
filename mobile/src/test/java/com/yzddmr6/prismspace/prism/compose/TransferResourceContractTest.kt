package com.yzddmr6.prismspace.prism.compose

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** One transfer vocabulary in three languages; no retired "save here / save as / import" copy. */
class TransferResourceContractTest {

    private val locales = listOf("values", "values-zh", "values-zh-rTW")

    @Test fun everyTransferKeyExistsInAllLocalesWithMatchingPlaceholders() {
        val catalogs = locales.associateWith { stringsIn(file("mobile/src/main/res/$it/strings_xfer.xml")) }
        val keys = catalogs.getValue("values").keys.filter { it.startsWith("lz_xfer_") || it.startsWith("lz_files_open_gate_") }
        assertTrue("expected the full transfer catalog", keys.size >= 50)
        locales.forEach { locale ->
            val catalog = catalogs.getValue(locale)
            keys.forEach { key ->
                val value = catalog[key]
                assertTrue("$locale missing $key", !value.isNullOrBlank())
                assertEquals("$locale placeholders differ for $key", placeholders(catalogs.getValue("values").getValue(key)), placeholders(value!!))
            }
        }
    }

    @Test fun shareTargetLabelIsAResourceAndEnglishByDefault() {
        val manifest = file("mobile/src/main/AndroidManifest.xml").readText()
        val receiver = manifest.substringAfter("prism.ui.ImportToSpaceActivity").substringBefore("</activity>")
        assertTrue(receiver.contains("android:label=\"@string/lz_xfer_share_target_label\""))
        assertEquals("Send to other space", stringsIn(file("mobile/src/main/res/values/strings_xfer.xml"))["lz_xfer_share_target_label"])
        stringsIn(file("mobile/src/main/res/values/strings_xfer.xml")).forEach { (key, value) ->
            assertFalse("default $key must not contain CJK", value.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN })
        }
    }

    @Test fun retiredTransferChoicesAreGoneFromLiveCopy() {
        val retired = listOf("保存到本空间", "儲存到本空間", "另存为", "另存為", "Save as…", "Save in this space", "Import into this space", "导入到此空间", "匯入到此空間")
        locales.forEach { locale ->
            file("mobile/src/main/res/$locale").listFiles().orEmpty().filter { it.extension == "xml" }.forEach { resource ->
                stringsIn(resource).forEach { (key, value) ->
                    retired.forEach { phrase -> assertFalse("$locale/${resource.name}:$key still says \"$phrase\"", value.contains(phrase)) }
                }
            }
        }
        assertFalse(file("mobile/src/main/AndroidManifest.xml").readText().contains("导入到此空间"))
    }

    private fun placeholders(value: String) = Regex("%(\\d+\\$)?[sd]").findAll(value).map { it.value }.toSortedSet()

    private fun stringsIn(resourceFile: File): Map<String, String> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resourceFile).documentElement
        return (0 until root.childNodes.length).mapNotNull { index ->
            val element = root.childNodes.item(index) as? Element ?: return@mapNotNull null
            if (element.tagName != "string") return@mapNotNull null
            element.getAttribute("name") to element.textContent
        }.toMap()
    }

    private fun file(path: String): File = listOf(File(path), File(path.removePrefix("mobile/"))).first(File::exists)
}
