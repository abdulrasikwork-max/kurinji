package com.safesphere.app.feature.social

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-device sprint, Track C7 (Galaxy A23): on the place page the "I went here" button changed its TEXT after a visit
 * ("You went here · 1 out of 5") but kept the first-visit spoken label ("I went here: mark that you went ..."), so a person
 * using TalkBack was never told the state. The spoken label now follows the same three states as the text.
 */
class Phase17HWentLabelTest {

    private val strings = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
    private val fragment = File(
        "src/main/java/com/safesphere/app/feature/social/ui/PostCollectionFragment.kt"
    ).readText(Charsets.UTF_8)

    private fun string(name: String): String =
        Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(strings)?.groupValues?.get(1)
            ?: error("string $name not found")

    @Test
    fun `each visible state of the button has its own spoken label`() {
        assertTrue(string("place_went_description_state").startsWith("You have been to"))
        assertTrue(string("place_went_description_state").contains("not rated"))
        val rated = string("place_went_description_rated")
        assertTrue(rated.startsWith("You have been to"))
        assertTrue("the person's own number is spoken", rated.contains("%2\$s out of 5"))
        // The first-visit prompt is only for a person who has not been marked as having gone.
        assertTrue(string("place_share_yours_description").startsWith("Been here"))
    }

    @Test
    fun `the place page picks the spoken label by the visit, like the text`() {
        val block = fragment.substringAfter("binding.wentButton.contentDescription").substringBefore("binding.wentButton.text")
        assertTrue(block.contains("visit == null -> getString(R.string.place_share_yours_description"))
        assertTrue(block.contains("visit.rating == null -> getString(R.string.place_went_description_state"))
        assertTrue(block.contains("R.string.place_went_description_rated"))
        assertEquals("exactly one label per state", 3, Regex("->\\s*getString").findAll(block).count())
    }

    @Test
    fun `the labels never print a count of people`() {
        listOf("place_went_description_state", "place_went_description_rated").forEach {
            assertTrue(it, !string(it).contains("%d") && !string(it).lowercase().contains("people"))
        }
    }
}
