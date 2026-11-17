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
            text.contains("%d") || text.contains("%1\$d")
        }
        // N4: no count of anything, anywhere (Same shows "a few / many", never a number: N5).
        assertTrue("counts in: $problems", problems.isEmpty())
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

    /** No "I'm here now" posting, no presence (PHASE_N4 §1): a moment says what is on someone's mind, not where they are. */
    @Test
    fun `moment texts never invite presence posting and never name a place, a distance or who looked`() {
        val strings = allStrings()
        val problems = socialStringNames(strings).filter { name ->
            val text = strings[name].orEmpty().lowercase()
            LIVE.any { it.containsMatchIn(text) }
        }
        assertTrue("live wording in: $problems", problems.isEmpty())
        val retired = socialStringNames(strings).filter { name ->
            val text = strings[name].orEmpty().lowercase()
            RETIRED.any { it.containsMatchIn(text) }
        }
        assertTrue("retired ideas in: $retired", retired.isEmpty())
    }

    /** N4-C walk finding: the "Settings and more" row still offered the private places removed in A1. */
    @Test
    fun `no text anywhere offers the removed private places`() {
        val problems = allStrings().filter { (_, text) -> Regex("\\bprivate places?\\b").containsMatchIn(text.lowercase()) }.keys
        assertTrue("private places in: $problems", problems.isEmpty())
    }

    private fun allStrings(): Map<String, String> {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        return STRING.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** Every social_* string, plus every string the social screens' layouts and code refer to. */
    private fun socialStringNames(strings: Map<String, String>): Set<String> {
        val sources = listOf("social", "moments").flatMap { feature ->
            File("src/main/java/com/safesphere/app/feature/$feature").walkTopDown().filter { it.extension == "kt" }.toList()
        } +
            LAYOUTS.map { File("src/main/res/layout/$it") }
        val referenced = sources.flatMap { file ->
            assertTrue("missing ${file.path}", file.isFile)
            val pattern = if (file.extension == "xml") LAYOUT_REF else CODE_REF
            pattern.findAll(file.readText(Charsets.UTF_8)).map { it.groupValues[1] }.toList()
        }.toSet()
        // N3: "verified" here forbids a safety badge on people. The community membership itself is "verified" by
        // design (IRL_DIRECTION, a verified real-name community); its texts are guarded by MembershipTextsTest.
        return (strings.keys.filter { it.startsWith("social_") || it.startsWith("moment_") || it.startsWith("today_") }.toSet() + referenced)
            .filterNot { name -> MEMBERSHIP.any { name.startsWith(it) } }.toSet()
    }

    private companion object {
        val STRING = Regex("<string name=\"([a-z0-9_]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
        val LAYOUT_REF = Regex("@string/([a-z0-9_]+)")
        val CODE_REF = Regex("R\\.string\\.([a-z0-9_]+)")
        val LAYOUTS = listOf("fragment_profile_setup.xml", "fragment_connections.xml", "fragment_person_profile.xml",
            "fragment_blocked_muted.xml", "item_person_row.xml", "fragment_you.xml", "fragment_more.xml",
            // N4: moments
            "fragment_today.xml", "fragment_moment.xml", "item_moment_card.xml", "item_today_question.xml",
            "item_today_message.xml", "item_moment_section.xml", "item_moment_note.xml", "fragment_create.xml",
            // Phase 13D
            "fragment_my_reports.xml", "fragment_guidelines.xml")
        /** N3: the membership words (MembershipTextsTest guards them; "Get verified" names the membership step). */
        val MEMBERSHIP = listOf("action_get_verified", "membership_", "you_membership_")
        /** N4 (N-58): ideas that must not come back in words: places, distance, who looked, reactions, the gallery. */
        val RETIRED = listOf(Regex("\\bnearby\\b"), Regex("\\bnear you\\b"), Regex("\\bkm\\b"), Regex("\\bdistance\\b"),
            Regex("\\bseen by\\b"), Regex("\\bviewed\\b"), Regex("\\bread receipt"), Regex("\\bupvote"), Regex("\\bgallery\\b"),
            Regex("\\bvideo\\b"), Regex("\\brating\\b"), Regex("\\bcomments?\\b"))
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
