package com.safesphere.app.core.textcheck

import com.safesphere.app.core.textcheck.DraftNudge.Nudge
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 13E: when the phone nudges before sharing (never a block; the server decides on its own). */
class DraftNudgeTest {

    private fun result(vararg evidence: TextCheck.Evidence, selfHarm: Boolean = false) =
        TextCheck.Result("LATIN", evidence.toList(), false, selfHarm)

    private fun e(category: String, severity: Int, targeted: Boolean = true, quoted: Boolean = false) =
        TextCheck.Evidence(category, severity, targeted, quoted, 0, 4)

    @Test
    fun `support comes first and only ever offers help`() {
        assertEquals(Nudge.Support, DraftNudge.of(result(e("THREAT", 3), selfHarm = true)))
    }

    @Test
    fun `what the server would collapse or hold is named, the most serious first`() {
        assertEquals(Nudge.Care("HARASSMENT", listOf(0 until 4)), DraftNudge.of(result(e("HARASSMENT", 2))))
        val mixed = DraftNudge.of(result(e("HARASSMENT", 2), e("THREAT", 3)))
        assertTrue(mixed is Nudge.Care && mixed.category == "THREAT" && mixed.spans.size == 2)
        assertEquals("SPAM", (DraftNudge.of(result(e("SPAM", 3, targeted = false))) as Nudge.Care).category)
    }

    @Test
    fun `banter, untargeted profanity and quoted speech are not nudged`() {
        assertEquals(Nudge.None, DraftNudge.of(result(e("HARASSMENT", 1))))
        assertEquals(Nudge.None, DraftNudge.of(result(e("PROFANITY", 3, targeted = false))))
        assertEquals(Nudge.None, DraftNudge.of(result(e("THREAT", 3, quoted = true))))
        assertEquals(Nudge.None, DraftNudge.of(result(e("HARASSMENT", 3, targeted = false))))
    }

    @Test
    fun `a phone number gets a gentle question`() {
        assertEquals(Nudge.Contact(listOf(0 until 4)), DraftNudge.of(result(e("DOXXING", 1, targeted = false))))
    }

    @Test
    fun `with the real lexicon, Tamil, Tanglish and English`() {
        val check = TextCheck(HashedLexicon(File("src/main/assets/" + HashedLexicon.ASSET).readLines(Charsets.UTF_8)))
        assertTrue(DraftNudge.of(check.check("Poda naaye, un review waste")) is Nudge.Care)
        assertTrue(DraftNudge.of(check.check("நீ ஒரு தேவடியா")) is Nudge.Care)
        assertTrue(DraftNudge.of(check.check("Unna k o l l a poren")) is Nudge.Care)
        assertEquals(Nudge.Support, DraftNudge.of(check.check("Vaazha pidikala, beach la ukkaandhu irundhen")))
        assertEquals(Nudge.None, DraftNudge.of(check.check("Sema place da, vera level view!")))
        assertEquals(Nudge.None, DraftNudge.of(check.check("Machan otha sema mass trip da idhu")))
        assertEquals(Nudge.None, DraftNudge.of(check.check(
            "A man at the bus stop called me a \"thevidiya\" when I didn't reply. Please be careful.")))
        assertTrue(DraftNudge.of(check.check("Call 9840012345 to book")) is Nudge.Contact)
    }

    @Test
    fun `spans become the person's own words`() {
        assertEquals("naaye", TextSpans.phrase("Poda naaye!", 5 until 10))
        assertEquals("தேவடியா", TextSpans.phrase("நீ ஒரு தேவடியா", 7 until 14))
        assertEquals(null, TextSpans.phrase("short", 3 until 10))
        assertEquals("a”, “b", TextSpans.phrases("a b", listOf(0 until 1, 2 until 3, 0 until 1)))
    }
}
