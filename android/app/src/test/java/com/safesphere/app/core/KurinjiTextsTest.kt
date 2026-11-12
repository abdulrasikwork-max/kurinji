package com.safesphere.app.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 13F (docs/PHASE13F_PLAN.md §2 "Voice", F1; the brand is IRL since 2026-11-03): the words are plain, warm and
 * specific. No emoji in UI text (the butterfly is drawn), no slang or hype, the visible brand is IRL, and the lines the
 * identity rests on exist.
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

    /**
     * The IRL rebrand (2026-11-03, docs/DESIGN_SYSTEM.md "Name"): the visible name is IRL; no string a person reads may
     * still say Kurinji or SafeSphere (code names, the package and the applicationId stay SafeSphere on purpose).
     */
    @Test
    fun `the visible brand is IRL`() {
        assertEquals("IRL", strings["app_name"])
        val old = strings.filterValues { it.contains("SafeSphere") || it.contains("Kurinji") || it.contains("குறிஞ்சி") }.keys
        assertTrue("old brand in: $old", old.isEmpty())
        val manifest = File("src/main/AndroidManifest.xml").readText(Charsets.UTF_8)
        assertTrue(manifest.contains("android:label=\"@string/app_name\""))
        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        val gradle = File("build.gradle.kts").readText(Charsets.UTF_8)
        assertTrue("the installation identity must not change", gradle.contains("applicationId = \"com.safesphere.app\""))
    }

    /** The IRL opening (2026-11-03): the brand line and the opening screen's exact words. */
    @Test
    fun `the lines the identity rests on are there`() {
        assertEquals("Out there. Together.", strings["motto"])
        assertEquals(listOf("Your world.", "Your people.", "Your freedom."),
            listOf("opening_line_world", "opening_line_people", "opening_line_freedom").map { strings[it] })
        // 2026-11-12: the opening says only the name, the line and the three lines; the longer sentence was taken out.
        assertNull(strings["opening_supporting"])
        assertEquals("IRL, in real life. Out there. Together.", strings["opening_brand_spoken"])
        assertEquals("Let\\'s go →", strings["opening_cta"])
        assertEquals("You\\'re all caught up.", strings["discover_edition_end"])
        // Phase 17E (D-3): the butterfly is a reaction; Save keeps a post; "I went" leads to sharing.
        assertEquals("Inspired", strings["reaction_label"])
        assertTrue(strings.getValue("saved_empty_message").startsWith("Tap Save"))
        assertEquals("I went", strings["went_label"])
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
