package com.yzddmr6.prismspace.util

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class PrismLocaleContractTest {

    @Test
    fun `traditional chinese deliberately falls back to simplified chinese`() {
        assertEquals(listOf("zh-TW", "zh-CN"), PrismLocale.localeTagsFor(PrismLocale.ZH_TW))
        assertEquals(listOf("zh-CN"), PrismLocale.localeTagsFor(PrismLocale.ZH))
        assertEquals(listOf("en"), PrismLocale.localeTagsFor(PrismLocale.EN))
        assertEquals(emptyList<String>(), PrismLocale.localeTagsFor(PrismLocale.SYSTEM))
    }

    @Test
    fun `all language choices have resources and settings entries`() {
        val required = setOf(
            "prism_language_system",
            "prism_language_en",
            "prism_language_zh",
            "prism_language_zh_tw",
        )
        assertTrue(stringsIn("mobile/src/main/res/values/strings.xml").keys.containsAll(required))
        assertTrue(stringsIn("mobile/src/main/res/values-zh/strings.xml").keys.containsAll(required))
        assertTrue(stringsIn("mobile/src/main/res/values-zh-rTW/strings.xml").containsKey("prism_language_zh_tw"))

        val settingsSource = file("mobile/src/main/java/com/yzddmr6/prismspace/prism/compose/screen/SettingsScreen.kt").readText()
        listOf("SYSTEM", "EN", "ZH", "ZH_TW").forEach { choice ->
            assertTrue("Missing language choice: $choice", settingsSource.contains("PrismLocale.$choice"))
        }
    }

    @Test
    fun `traditional chinese covers chrome home app and space strings`() {
        // zh-TW completeness: navigation/home/app-action/space strings must not fall back
        // (simplified-Chinese fallback is a stopgap, not the shipped state). Keys may live in any
        // values-zh-rTW file — coverage is what matters, not placement.
        val tw = file("mobile/src/main/res/values-zh-rTW").listFiles().orEmpty()
            .filter { it.extension == "xml" }
            .flatMap { stringsIn(it).keys }
            .toSet()
        listOf("strings_chrome", "strings_home", "strings_app", "strings_space", "strings_xfer").forEach { family ->
            val zhKeys = stringsIn("mobile/src/main/res/values-zh/$family.xml").keys
            val missing = zhKeys - tw
            assertEquals("values-zh-rTW missing $family keys: $missing", emptySet<String>(), missing)
        }
    }

    @Test
    fun `production mappers cannot restore a hard coded fallback catalog`() {
        val production = file("mobile/src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .joinToString("\n") { it.readText() }

        assertFalse(production.contains("zhFallback"))
        assertFalse(production.contains("zhTemplate"))
    }

    @Test
    fun `live resources use one product name and current space terminology`() {
        val forbiddenValues = listOf(
            Regex("Cross-land", RegexOption.IGNORE_CASE),
            Regex("\\bMainland\\b", RegexOption.IGNORE_CASE),
            Regex("棱镜空间|棱鏡空間|稜鏡空間"),
        )
        liveResourceFiles().forEach { resourceFile ->
            stringsIn(resourceFile).forEach { (name, value) ->
                forbiddenValues.forEach { forbidden ->
                    assertFalse(
                        "Forbidden term ${forbidden.pattern} in ${resourceFile.path}:$name",
                        forbidden.containsMatchIn(value),
                    )
                }
                if (name == "app_name") {
                    assertEquals("Unexpected product name in ${resourceFile.path}", "PrismSpace", value.trim())
                }
            }
        }
    }

    private fun liveResourceFiles(): Sequence<File> =
        listOf("mobile", "shared", "engine", "installer", "assembly", "probe", "open", "watcher")
            .asSequence()
            .map(::file)
            .filter(File::isDirectory)
            .flatMap { module -> module.walkTopDown().asSequence() }
            .filter { candidate ->
                candidate.isFile &&
                    candidate.extension == "xml" &&
                    candidate.invariantSeparatorsPath.contains("/src/") &&
                    candidate.invariantSeparatorsPath.contains("/res/values")
            }

    private fun stringsIn(path: String): Map<String, String> = stringsIn(file(path))

    private fun stringsIn(resourceFile: File): Map<String, String> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resourceFile).documentElement
        return (0 until root.childNodes.length).mapNotNull { index ->
            val element = root.childNodes.item(index) as? Element ?: return@mapNotNull null
            if (element.tagName != "string") return@mapNotNull null
            element.getAttribute("name") to element.textContent
        }.toMap()
    }

    private fun file(path: String): File {
        val fromRoot = File(path)
        if (fromRoot.exists()) return fromRoot
        return File("..", path)
    }
}
