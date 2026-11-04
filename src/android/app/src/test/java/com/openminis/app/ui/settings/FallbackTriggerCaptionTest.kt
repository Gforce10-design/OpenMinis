package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Default fallback caption used to say the group advances on 429 and 5xx
 * only. The loop also advances on a rejected request (400), an auth failure
 * (401/403), a certificate error, and any other provider error. The caption
 * has to say so, in English and Korean, without changing that decision.
 */
class FallbackTriggerCaptionTest {

    private fun resRoot(): File {
        val javaMain = ProductionSources.mainRoot()
            ?: error("production source root not found")
        // .../src/main/java/com/openminis/app → .../src/main
        val main = javaMain.parentFile!!.parentFile!!.parentFile!!.parentFile!!
        val res = File(main, "res")
        require(res.isDirectory) { "res dir missing: ${res.absolutePath}" }
        return res
    }

    private fun strings(locale: String): String {
        val dir = if (locale.isEmpty()) "values" else "values-$locale"
        val file = File(resRoot(), "$dir/strings.xml")
        require(file.isFile) { "strings missing: ${file.absolutePath}" }
        return file.readText()
    }

    private fun footer(xml: String, name: String): String {
        val key = "name=\"$name\">"
        val start = xml.indexOf(key)
        assertTrue("missing string $name", start >= 0)
        val from = start + key.length
        val end = xml.indexOf("</string>", from)
        return xml.substring(from, end)
    }

    @Test
    fun `english and korean default captions name every class that advances`() {
        val en = footer(strings(""), "model_group_detail_fallback_default_footer")
        val ko = footer(strings("ko"), "model_group_detail_fallback_default_footer")
        for (text in listOf(en, ko)) {
            assertTrue(text.contains("429"))
            assertTrue(text.contains("5xx"))
            assertTrue(text.contains("400"))
            assertTrue(text.contains("401"))
            assertTrue(text.contains("403"))
        }
        assertTrue(en.contains("certificate", ignoreCase = true))
        assertTrue(en.contains("provider error", ignoreCase = true))
        assertFalse("the old 'only 429/5xx' claim", en.contains("5xx) only") || en.contains("only."))
        assertTrue(ko.contains("인증서"))
        assertTrue(ko.contains("제공자 오류"))
        assertFalse(ko.contains("만"))
    }

    @Test
    fun `the screen reads the localized captions and the old sentence is gone`() {
        val screen = ProductionSources.read("ui/settings/ModelGroupDetailScreen.kt")
        assertTrue(screen.contains("R.string.model_group_detail_fallback_default_footer"))
        assertTrue(screen.contains("R.string.model_group_detail_fallback_always_footer"))
        assertFalse(screen.contains("Fall back on rate limits (429) and server errors (5xx) only."))
        val alwaysEn = footer(strings(""), "model_group_detail_fallback_always_footer")
        val alwaysKo = footer(strings("ko"), "model_group_detail_fallback_always_footer")
        assertTrue(alwaysEn.contains("any error"))
        assertTrue(alwaysKo.isNotBlank())
    }

    @Test
    fun `the config field description no longer says default is only 429 and 5xx`() {
        val groups = ProductionSources.read("config/collections/GroupsCollection.kt")
        assertFalse(groups.contains("only on rate limiting"))
        assertTrue(groups.contains("401/403"))
        assertTrue(groups.contains("certificate"))
    }
}
