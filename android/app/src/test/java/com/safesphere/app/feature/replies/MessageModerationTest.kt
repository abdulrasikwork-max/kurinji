package com.safesphere.app.feature.replies

import com.safesphere.app.R
import com.safesphere.app.feature.moderation.data.remote.ContentViewDto
import com.safesphere.app.feature.moderation.data.remote.ContextMessageDto
import com.safesphere.app.feature.moderation.data.remote.ConversationContextDto
import com.safesphere.app.feature.moderation.domain.ContentTarget
import com.safesphere.app.feature.moderation.ui.ContentModerationTexts
import com.safesphere.app.feature.moments.domain.MomentPlan
import com.safesphere.app.feature.replies.domain.ConversationKind
import com.safesphere.app.feature.replies.domain.Message
import com.safesphere.app.feature.replies.domain.MessageEvent
import com.safesphere.app.feature.replies.domain.MessageKind
import com.safesphere.app.feature.replies.domain.Turn
import com.safesphere.app.feature.replies.ui.RepliesLabels
import com.safesphere.app.feature.social.domain.ReportReason
import com.safesphere.app.feature.social.domain.ReportTarget
import com.safesphere.app.testing.conversation
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase N5-A3 on the phone: messages can be reported (never one's own), the writer can ask a person to look; moderators
 * read a message case with its context (pseudonyms, never the whole conversation) and a moment's follow-up; This
 * happened is offered only when the server would take it; the guidelines say how replies and plans work.
 */
class MessageModerationTest {

    @Test
    fun `a message is reported with the moment reasons, impersonation is for people only`() {
        assertFalse(ReportReason.IMPERSONATION in ReportReason.forTarget(ReportTarget.MESSAGE))
        assertTrue(ReportReason.accepts(ReportTarget.MESSAGE, ReportReason.HARASSMENT, null))
        assertFalse(ReportReason.accepts(ReportTarget.MESSAGE, ReportReason.OTHER, " "))
        assertTrue(ReportReason.accepts(ReportTarget.MESSAGE, ReportReason.OTHER, "keeps writing after I ended it"))
    }

    @Test
    fun `a message case keeps its context lines with pseudonyms, drops blank ones, reads unknown kinds as none`() {
        val content = ContentViewDto(kind = "MESSAGE", body = "you again", conversation = ConversationContextDto("REPLY",
            listOf(ContextMessageDto("Person 3f9a", "hi"), ContextMessageDto(" ", "x"), ContextMessageDto("Person 77c1", " "),
                ContextMessageDto("Person 3f9a", "you again", reported = true), null), connected = false)).toDomain()
        assertEquals("MESSAGE", content.kind)
        val context = content.conversation!!
        assertEquals("REPLY", context.kind)
        assertEquals(listOf("hi", "you again"), context.lines.map { it.text })
        assertEquals(listOf(false, true), context.lines.map { it.reported })
        assertEquals(false, context.connected)
        assertNull(ConversationContextDto("GROUP").toDomain().kind)
        val moment = ContentViewDto(kind = "SAY", body = "x", more = "  ").toDomain()
        assertNull(moment.more)
        assertEquals("the follow-up is part of the words", "added", ContentViewDto(kind = "SAY", more = "added").toDomain().more)
        assertNull(ContentViewDto(kind = "VOICE").toDomain().kind)
        assertEquals(R.string.content_target_message, ContentModerationTexts.of(ContentTarget.MESSAGE))
    }

    private fun happened(at: Instant?, open: Boolean = true, calledOff: Boolean = false, lines: List<Message> = emptyList()) =
        conversation(kind = ConversationKind.PLAN, turn = Turn.OPEN, open = open, messages = lines).let {
            it.copy(moment = it.moment.copy(plan = MomentPlan(at, "Grounds"), calledOff = calledOff))
        }

    @Test
    fun `This happened is offered in an open plan after its time, once, never after Call it off`() {
        val now = Instant.parse("2026-11-18T12:00:00Z")
        assertTrue(RepliesLabels.canShareHappened(happened(now.minusSeconds(60)), now))
        assertTrue("a plan without a time", RepliesLabels.canShareHappened(happened(null), now))
        assertFalse("before its time", RepliesLabels.canShareHappened(happened(now.plusSeconds(60)), now))
        assertFalse(RepliesLabels.canShareHappened(happened(null, open = false), now))
        assertFalse(RepliesLabels.canShareHappened(happened(null, calledOff = true), now))
        val done = Message("m", MessageKind.SYSTEM, null, MessageEvent.HAPPENED, null, false, null, false, null, false)
        assertFalse("once per plan", RepliesLabels.canShareHappened(happened(null, lines = listOf(done)), now))
        assertFalse("not in a private reply", RepliesLabels.canShareHappened(conversation(), now))
    }

    @Test
    fun `the conversation screen reports others' messages only, and asks for a review of one's own`() {
        val screen = File("src/main/java/com/safesphere/app/feature/replies/ui/ConversationFragment.kt").readText(Charsets.UTF_8)
        assertTrue(screen.contains("if (message.mine || message.kind != MessageKind.TEXT) return"))
        assertTrue(screen.contains("reports.report(ReportTarget.MESSAGE, message.id"))
        assertTrue(screen.contains("reports.askCheckReview(ReportTarget.MESSAGE, message.id)"))
        assertTrue("an appeal only when the decision can still be appealed", screen.contains("decision?.canAppeal == true"))
        assertTrue(screen.contains("CreateFragment.ARG_HAPPENED_OF"))
    }

    @Test
    fun `the guidelines say how private replies and plans work, without counts or read states`() {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        val text = Regex("<string name=\"guidelines_replies\">(.*?)</string>").find(xml)!!.groupValues[1]
        assertTrue(text.contains("takes turns"))
        assertTrue(text.contains("up to ten before it, never the whole conversation"))
        assertTrue(text.contains("Nobody is ever told they were turned down"))
        assertFalse(Regex("[0-9]").containsMatchIn(text))
        val layout = File("src/main/res/layout/fragment_guidelines.xml").readText(Charsets.UTF_8)
        assertTrue(layout.contains("@string/guidelines_replies"))
    }
}
