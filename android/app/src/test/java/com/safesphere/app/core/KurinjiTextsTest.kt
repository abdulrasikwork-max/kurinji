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

    /** Phase 16 (docs/PHASE16.md): the new line, plain words for the save and the tip, and an end to Discover (U3). */
    @Test
    fun `the lines the identity rests on are there`() {
        assertEquals("Go. See. Tell.", strings["motto"])
        assertEquals(listOf("Go.", "See.", "Tell."), listOf("motto_go", "motto_see", "motto_tell").map { strings[it] })
        assertEquals("You\\'re all caught up.", strings["discover_edition_end"])
        assertTrue(strings.getValue("cocoon_empty_want").contains("Tap the butterfly"))
        assertEquals("Saved", strings["nav_cocoon"])
        assertEquals("Tip", strings["card_ticket_label"])
        assertEquals("Comments", strings["social_perspectives_title"])
        assertEquals("Going there?", strings["going_there"])
    }

    /** Phase 16: no invented words a first-time user would have to learn (the 13F vocabulary is gone from the UI). */
    @Test
    fun `no invented vocabulary in what people read`() {
        val words = listOf("cocoon", "perspective", "postmark", "edition", "for the next person", "butterfly it")
        val problems = strings.filter { (name, text) ->
            // Moderators' and the deletion texts may name things plainly; nothing may use the retired words.
            words.any { it in text.lowercase() } && !name.startsWith("content_case_")
        }.keys
        assertTrue("invented words in: $problems", problems.isEmpty())
    }
}
