package com.safesphere.app.feature.intelligence

import com.safesphere.app.feature.intelligence.domain.AreaOutlook
import com.safesphere.app.feature.intelligence.domain.InformationLevel
import com.safesphere.app.feature.intelligence.domain.IntelligencePolicy.Compass
import com.safesphere.app.feature.intelligence.domain.PictureReason
import com.safesphere.app.feature.intelligence.ui.IntelligenceTexts
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 8 words. Every code has its own text, and no Safety Intelligence text ever claims that a place or a way is
 * safe, safest, dangerous or verified (docs/PHASE8_DESIGN.md §6.4, §16.1 rule 7). Negations ("does not mean it is
 * safe", "cannot tell you that a place is safe") are the honest exception and are allowed.
 */
class IntelligenceTextsTest {

    @Test
    fun `every code has its own words`() {
        assertEquals(AreaOutlook.entries.size, AreaOutlook.entries.map { IntelligenceTexts.headline(it) }.toSet().size)
        assertEquals(PictureReason.entries.size, PictureReason.entries.map { IntelligenceTexts.reason(it) }.toSet().size)
        assertEquals(InformationLevel.entries.size, InformationLevel.entries.map { IntelligenceTexts.information(it) }.toSet().size)
        assertEquals(Compass.entries.size, Compass.entries.map { IntelligenceTexts.compass(it) }.toSet().size)
    }

    @Test
    fun `no intelligence text claims safety or danger`() {
        val texts = phase8Strings()
        assertTrue("Phase 8 strings not found", texts.size >= 20)
        val problems = mutableListOf<String>()
        for ((name, text) in texts) {
            val lower = text.lowercase()
            FORBIDDEN.filter { it in lower }.forEach { problems += "$name contains \"$it\"" }
            for (sentence in lower.split(". ")) {
                if (SAFE_CLAIM.containsMatchIn(sentence) && NEGATION.none { it in sentence }) {
                    problems += "$name claims safety: \"$sentence\""
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `the no-information headline says it is not the same as safe`() {
        val text = phase8Strings().getValue("outlook_no_information").lowercase()
        assertTrue(text.contains("does not mean"))
    }

    private fun phase8Strings(): Map<String, String> {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        return STRING.findAll(xml)
            .filter { m -> PREFIXES.any { m.groupValues[1].startsWith(it) } }
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private companion object {
        val STRING = Regex("<string name=\"([a-z_]+)\">(.*?)</string>")
        val PREFIXES = listOf("outlook_", "picture_", "information_", "radar_legend_cells", "cd_picture", "way_")
        val FORBIDDEN = listOf("safest", "safe route", "safe way", "dangerous", "verified", "guarantee", "no risk",
            "risk-free", "all clear", "danger zone")
        val SAFE_CLAIM = Regex("\\b(is|are|it's|feels?) safe\\b|\\bsafe (area|here|place to be)\\b")
        val NEGATION = listOf("not ", "cannot", "can't", "n't ")
    }
}
