package com.safesphere.app.feature.social

import com.safesphere.app.R
import com.safesphere.app.feature.moderation.data.remote.AssessmentViewDto
import com.safesphere.app.feature.moderation.data.remote.ContentCaseSummaryDto
import com.safesphere.app.feature.moderation.data.remote.EvidenceViewDto
import com.safesphere.app.feature.moments.data.remote.MomentAuthorDto
import com.safesphere.app.feature.moments.data.remote.MomentDto
import com.safesphere.app.feature.moments.data.remote.NoticeDto
import com.safesphere.app.feature.moments.domain.MomentNotice
import com.safesphere.app.feature.moments.domain.NoticeState
import com.safesphere.app.feature.moments.ui.MomentCardBinder
import com.safesphere.app.feature.moments.ui.MomentLabels
import com.safesphere.app.feature.social.domain.ReportTarget
import com.safesphere.app.feature.social.ui.DraftNudgeDialogs
import com.safesphere.app.feature.social.ui.ReportMessage
import com.safesphere.app.feature.social.ui.ReportViewModel
import com.safesphere.app.testing.FakeReportRepository
import com.safesphere.app.testing.MainDispatcherRule
import com.safesphere.app.testing.moment
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase 13E on the phone, on moments since N4: the automatic check's states are read carefully (fail closed), speak as
 * "our system", never accuse, never show a score; a covered moment stays behind a tap for others and never for its
 * author; the review request is sent once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiModerationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val strings: Map<String, String> by lazy {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        Regex("<string name=\"([a-z0-9_]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun own(notice: NoticeDto) = MomentDto(id = "0a0a0a0a-0000-4000-8000-000000000001", kind = "SAY",
        audience = "COMMUNITY", author = MomentAuthorDto(name = "Asha"), mine = true, text = "words", status = "LIVE",
        notice = notice).toDomain()!!.notice!!

    @Test
    fun `own states from the check are read carefully`() {
        val system = own(NoticeDto("COLLAPSED", "HARASSMENT", "SYSTEM", true, null))
        assertEquals(false, system.byPerson)
        assertTrue(system.reviewable)
        // An unknown source is not said to be anyone's; an unknown state is the most cautious one.
        val unknown = own(NoticeDto("SOMETHING_NEW", "HARASSMENT", "ROBOT", null, "not-an-id"))
        assertEquals(NoticeState.HELD, unknown.state)
        assertNull(unknown.byPerson)
        assertFalse(unknown.reviewable)
        assertNull(unknown.decisionId)
        // A reason that is not a code never reaches the screen.
        assertNull(own(NoticeDto("HELD", "<b>you are</b>", "SYSTEM", null, null)).reason)
        // A collapse by reports has no reason and no source: plain words, nobody named.
        val reports = own(NoticeDto("COLLAPSED", null, null, false, null))
        assertEquals(R.string.moment_notice_collapsed, MomentLabels.notice(reports))
    }

    @Test
    fun `the notices speak for the system, not for a moderator`() {
        fun notice(state: NoticeState, reason: String?, byPerson: Boolean? = false) =
            MomentLabels.notice(MomentNotice(state, reason, byPerson, false, null))
        assertEquals(R.string.own_collapsed_by_system, notice(NoticeState.COLLAPSED, "HARASSMENT"))
        assertEquals(R.string.own_held_by_system, notice(NoticeState.HELD, "THREAT"))
        assertEquals(R.string.own_waiting_check, notice(NoticeState.HELD, "AWAITING_CHECK"))
        assertEquals(R.string.moment_notice_new_member_photo, notice(NoticeState.HELD, "NEW_MEMBER_PHOTO"))
        // "This is about me" and a moderator's closer look read the same: no reason, nobody named.
        assertEquals(R.string.moment_notice_held, notice(NoticeState.HELD, "NEEDS_REVIEW"))
        assertEquals(R.string.moment_notice_held, notice(NoticeState.HELD, "NEEDS_REVIEW", byPerson = true))
        // A moderator's decision is said to be a moderator's, with its reason.
        assertEquals(R.string.own_collapsed_by_moderator, notice(NoticeState.COLLAPSED, "SPAM", byPerson = true))
        assertEquals(R.string.own_held, notice(NoticeState.HELD, "SPAM", byPerson = true))
        assertEquals(R.string.own_removed, notice(NoticeState.REMOVED, "HATE", byPerson = true))
        // A code this app does not know gets the plain sentence, never a guessed reason.
        assertEquals(R.string.moment_notice_held, notice(NoticeState.HELD, "SOMETHING_NEW"))
        assertEquals(R.string.moderation_reason_unknown, MomentLabels.reason("SOMETHING_NEW"))
        assertEquals(R.string.report_reason_harassment, MomentLabels.reason("HARASSMENT"))
    }

    @Test
    fun `a covered moment stays behind a tap for others and is never covered for its author`() {
        val theirs = moment("m1", collapsed = true)
        assertTrue(MomentCardBinder.covered(theirs, open = false))
        assertFalse(MomentCardBinder.covered(theirs, open = true))
        assertFalse(MomentCardBinder.covered(moment("m2", mine = true, collapsed = true), open = false))
        assertFalse(MomentCardBinder.covered(moment("m3"), open = false))
    }

    @Test
    fun `moderators see the check as evidence, unknown codes dropped, never self-harm`() {
        val check = AssessmentViewDto("DONE", "PUBLISH_COLLAPSED", "TARGETED", listOf(
            EvidenceViewDto("HARASSMENT", 2, true, false, 3, 8), EvidenceViewDto("SELF_HARM", 1, false, false, 0, 1),
            EvidenceViewDto("MIND_READING", 3, true, false, 0, 1), EvidenceViewDto("THREAT", 9, true, true, 5, 2)),
            "norm-1", "lex-1", "-", "policy-1").toDomain()!!
        assertEquals(listOf("HARASSMENT", "THREAT"), check.findings.map { it.category })
        assertEquals(3 until 8, check.findings[0].span)
        assertEquals(3, check.findings[1].severity)
        assertNull(check.findings[1].span)
        assertEquals("norm-1, lex-1, policy-1", check.versions)
        assertNull(AssessmentViewDto(rule = "CLEAN").toDomain())
        val summary = ContentCaseSummaryDto(caseId = "c", targetKind = "MOMENT", openedBy = "ALIENS",
            contentSource = "SYSTEM").toDomain()!!
        assertEquals("REPORTS", summary.openedBy)
        assertEquals("SYSTEM", summary.contentSource)
    }

    @Test
    fun `asking for a review of a system collapse is sent once and confirmed`() = runTest {
        val reports = FakeReportRepository()
        val vm = ReportViewModel(reports)
        vm.askCheckReview(ReportTarget.MOMENT, "m1")
        advanceUntilIdle()
        assertEquals(listOf(ReportTarget.MOMENT to "m1"), reports.checkReviews)
        assertEquals(ReportMessage.CHECK_REVIEW_ASKED, vm.uiState.value.message)
        assertTrue("m1" in vm.uiState.value.checkReviewAsked)
    }

    @Test
    fun `the words are calm, about the text, with no score and no accusation`() {
        val ai = strings.filterKeys {
            it.startsWith("nudge_") || it.endsWith("_by_system") || it.startsWith("check_review") ||
                it == "own_waiting_check" || it.startsWith("moment_notice_") ||
                it.startsWith("content_case_check") || it.startsWith("content_case_opened_by")
        }
        assertTrue("13E strings not found: ${ai.keys}", ai.size >= 20)
        for ((name, text) in ai) {
            val lower = text.lowercase()
            for (forbidden in listOf("you are", "you're a", "score", "confidence", "percent", "%)", "probability",
                    "algorithm", "ai thinks", "toxic", "abuser", "banned", "punish")) {
                assertFalse("$name says \"$forbidden\"", lower.contains(forbidden))
            }
        }
        for (name in listOf("own_collapsed_by_system", "own_held_by_system", "nudge_care_message")) {
            assertTrue("$name speaks as our system", strings.getValue(name).lowercase().contains("our system"))
        }
        assertTrue(strings.getValue("content_case_check_guidance").contains("not a verdict"))
        assertTrue(strings.getValue("content_case_opened_by_system").contains("never restricts"))
        assertTrue(strings.getValue("nudge_support_message").contains(DraftNudgeDialogs.HELPLINE))
        assertEquals("14416", DraftNudgeDialogs.HELPLINE)
        assertTrue(strings.getValue("nudge_support_message").contains("not saved"))
    }
}
