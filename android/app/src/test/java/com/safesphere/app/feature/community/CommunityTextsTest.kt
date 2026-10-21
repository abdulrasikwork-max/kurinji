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
}
