package com.safesphere.app.feature.social

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 13A words (EXPERIENCE_PLATFORM_DESIGN.md §2.2): every social_* text and every string the social screens use is
 * scanned. No counts of people, no follower/like/popularity wording, no presence ("online", "last seen"), no claim that
 * anyone or anything is safe, and blocks/mutes/removals always say the other person is not told.
 */
class SocialTextsTest {

    @Test
    fun `social texts use no popularity, presence or safety-claim words`() {
        val strings = allStrings()
        val names = socialStringNames(strings)
        assertTrue("social strings not found", names.size >= 60)
        val problems = mutableListOf<String>()
        for (name in names) {
            val text = strings[name]
            if (text == null) {
                problems += "$name is not in strings.xml"
                continue
            }
            val lower = text.lowercase()
            FORBIDDEN.filter { it.containsMatchIn(lower) }.forEach { problems += "$name: \"$text\" matches ${it.pattern}" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `no social text shows a number of people`() {
        val strings = allStrings()
        val problems = socialStringNames(strings).filter { name ->
            val text = strings[name].orEmpty()
            // %d placeholders would be counts; the only digits allowed are the fixed rules (18, 3 to 20, 7 days, ...).
            name !in AUTHOR_ONLY_COUNTS && (text.contains("%d") || text.contains("%1\$d"))
        }
        assertTrue("counts in: $problems", problems.isEmpty())
        // Phase 13C (C2): the one number, the author's own private line, says so, and only exists from 3 people up.
        for (name in AUTHOR_ONLY_COUNTS) assertTrue(name, strings[name].orEmpty().contains("Only you can see this line"))
        assertTrue(strings["butterfly_author_fewer"].orEmpty().startsWith("Fewer than 3 people"))
        // Emulator finding (13C): the Cocoon's saved line has its own words, never a band sentence glued after "Saved".
        assertTrue(strings["cocoon_saved_this_week"] == "Saved this week")
    }

    @Test
    fun `quiet actions say that the other person is not told`() {
        val strings = allStrings()
        for (name in listOf("social_block_message", "social_remove_connection_message", "social_muted_message")) {
            assertTrue("$name must say they are not told", strings[name].orEmpty().contains("not told"))
        }
    }

    @Test
    fun `the date of birth texts say it is not stored`() {
        val strings = allStrings()
        for (name in listOf("register_date_of_birth_helper", "social_age_body")) {
            assertTrue("$name must say it is not stored", strings[name].orEmpty().contains("not stored"))
        }
    }

    /** Phase 13B: no "I'm here now" posting (design §4.3); the delay and the photo clean-up are explained honestly. */
    @Test
    fun `experience texts never invite live posting and explain the delay and the photos honestly`() {
        val strings = allStrings()
        val problems = socialStringNames(strings).filter { name ->
            val text = strings[name].orEmpty().lowercase()
            LIVE.any { it.containsMatchIn(text) }
        }
        assertTrue("live wording in: $problems", problems.isEmpty())
        assertTrue(strings["social_delay_notice"].orEmpty().contains("1 to 3 hours"))
        assertTrue(strings["social_photos_note"].orEmpty().contains("where and when"))
        assertTrue(strings["social_private_intro"].orEmpty().contains("never sent"))
        assertTrue(strings["social_near_me_note"].orEmpty().contains("never your position"))
        assertTrue(strings["social_hidden_by_author"].orEmpty().startsWith("Hidden by the author"))
    }

    private fun allStrings(): Map<String, String> {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        return STRING.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** Every social_* string, plus every string the social screens' layouts and code refer to. */
    private fun socialStringNames(strings: Map<String, String>): Set<String> {
        val sources = File("src/main/java/com/safesphere/app/feature/social").walkTopDown().filter { it.extension == "kt" } +
            LAYOUTS.map { File("src/main/res/layout/$it") }
        val referenced = sources.flatMap { file ->
            assertTrue("missing ${file.path}", file.isFile)
            val pattern = if (file.extension == "xml") LAYOUT_REF else CODE_REF
            pattern.findAll(file.readText(Charsets.UTF_8)).map { it.groupValues[1] }.toList()
        }.toSet()
        return strings.keys.filter { it.startsWith("social_") }.toSet() + referenced
    }

    private companion object {
        val STRING = Regex("<string name=\"([a-z0-9_]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
        val LAYOUT_REF = Regex("@string/([a-z0-9_]+)")
        val CODE_REF = Regex("R\\.string\\.([a-z0-9_]+)")
        val LAYOUTS = listOf("fragment_profile_setup.xml", "fragment_connections.xml", "fragment_person_profile.xml",
            "fragment_blocked_muted.xml", "item_person_row.xml", "fragment_compose.xml", "fragment_place_picker.xml",
            "fragment_post.xml", "fragment_my_posts.xml", "fragment_private_places.xml", "item_photo.xml",
            "fragment_discover.xml", "fragment_cocoon.xml", "fragment_post_collection.xml",
            // Phase 13D
            "fragment_my_reports.xml", "fragment_guidelines.xml")
        /** Phase 13C (C2): the only text with a number of people: the author's own private line. */
        val AUTHOR_ONLY_COUNTS = setOf("butterfly_author_count")
        val LIVE = listOf(Regex("\\blive\\b"), Regex("right now here"), Regex("\\bi'?m here\\b"), Regex("\\bhere now\\b"),
            Regex("\\bcheck[- ]?in\\b"), Regex("who is here"))
        val FORBIDDEN = listOf(
            Regex("\\bfollow(er|ers|ing|s)?\\b"), Regex("\\blikes?\\b"), Regex("\\bpopular"), Regex("\\btrending\\b"),
            Regex("\\bfans?\\b"), Regex("\\bonline\\b"), Regex("last seen"), Regex("\\bstreaks?\\b"),
            Regex("\\bsafe\\b"), Regex("\\bsafest\\b"), Regex("\\bverified\\b"),
            // Phase 13C: no ranking or engagement words in Discover (design §3.7).
            Regex("^for you$"), Regex("\\b(picked|chosen|made|curated) for you\\b"), Regex("\\brecommended\\b"),
            Regex("\\btop\\b"), Regex("\\bmost (liked|popular|saved|wanted|viewed|read)\\b"),
            Regex("\\brank(ed|ing|s)?\\b"), Regex("\\bhot\\b"), Regex("\\bviral\\b"), Regex("\\bbest\\b"),
            Regex("\\bfeed\\b"), Regex("\\bviews?\\b")
        )
    }
}
