package com.safesphere.app.feature.social

import com.safesphere.app.R
import com.safesphere.app.feature.moderation.data.remote.AssessmentViewDto
import com.safesphere.app.feature.moderation.data.remote.ContentCaseSummaryDto
import com.safesphere.app.feature.moderation.data.remote.EvidenceViewDto
import com.safesphere.app.feature.social.data.remote.CommentDto
import com.safesphere.app.feature.social.data.remote.ModerationViewDto
import com.safesphere.app.feature.social.data.remote.PersonRefDto
import com.safesphere.app.feature.social.data.remote.PostDto
import com.safesphere.app.feature.social.data.remote.SpanDto
import com.safesphere.app.feature.social.domain.Collapsed
import com.safesphere.app.feature.social.domain.ModerationReason
import com.safesphere.app.feature.social.domain.ModerationSource
import com.safesphere.app.feature.social.domain.ModerationState
import com.safesphere.app.feature.social.domain.OwnModeration
import com.safesphere.app.feature.social.domain.Perspective
import com.safesphere.app.feature.social.domain.CommentKind
import com.safesphere.app.feature.social.domain.ReportTarget
import com.safesphere.app.feature.social.domain.TimeBand
import com.safesphere.app.feature.social.ui.DraftNudgeDialogs
import com.safesphere.app.feature.social.ui.ModerationLabels
import com.safesphere.app.feature.social.ui.PostUiState
import com.safesphere.app.feature.social.ui.ReportMessage
import com.safesphere.app.feature.social.ui.ReportViewModel
import com.safesphere.app.testing.FakeReportRepository
import com.safesphere.app.testing.MainDispatcherRule
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
 * Phase 13E on the phone: the automatic check's states are read carefully (fail closed), speak as "our system", never
 * accuse, never show a score; the strong-language filter is the viewer's own; the review request is sent once.
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

    @Test
    fun `own states from the check are read carefully`() {
        val system = ModerationViewDto("COLLAPSED", "HARASSMENT", null, "SYSTEM",
            listOf(SpanDto(4, 9), SpanDto(9, 4), SpanDto(null, 3), SpanDto(-1, 2))).toDomain()!!
        assertEquals(ModerationSource.SYSTEM, system.source)
        assertEquals(listOf(4 until 9), system.flagged)
        assertTrue(system.canAskCheckReview)
        // An unknown source reads as a moderator's, without phrases.
        val unknown = ModerationViewDto("COLLAPSED", "HARASSMENT", "d", "ROBOT", listOf(SpanDto(0, 3))).toDomain()!!
        assertEquals(ModerationSource.MODERATOR, unknown.source)
        assertTrue(unknown.flagged.isEmpty())
        assertFalse(unknown.canAskCheckReview)
        // A collapse by reports has no source in the author's answer: the 13D words, no review of "the check".
        val reports = ModerationViewDto("COLLAPSED", null, null, null, null).toDomain()!!
        assertFalse(reports.bySystem)
        assertEquals(R.string.own_collapsed_while_reviewed, ModerationLabels.ownNotice(reports))
        val waiting = ModerationViewDto("HELD", "AWAITING_CHECK", null, "SYSTEM", null).toDomain()!!
        assertEquals(ModerationReason.AWAITING_CHECK, waiting.reason)
        assertFalse(waiting.canAskCheckReview)
    }

    @Test
    fun `the notices speak for the system, not for a moderator`() {
        fun own(state: ModerationState, reason: ModerationReason?) =
            OwnModeration(state, reason, null, ModerationSource.SYSTEM)
        assertEquals(R.string.own_collapsed_by_system, ModerationLabels.ownNotice(own(ModerationState.COLLAPSED,
            ModerationReason.HARASSMENT)))
        assertEquals(R.string.own_held_by_system, ModerationLabels.ownNotice(own(ModerationState.HELD,
            ModerationReason.THREAT)))
        assertEquals(R.string.own_waiting_check, ModerationLabels.ownNotice(own(ModerationState.HELD,
            ModerationReason.AWAITING_CHECK)))
        assertEquals(R.string.own_held_new_photos, ModerationLabels.ownNotice(own(ModerationState.HELD,
            ModerationReason.NEEDS_REVIEW)))
        assertEquals(R.string.collapsed_by_system, ModerationLabels.collapsedLine(Collapsed.BY_SYSTEM))
        assertEquals(R.string.collapsed_strong_language, ModerationLabels.collapsedLine(null))
    }

    @Test
    fun `others see a system collapse, an unknown one stays collapsed, strong language is read`() {
        val post = PostDto(publicId = "p", type = "EXPERIENCE", own = false, collapsed = "BY_SYSTEM",
            strongLanguage = true).toDomain()!!
        assertEquals(Collapsed.BY_SYSTEM, post.collapsed)
        assertTrue(post.strongLanguage)
        assertEquals(Collapsed.UNDER_REVIEW, PostDto(publicId = "p", type = "EXPERIENCE", own = false,
            collapsed = "BY_MAGIC").toDomain()!!.collapsed)
        assertFalse(PostDto(publicId = "p", type = "EXPERIENCE").toDomain()!!.strongLanguage)
        val comment = CommentDto(publicId = "c", kind = "QUESTION", author = PersonRefDto("a", "h", "A"),
            collapsed = "BY_SYSTEM", strongLanguage = true).toDomain()!!
        assertEquals(Collapsed.BY_SYSTEM, comment.collapsed)
    }

    @Test
    fun `the strong language filter is the viewer's own and never hides their own words`() {
        val theirs = PostDto(publicId = "p", type = "EXPERIENCE", own = false, strongLanguage = true).toDomain()!!
        val mine = PostDto(publicId = "m", type = "EXPERIENCE", own = true, strongLanguage = true).toDomain()!!
        assertFalse(PostUiState(post = theirs).showCollapsed)
        assertTrue(PostUiState(post = theirs, strongLanguageFilter = true).showCollapsed)
        assertFalse(PostUiState(post = theirs, strongLanguageFilter = true, revealed = true).showCollapsed)
        assertFalse(PostUiState(post = mine, strongLanguageFilter = true).showCollapsed)
        val p = Perspective("c", CommentKind.QUESTION, null, false, "x", TimeBand.EARLIER, false, false, null, false,
            false, strongLanguage = true)
        assertTrue(PostUiState(strongLanguageFilter = true).perspectiveCollapsed(p))
        assertFalse(PostUiState(strongLanguageFilter = true, revealedPerspectives = setOf("c")).perspectiveCollapsed(p))
        assertFalse(PostUiState().perspectiveCollapsed(p))
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
        val summary = ContentCaseSummaryDto(caseId = "c", targetKind = "POST", openedBy = "ALIENS",
            contentSource = "SYSTEM").toDomain()!!
        assertEquals("REPORTS", summary.openedBy)
        assertEquals("SYSTEM", summary.contentSource)
    }

    @Test
    fun `asking for a review of a system collapse is sent once and confirmed`() = runTest {
        val reports = FakeReportRepository()
        val vm = ReportViewModel(reports)
        vm.askCheckReview(ReportTarget.POST, "p1")
        advanceUntilIdle()
        assertEquals(listOf(ReportTarget.POST to "p1"), reports.checkReviews)
        assertEquals(ReportMessage.CHECK_REVIEW_ASKED, vm.uiState.value.message)
        assertTrue("p1" in vm.uiState.value.checkReviewAsked)
    }

    @Test
    fun `the words are calm, about the text, with no score and no accusation`() {
        val ai = strings.filterKeys {
            it.startsWith("nudge_") || it.endsWith("_by_system") || it.startsWith("check_review") ||
                it == "own_waiting_check" || it == "own_held_new_photos" || it == "own_perspective_system_line" ||
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
        for (name in listOf("own_collapsed_by_system", "own_held_by_system", "collapsed_by_system", "nudge_care_message",
                "own_perspective_system_line")) {
            assertTrue("$name speaks as our system", strings.getValue(name).lowercase().contains("our system"))
        }
        assertTrue(strings.getValue("content_case_check_guidance").contains("not a verdict"))
        assertTrue(strings.getValue("content_case_opened_by_system").contains("never restricts"))
        assertTrue(strings.getValue("nudge_support_message").contains(DraftNudgeDialogs.HELPLINE))
        assertEquals("14416", DraftNudgeDialogs.HELPLINE)
        assertTrue(strings.getValue("nudge_support_message").contains("not saved"))
    }
}
