package com.safesphere.app.feature.social

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.moderation.data.remote.ContentAppealDto
import com.safesphere.app.feature.moderation.data.remote.ContentCaseDetailDto
import com.safesphere.app.feature.moderation.data.remote.ContentCaseSummaryDto
import com.safesphere.app.feature.moderation.data.remote.ContentParticipantDto
import com.safesphere.app.feature.moderation.domain.CaseContent
import com.safesphere.app.feature.moderation.domain.ContentAction
import com.safesphere.app.feature.moderation.domain.ContentAppeal
import com.safesphere.app.feature.moderation.domain.ContentCaseDetail
import com.safesphere.app.feature.moderation.domain.ContentCaseSummary
import com.safesphere.app.feature.moderation.domain.ContentDecisionReason
import com.safesphere.app.feature.moderation.domain.ContentModerationPolicy
import com.safesphere.app.feature.moderation.domain.ContentModerationRepository
import com.safesphere.app.feature.moderation.domain.ContentTarget
import com.safesphere.app.feature.moderation.ui.ContentCaseViewModel
import com.safesphere.app.feature.moderation.ui.ContentQueueViewModel
import com.safesphere.app.feature.social.data.remote.DecisionDto
import com.safesphere.app.feature.social.data.remote.MyReportDto
import com.safesphere.app.feature.social.data.remote.ReportRequestDto
import com.safesphere.app.feature.social.data.remote.RestrictionDto
import com.safesphere.app.feature.social.domain.AppealState
import com.safesphere.app.feature.social.domain.ModerationDecision
import com.safesphere.app.feature.social.domain.DecisionAction
import com.safesphere.app.feature.social.domain.PauseLevel
import com.safesphere.app.feature.social.domain.ReportReason
import com.safesphere.app.feature.social.domain.ReportTarget
import com.safesphere.app.feature.social.domain.SocialStanding
import com.safesphere.app.feature.social.domain.TimeBand
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
 * Phase 13D on the phone, on moments since N4: reports, the person's own standing and reviews, and the moderators'
 * content queue. DTOs fail closed; nothing names a reporter; impossible actions are never sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModerationV2Test {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ---- Rules mirrored from the server --------------------------------------------------------------------------

    @Test
    fun `report reasons fit their target and only Something else takes a note`() {
        assertFalse(ReportReason.IMPERSONATION in ReportReason.forTarget(ReportTarget.MOMENT))
        assertTrue(ReportReason.IMPERSONATION in ReportReason.forTarget(ReportTarget.PERSON))
        assertTrue(ReportReason.accepts(ReportTarget.MOMENT, ReportReason.SPAM, null))
        assertFalse(ReportReason.accepts(ReportTarget.MOMENT, ReportReason.SPAM, "a note"))
        assertFalse(ReportReason.accepts(ReportTarget.MOMENT, ReportReason.IMPERSONATION, null))
        assertFalse(ReportReason.accepts(ReportTarget.MOMENT, ReportReason.OTHER, "   "))
        assertTrue(ReportReason.accepts(ReportTarget.MOMENT, ReportReason.OTHER, "Shares a phone number"))
        assertFalse(ReportReason.accepts(ReportTarget.MOMENT, ReportReason.OTHER, "x".repeat(201)))
    }

    @Test
    fun `report requests carry no reporter and log no target id`() {
        val dto = ReportRequestDto("MOMENT", "moment-123", "SPAM", null)
        assertFalse(dto.toString().contains("moment-123"))
    }

    // ---- DTOs fail closed --------------------------------------------------------------------------------------------

    @Test
    fun `reports, pauses and decisions the app cannot describe are not shown with wrong words`() {
        assertNull(MyReportDto("r", "MOMENT", "SPAM", "SOMETHING", null, "THIS_WEEK").toDomain())
        assertNull(MyReportDto("r", "PLANET", "SPAM", "RECEIVED", null, "THIS_WEEK").toDomain())
        // N4: a report about a post or a perspective (kinds that no longer exist) is not shown with wrong words.
        assertNull(MyReportDto("r", "POST", "SPAM", "RECEIVED", null, "THIS_WEEK").toDomain())
        assertNull(MyReportDto("r", "COMMENT", "SPAM", "RECEIVED", null, "THIS_WEEK").toDomain())
        val reviewed = MyReportDto("r", "MOMENT", "HATE", "REVIEWED", "ACTION_TAKEN", null).toDomain()!!
        assertTrue(reviewed.reviewed && reviewed.actionTaken == true)
        assertEquals(TimeBand.EARLIER, reviewed.period)

        val pause = RestrictionDto("NEW_LEVEL", "2026-11-01T00:00:00Z", "SPAM", "d1", "WHATEVER", true).toDomain()!!
        assertEquals(PauseLevel.SUSPENDED, pause.level)
        assertEquals(AppealState.OPEN, pause.appeal)
        assertFalse("no button the server would refuse", pause.canAppeal)
        assertNull(RestrictionDto("COMMENTS_PAUSED", null, "SPAM", "d1", "NONE", true).toDomain())
        assertNull(DecisionDto("d", "PERSON_BANISH", "SPAM").toDomain())
        assertTrue(DecisionDto("d", "CONTENT_REMOVE", "SPAM", appeal = "NONE", canAppeal = true).toDomain()!!.canAppeal)
    }

    // ---- Reporting and reviews ---------------------------------------------------------------------------------------

    @Test
    fun `a report is sent once checked, and says only that a moderator will look`() = runTest {
        val reports = FakeReportRepository()
        val vm = ReportViewModel(reports)
        vm.report(ReportTarget.MOMENT, "moment-1", ReportReason.OTHER, null) // no note: not sent
        advanceUntilIdle()
        assertTrue(reports.sent.isEmpty())
        vm.report(ReportTarget.MOMENT, "c-1", ReportReason.HARASSMENT, null)
        advanceUntilIdle()
        assertEquals(listOf(FakeReportRepository.Sent(ReportTarget.MOMENT, "c-1", ReportReason.HARASSMENT, null)),
            reports.sent)
        assertEquals(ReportMessage.REPORT_SENT, vm.uiState.value.message)

        reports.reportResult = ApiResult.Failure(ApiError.TOO_MANY_ATTEMPTS, "You have sent many reports today.")
        vm.onMessageShown()
        vm.report(ReportTarget.PERSON, "person-1", ReportReason.IMPERSONATION, null)
        advanceUntilIdle()
        assertNull(vm.uiState.value.message)
        assertEquals(ApiError.TOO_MANY_ATTEMPTS, vm.uiState.value.failure!!.error)
    }

    @Test
    fun `asking for a review reads the standing again`() = runTest {
        val reports = FakeReportRepository()
        val decision = ModerationDecision("d1", DecisionAction.CONTENT_REMOVE, null, "post-1", TimeBand.THIS_WEEK,
            AppealState.NONE, true)
        reports.standing = ApiResult.Success(SocialStanding(null, listOf(decision)))
        val vm = ReportViewModel(reports)
        vm.loadStanding()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.standing!!.decisionFor("d1")!!.canAppeal)
        reports.standing = ApiResult.Success(SocialStanding(null,
            listOf(decision.copy(appeal = AppealState.OPEN, canAppeal = false))))
        vm.appeal("d1")
        advanceUntilIdle()
        assertEquals(listOf("d1"), reports.appeals)
        assertEquals(ReportMessage.REVIEW_ASKED, vm.uiState.value.message)
        assertEquals(AppealState.OPEN, vm.uiState.value.standing!!.decisionFor("d1")!!.appeal)
    }

    // ---- Moderators ------------------------------------------------------------------------------------------------

    private fun summary(tookPart: Boolean = false, top: ContentDecisionReason? = ContentDecisionReason.HARASSMENT) =
        ContentCaseSummary("case-1", ContentTarget.MOMENT, true, null, null, top, emptyMap(), 1, 1, "NONE", tookPart)

    private fun detail(tookPart: Boolean = false, actions: List<ContentAction> = ContentAction.entries.toList()) =
        ContentCaseDetail(summary(tookPart), CaseContent(kind = "SAY", audience = "COMMUNITY", body = "Text"),
            emptyList(), null, actions, emptyList())

    @Test
    fun `decision reasons mirror the server`() {
        assertEquals(listOf(ContentDecisionReason.NO_VIOLATION), ContentModerationPolicy.reasonsFor(ContentAction.CONTENT_KEEP))
        assertTrue(ContentDecisionReason.NEEDS_REVIEW in ContentModerationPolicy.reasonsFor(ContentAction.CONTENT_HOLD))
        assertFalse(ContentDecisionReason.NEEDS_REVIEW in ContentModerationPolicy.reasonsFor(ContentAction.CONTENT_REMOVE))
        assertFalse(ContentDecisionReason.NO_VIOLATION in ContentModerationPolicy.reasonsFor(ContentAction.PERSON_SUSPEND))
        assertTrue(ContentModerationPolicy.actionsFor(detail(tookPart = true)).isEmpty())
        assertEquals(ContentDecisionReason.HARASSMENT, ContentModerationPolicy.defaultReason(ContentAction.CONTENT_REMOVE, detail()))
        assertEquals(ContentDecisionReason.NO_VIOLATION, ContentModerationPolicy.defaultReason(ContentAction.CONTENT_KEEP, detail()))
        assertFalse(ContentModerationPolicy.needsConfirmation(ContentAction.CONTENT_KEEP))
        assertTrue(ContentModerationPolicy.needsConfirmation(ContentAction.PERSON_PAUSE_POSTING))
    }

    @Test
    fun `moderator DTOs fail closed`() {
        assertTrue(ContentCaseSummaryDto(caseId = "c", targetKind = "MOMENT").toDomain()!!.youTookPart)
        assertNull(ContentCaseSummaryDto(caseId = "c", targetKind = "PLANET").toDomain())
        val read = ContentCaseDetailDto(summary = ContentCaseSummaryDto(caseId = "c", targetKind = "MOMENT",
            youTookPart = false), possibleActions = listOf("CONTENT_REMOVE", "DELETE_ACCOUNT")).toDomain()!!
        assertEquals(listOf(ContentAction.CONTENT_REMOVE), read.possibleActions)
        assertNull(ContentAppealDto(appealId = "a", action = "SOMETHING").toDomain())
        assertTrue(ContentAppealDto(appealId = "a", action = "CONTENT_REMOVE").toDomain()!!.youDecided)
        assertFalse(ContentParticipantDto(ref = "3f9a1c2b4d5e", role = "REPORTER", note = "my number").toString()
            .contains("3f9a1c2b4d5e"))
    }

    private class FakeContent(var detail: ApiResult<ContentCaseDetail>) : ContentModerationRepository {
        val acted = mutableListOf<Pair<ContentAction, ContentDecisionReason>>()
        var cases: ApiResult<List<ContentCaseSummary>> = ApiResult.Success(emptyList())
        var appeals: ApiResult<List<ContentAppeal>> = ApiResult.Success(emptyList())
        val decided = mutableListOf<Pair<String, Boolean>>()
        override suspend fun openCases() = cases
        override suspend fun caseDetail(caseId: String) = detail
        override suspend fun act(caseId: String, action: ContentAction, reason: ContentDecisionReason): ApiResult<ContentCaseDetail> {
            acted += action to reason
            return detail
        }
        override suspend fun openAppeals() = appeals
        override suspend fun decideAppeal(appealId: String, overturn: Boolean): ApiResult<ContentAppeal> {
            decided += appealId to overturn
            return ApiResult.Success(ContentAppeal(appealId, false, null, ContentAction.CONTENT_REMOVE, null, null, null, false))
        }
        override fun photoUrl(caseId: String) = "http://test/$caseId/media"
        override fun personPhotoUrl(caseId: String) = "http://test/$caseId/person-photo"
    }

    @Test
    fun `only offered decisions with fitting reasons are sent`() = runTest {
        val repository = FakeContent(ApiResult.Success(detail(actions = listOf(ContentAction.CONTENT_KEEP))))
        val vm = ContentCaseViewModel(repository, "case-1")
        vm.load()
        advanceUntilIdle()
        vm.onAct(ContentAction.CONTENT_REMOVE, ContentDecisionReason.SPAM)   // not offered
        vm.onAct(ContentAction.CONTENT_KEEP, ContentDecisionReason.SPAM)     // wrong reason
        advanceUntilIdle()
        assertTrue(repository.acted.isEmpty())
        vm.onAct(ContentAction.CONTENT_KEEP, ContentDecisionReason.NO_VIOLATION)
        advanceUntilIdle()
        assertEquals(listOf(ContentAction.CONTENT_KEEP to ContentDecisionReason.NO_VIOLATION), repository.acted)
        assertTrue(vm.uiState.value.decided)
    }

    @Test
    fun `a 403 hides the case and the queue`() = runTest {
        val forbidden = ApiResult.Failure(ApiError.FORBIDDEN, "You are part of this case")
        val repository = FakeContent(forbidden)
        val vm = ContentCaseViewModel(repository, "case-1")
        vm.load()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.notAllowed)
        assertNull(vm.uiState.value.detail)

        repository.cases = ApiResult.Success(listOf(summary()))
        val queue = ContentQueueViewModel(repository)
        queue.load()
        advanceUntilIdle()
        assertTrue(queue.uiState.value.allowed)
        repository.cases = forbidden
        queue.load()
        advanceUntilIdle()
        assertFalse(queue.uiState.value.allowed)
        assertTrue(queue.uiState.value.cases.isEmpty())
    }

    @Test
    fun `nobody decides the review of their own decision`() = runTest {
        val repository = FakeContent(ApiResult.Success(detail()))
        val queue = ContentQueueViewModel(repository)
        queue.onDecideAppeal(ContentAppeal("a1", true, null, ContentAction.CONTENT_REMOVE, null, null, null, true), true)
        queue.onDecideAppeal(ContentAppeal("a2", true, null, ContentAction.CONTENT_REMOVE, null, null, null, false), true)
        advanceUntilIdle()
        assertEquals(listOf("a2" to true), repository.decided)
    }

    // ---- Words -----------------------------------------------------------------------------------------------------

    @Test
    fun `the person concerned is never told about reports, and moderation is never red`() {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        val strings = Regex("<string name=\"([a-z0-9_]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
        val authorFacing = strings.filterKeys {
            it.startsWith("own_") || it.startsWith("standing_") || it.startsWith("review_") ||
                it.startsWith("moment_notice_") || it.startsWith("moment_life_")
        }
        assertTrue("13D author-facing strings not found", authorFacing.size >= 12)
        // "reporting" (what they may still do) is fine; "report(s)" / "reported" (about them) is not.
        val problems = authorFacing.filter { (_, text) ->
            Regex("\\breports?\\b|\\breported\\b").containsMatchIn(text.lowercase())
        }
        assertTrue("mentions reports to the person concerned: ${problems.keys}", problems.isEmpty())
        assertTrue(strings.getValue("report_sent").contains("Nobody is told who reported"))
        assertTrue(strings.getValue("my_reports_intro").contains("Nobody is told who reported"))
        assertTrue(strings.getValue("standing_unaffected").contains("safety tools are not affected"))
        assertTrue(strings.getValue("content_case_guidance").contains("not proof"))
        // Emulator finding: the moderation screen's intro must not claim cases are never about people any more.
        assertFalse(strings.getValue("moderation_intro").contains("never about people"))
        // Moderation feedback uses the amber notice only (never the emergency red).
        for (layout in listOf("fragment_moment.xml", "item_moment_card.xml", "fragment_today.xml", "fragment_you.xml",
                "fragment_connections.xml", "fragment_content_case.xml")) {
            val text = File("src/main/res/layout/$layout").readText(Charsets.UTF_8)
            assertFalse("$layout uses red", text.contains("emergency_red") || text.contains("colorError"))
        }
    }

    @Test
    fun `moderator words describe content and accuse nobody`() {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        val texts = Regex("<string name=\"(content_[a-z_]+)\">(.*?)</string>").findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2].lowercase() }
        assertTrue(texts.size >= 40)
        val forbidden = listOf("fake", "fraud", "liar", "criminal", "guilty", "abuser", "troll", "predator", "pervert",
            "dangerous", "suspicious", "ban", "banned")
        val problems = texts.flatMap { (name, text) ->
            forbidden.filter { Regex("\\b$it\\b").containsMatchIn(text) }.map { "$name: $it" }
        }
        assertTrue(problems.joinToString(), problems.isEmpty())
    }
}
