package com.safesphere.app.feature.community

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 9D: the report confirmation tells people, before they share, that a report may be added to the same report
 * in the block next to theirs (the server's neighbour join), and that "your reports" then shows it as a confirmation.
 * It must not suggest a place is safe, and must keep the Phase 6 privacy promises it already made.
 */
class CommunityTextsTest {

    @Test
    fun `the report confirmation explains the neighbour join honestly`() {
        val text = string("report_confirm_message").lowercase()
        assertTrue("mentions the block next to yours", text.contains("block right next to yours"))
        assertTrue("says the report is added, not lost", text.contains("your report is added to it"))
        assertTrue("says how it then appears", text.contains("something you saw too"))
        for (promise in listOf("without your name", "about 150 m", "about 1 km", "random delay", "never a person")) {
            assertTrue("still promises \"$promise\"", text.contains(promise))
        }
        assertTrue("no safety claim", !Regex("\\b(is|are|it's) safe\\b").containsMatchIn(text))
    }

    @Test
    fun `a joined report reads as a confirmation in your reports`() {
        assertTrue(string("your_kind_confirm").lowercase().contains("saw this too"))
    }

    private fun string(name: String): String {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        return Regex("<string name=\"$name\">(.*?)</string>").find(xml)?.groupValues?.get(1)
            ?: error("string $name not found")
    }

    /** Phase 17D: every location problem says what to do, in plain words, and no text claims a place is safe. */
    @Test
    fun `17D - location problems say what to do`() {
        val rough = string("community_rough_message").lowercase()
        assertTrue(rough.contains("about 100 m"))
        assertTrue("says to move or wait", rough.contains("move outside or wait for a better signal"))
        assertTrue(string("community_no_fix").lowercase().contains("move outside or wait for a better signal"))
        assertTrue(string("community_needs_precise").lowercase().contains("precise location"))
        assertTrue("the settings way is named", string("community_needs_precise_settings").contains("Use precise location"))
        assertTrue(string("safe_walk_hint_no_signal").lowercase().contains("check-ins still work"))
        assertTrue(string("safe_walk_hint_location_off").lowercase().contains("check-ins still work"))
        assertTrue(string("safe_walk_movement_needs_precise").lowercase().contains("check-ins still work"))
        for (name in listOf("community_rough_message", "community_rough_message_unknown", "community_stale", "community_updated",
            "community_locating_precise", "community_locating_precise_accuracy", "community_search_stopped",
            "community_rough_position", "community_rough_position_unknown", "community_permission_blocked",
            "community_location_off_report", "safe_walk_hint_location_off", "safe_walk_hint_no_signal",
            "safe_walk_setup_location_precise", "safe_walk_setup_location_approximate", "safe_walk_setup_location_off")) {
            val text = string(name).lowercase()
            assertTrue("$name claims safety", !Regex("\b(is|are|it's|you're) safe\b|\bsafest\b").containsMatchIn(text))
        }
    }
}
