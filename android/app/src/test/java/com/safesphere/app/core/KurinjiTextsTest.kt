package com.safesphere.app.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 13F (docs/PHASE13F_PLAN.md §2 "Voice", F1): Kurinji's words are plain, warm and specific. No emoji in UI text
 * (the butterfly is drawn), no slang or hype, the visible brand is Kurinji, and the lines the identity rests on exist.
 */
class KurinjiTextsTest {

    private val strings: Map<String, String> by lazy {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        Regex("<string name=\"([a-z0-9_]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `no emoji in any UI text`() {
        val emoji = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]")
        val found = strings.filterValues { emoji.containsMatchIn(it) }.keys
        assertTrue("emoji in: $found", found.isEmpty())
    }

    @Test
    fun `no slang, hype or engagement bait`() {
        // ("lit" is not listed: "Well lit" is a real place signal.)
        val words = listOf("vibe", "slay", "bestie", "omg", "fam ", "no cap", "epic", "insane", "crushing it",
            "don't miss", "don\\'t miss", "hurry", "you won't believe", "you won\\'t believe", "trending", "going viral",
            "!!", "awesome", "amazing", "must-see", "must see", "bucket list", "fomo")
        val problems = strings.flatMap { (name, text) ->
            val lower = text.lowercase()
            words.filter { it in lower }.map { "$name: \"$it\"" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `the visible brand is Kurinji`() {
        assertEquals("Kurinji", strings["app_name"])
        val old = strings.filterValues { it.contains("SafeSphere") }.keys
        assertTrue("old brand in: $old", old.isEmpty())
        val manifest = File("src/main/AndroidManifest.xml").readText(Charsets.UTF_8)
        assertTrue(manifest.contains("android:label=\"@string/app_name\""))
    }

    @Test
    fun `the lines the identity rests on are there`() {
        assertEquals("Written for the next one who goes.", strings["motto"])
        assertEquals("That\\'s this week\\'s edition. Go make your own.", strings["discover_edition_end"])
        assertTrue(strings.getValue("cocoon_empty_want").contains("Butterfly something"))
        assertEquals("For the next person", strings["card_ticket_label"])
        assertEquals("Going there?", strings["going_there"])
    }
}
