package com.safesphere.app.feature.moments

import com.safesphere.app.R
import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.MEMBERSHIP_NEEDED
import com.safesphere.app.feature.moderation.data.RemoteContentModerationRepository
import com.safesphere.app.feature.moderation.data.remote.AuthorViewDto
import com.safesphere.app.feature.moderation.data.remote.CasePhotoDto
import com.safesphere.app.feature.moderation.data.remote.ContentCaseSummaryDto
import com.safesphere.app.feature.moderation.data.remote.ContentModerationApi
import com.safesphere.app.feature.moderation.data.remote.ContentParticipantDto
import com.safesphere.app.feature.moderation.data.remote.ContentViewDto
import com.safesphere.app.feature.moderation.data.remote.PersonInfoDto
import com.safesphere.app.feature.moderation.domain.CaseContent
import com.safesphere.app.feature.moderation.domain.ContentAction
import com.safesphere.app.feature.moderation.domain.ContentCaseSummary
import com.safesphere.app.feature.moderation.domain.ContentDecisionReason
import com.safesphere.app.feature.moderation.domain.ContentModerationPolicy
import com.safesphere.app.feature.moderation.domain.ContentTarget
import com.safesphere.app.feature.moderation.domain.MomentCaseText
import com.safesphere.app.feature.moderation.ui.ContentModerationTexts
import com.safesphere.app.feature.moments.data.QuestionAdminDto
import com.safesphere.app.feature.moments.data.RemoteQuestionAdminRepository
import com.safesphere.app.feature.moments.data.QuestionAdminApi
import com.safesphere.app.feature.moments.data.QuestionRequestDto
import com.safesphere.app.feature.moments.domain.QuestionAdminRepository
import com.safesphere.app.feature.moments.domain.QuestionRules
import com.safesphere.app.feature.moments.domain.ScheduledQuestion
import com.safesphere.app.feature.moments.ui.QuestionsAdminViewModel
import com.safesphere.app.feature.social.data.RemoteReportRepository
import com.safesphere.app.feature.social.data.remote.MyReportDto
import com.safesphere.app.feature.social.data.remote.ReportApi
import com.safesphere.app.feature.social.data.remote.ReportReceivedDto
import com.safesphere.app.feature.social.data.remote.ReportRequestDto
import com.safesphere.app.feature.social.data.remote.ReviewRequestDto
import com.safesphere.app.feature.social.data.remote.StandingDto
import com.safesphere.app.feature.social.domain.ReportReason
import com.safesphere.app.feature.social.domain.ReportTarget
import com.safesphere.app.feature.social.ui.ReportMessage
import com.safesphere.app.feature.social.ui.ReportViewModel
import com.safesphere.app.testing.FakeReportRepository
import com.safesphere.app.testing.FakeSessionStorage
import com.safesphere.app.testing.MainDispatcherRule
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase N4 (A3): reporting a moment, "This is about me", the author's review and appeal, the moderator's moment case,
 * and the admins' daily questions.
 */
class MomentModerationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val id = "11111111-2222-3333-4444-555555555555"

    // ---- members: report, "This is about me" ----

    @Test
    fun `a moment can be reported with any reason but impersonation, and about me reaches the repository`() = runTest {
        assertFalse(ReportReason.IMPERSONATION in ReportReason.forTarget(ReportTarget.MOMENT))
        val reports = FakeReportRepository()
        val vm = ReportViewModel(reports)
        vm.report(ReportTarget.MOMENT, id, ReportReason.HARASSMENT, null)
        advanceUntilIdle()
        assertEquals(ReportTarget.MOMENT, reports.sent.single().target)
        assertEquals(ReportMessage.REPORT_SENT, vm.uiState.value.message)
        vm.onMessageShown()
        vm.aboutMe(id)
        advanceUntilIdle()
        assertEquals(listOf(id), reports.aboutMe)
        assertEquals(ReportMessage.ABOUT_ME_SENT, vm.uiState.value.message)
    }

    @Test
    fun `a refused about me keeps the server's words (the daily limit) and says nothing was sent`() = runTest {
        val reports = FakeReportRepository().apply {
            aboutMeResult = ApiResult.Failure(ApiError.TOO_MANY_ATTEMPTS, "You have sent many reports today.")
        }
        val vm = ReportViewModel(reports)
        vm.aboutMe(id)
        advanceUntilIdle()
        assertNull(vm.uiState.value.message)
        assertEquals("You have sent many reports today.", vm.uiState.value.failure?.message)
    }

    private class FakeReportApi : ReportApi {
        val aboutMe = ArrayList<String>()
        override suspend fun report(body: ReportRequestDto) = ReportReceivedDto("RECEIVED")
        override suspend fun mine(): List<MyReportDto> = emptyList()
        override suspend fun standing() = StandingDto()
        override suspend fun appeal(decisionId: String) = ReportReceivedDto("RECEIVED")
        override suspend fun aboutMe(momentId: String): ReportReceivedDto {
            aboutMe += momentId
            return ReportReceivedDto("RECEIVED")
        }
        override suspend fun reviewCheck(body: ReviewRequestDto) = ReportReceivedDto("RECEIVED")
    }

    @Test
    fun `only a moment id ever reaches the about-me address`() = runTest {
        val api = FakeReportApi()
        val repository = RemoteReportRepository(api, ApiCallHandler(FakeSessionStorage()))
        assertEquals(ApiResult.Failure(ApiError.NOT_FOUND), repository.aboutMe("../reports"))
        assertTrue(api.aboutMe.isEmpty())
        assertEquals(ApiResult.Success(Unit), repository.aboutMe(id))
        assertEquals(listOf(id), api.aboutMe)
    }

    // ---- moderators: a moment case ----

    @Test
    fun `a moment case reads as a moment, and what is not understood is left out`() {
        val content = ContentViewDto(kind = "WHOS_IN", audience = "MY_PEOPLE", body = "Chai?", planAt = "2026-11-17T12:30:00Z",
            planWhere = "Main gate", question = "Best chai?", photo = CasePhotoDto(1200, 900)).toDomain()
        assertEquals("WHOS_IN", content.kind)
        assertEquals("MY_PEOPLE", content.audience)
        assertEquals(Instant.parse("2026-11-17T12:30:00Z"), content.planAt)
        assertEquals(1200, content.photo?.width)
        val odd = ContentViewDto(kind = "POST", audience = "PUBLIC", planAt = "yesterday", planWhere = " ",
            photo = CasePhotoDto(0, 10)).toDomain()
        assertNull(odd.kind)
        assertNull(odd.audience)
        assertNull(odd.planAt)
        assertNull(odd.planWhere)
        assertNull(odd.photo)
        val person = ContentViewDto(person = PersonInfoDto("Asha Raman", true, "asha", null, "BTECH_IT", 2027)).toDomain().person!!
        assertEquals("BTECH_IT", person.programme)
        assertEquals(2027, person.graduationYear)
        val badPerson = ContentViewDto(person = PersonInfoDto("A", false, "a", null, "<b>x</b>", 3000)).toDomain().person!!
        assertNull(badPerson.programme)
        assertNull(badPerson.graduationYear)
    }

    @Test
    fun `This is about me cases, new-member photo holds and waiting time reach the moderator`() {
        val aboutMe = ContentCaseSummaryDto(caseId = "c1", targetKind = "MOMENT", status = "OPEN", topReason = "ABOUT_ME",
            openedBy = "ABOUT_ME", waitingHours = 13).toDomain()!!
        assertEquals("ABOUT_ME", aboutMe.openedBy)
        assertEquals("ABOUT_ME", aboutMe.topReasonCode)
        assertNull("not a decision reason", aboutMe.topReason)
        assertEquals(13, aboutMe.waitingHours)
        assertEquals("ABOUT_ME", MomentCaseText.headlineReason(aboutMe))
        val photo = ContentCaseSummaryDto(caseId = "c2", targetKind = "MOMENT", topReason = "NEW_MEMBER_PHOTO",
            openedBy = "SYSTEM", waitingHours = -4).toDomain()!!
        assertEquals("NEW_MEMBER_PHOTO", MomentCaseText.headlineReason(photo))
        assertEquals(0, photo.waitingHours)
        val unknown = ContentCaseSummaryDto(caseId = "c3", targetKind = "MOMENT", topReason = "SOMETHING_NEW",
            openedBy = "ROBOT").toDomain()!!
        assertEquals("REPORTS", unknown.openedBy)
        assertNull(MomentCaseText.headlineReason(unknown))
        assertEquals(R.string.content_reason_about_me, ContentModerationTexts.reasonCode("ABOUT_ME"))
        assertEquals(R.string.content_reason_new_member_photo, ContentModerationTexts.reasonCode("NEW_MEMBER_PHOTO"))
        assertEquals(R.string.report_reason_threat, ContentModerationTexts.reasonCode("THREAT"))
        assertEquals(R.string.moderation_reason_unknown, ContentModerationTexts.reasonCode(null))
    }

    @Test
    fun `the person who said about me is marked, and the author's new-member end is read`() {
        val p = ContentParticipantDto(ref = "Person a1", role = "REPORTER", reason = "ABOUT_ME").toDomain()!!
        assertTrue(p.aboutMe)
        assertFalse(ContentParticipantDto(ref = "Person b2", role = "REPORTER", reason = "HATE").toDomain()!!.aboutMe)
        val author = AuthorViewDto(ref = "Person c3", newMemberUntil = "2026-11-20T10:00:00Z").toDomain()!!
        assertEquals(Instant.parse("2026-11-20T10:00:00Z"), author.newMemberUntil)
    }

    @Test
    fun `the moderator never gets about-me or new-member codes as a decision reason`() {
        for (action in ContentAction.entries) {
            val names = ContentModerationPolicy.reasonsFor(action).map { it.name }
            assertFalse(action.name, "ABOUT_ME" in names || "NEW_MEMBER_PHOTO" in names || "AWAITING_CHECK" in names)
        }
        assertFalse(ContentDecisionReason.entries.any { it.name == "ABOUT_ME" })
    }

    @Test
    fun `the check's phrases are found in the same text the server checked (words, then where)`() {
        assertEquals("Chai?\nMain gate", MomentCaseText.checkedText(CaseContent(body = "Chai?", planWhere = "Main gate")))
        assertEquals("Chai?", MomentCaseText.checkedText(CaseContent(body = "Chai?")))
        assertEquals("Main gate", MomentCaseText.checkedText(CaseContent(planWhere = "Main gate")))
        assertEquals("", MomentCaseText.checkedText(CaseContent()))
        val server = File("../../backend/src/main/java/com/safesphere/backend/social/event/MomentShared.java").readText()
        assertTrue("mirrors MomentShared.words", server.contains("body + \"\\n\" + planWhere"))
    }

    @Test
    fun `the moment photo is fetched through the case, never by a moment address`() {
        val repository = RemoteContentModerationRepository(
            java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ContentModerationApi::class.java)) { _, _, _ ->
                error("no call")
            } as ContentModerationApi, ApiCallHandler(FakeSessionStorage()), "https://api.example.test/")
        assertEquals("https://api.example.test/api/moderation/content-cases/c1/media", repository.photoUrl("c1"))
    }

    @Test
    fun `a moment summary keeps the 13D defaults (took part = read only)`() {
        val s: ContentCaseSummary = ContentCaseSummaryDto(caseId = "c1", targetKind = "MOMENT").toDomain()!!
        assertEquals(ContentTarget.MOMENT, s.target)
        assertTrue(s.youTookPart)
    }

    // ---- admins: daily questions ----

    @Test
    fun `question days are India days, today to 60 days ahead`() {
        // 20:00 UTC on 17 Nov is 01:30 on 18 Nov in India.
        val now = Instant.parse("2026-11-17T20:00:00Z")
        assertEquals(LocalDate.of(2026, 11, 18), QuestionRules.today(now))
        assertFalse(QuestionRules.dayAllowed(LocalDate.of(2026, 11, 17), now))
        assertTrue(QuestionRules.dayAllowed(LocalDate.of(2026, 11, 18), now))
        assertTrue(QuestionRules.dayAllowed(LocalDate.of(2026, 11, 18).plusDays(60), now))
        assertFalse(QuestionRules.dayAllowed(LocalDate.of(2026, 11, 18).plusDays(61), now))
    }

    @Test
    fun `a question is 3 to 140 characters on one line`() {
        assertFalse(QuestionRules.textFits(" ab "))
        assertTrue(QuestionRules.textFits("abc"))
        assertTrue(QuestionRules.textFits("a".repeat(140)))
        assertFalse(QuestionRules.textFits("a".repeat(141)))
        assertFalse(QuestionRules.textFits("What made\nyou smile?"))
        assertTrue(QuestionRules.textFits("உங்கள் நாள் எப்படி?"))
    }

    @Test
    fun `an admin row the app cannot read is left out`() {
        assertNull(QuestionAdminDto("x", "2026-11-18", "Q?").toDomain())
        assertNull(QuestionAdminDto(id, "18/11/2026", "Q?").toDomain())
        assertNull(QuestionAdminDto(id, "2026-11-18", "  ").toDomain())
        assertEquals(ScheduledQuestion(id, LocalDate.of(2026, 11, 18), "Q?"), QuestionAdminDto(id, "2026-11-18", " Q? ").toDomain())
    }

    private class FakeQuestions : QuestionAdminRepository {
        var listResult: ApiResult<List<ScheduledQuestion>> = ApiResult.Success(emptyList())
        var scheduleResult: ApiResult<ScheduledQuestion>? = null
        var deleteResult: ApiResult<Unit> = ApiResult.Success(Unit)
        val scheduled = ArrayList<Pair<LocalDate, String>>()
        val deleted = ArrayList<String>()
        override suspend fun list() = listResult
        override suspend fun schedule(day: LocalDate, text: String): ApiResult<ScheduledQuestion> {
            scheduled += day to text
            return scheduleResult ?: ApiResult.Success(ScheduledQuestion("q-${scheduled.size}", day, text.trim()))
        }
        override suspend fun delete(id: String): ApiResult<Unit> {
            deleted += id
            return deleteResult
        }
    }

    private val now = Instant.parse("2026-11-17T06:00:00Z") // 11:30 India, 17 Nov
    private val today = LocalDate.of(2026, 11, 17)

    @Test
    fun `the next free day is suggested, scheduling adds it and clears the words`() = runTest {
        val repo = FakeQuestions().apply {
            listResult = ApiResult.Success(listOf(ScheduledQuestion("a", today, "Q1?"), ScheduledQuestion("b", today.plusDays(1), "Q2?")))
        }
        val vm = QuestionsAdminViewModel(repo) { now }
        vm.load()
        advanceUntilIdle()
        assertEquals(today.plusDays(2), vm.uiState.value.day)
        assertFalse("no words yet", vm.uiState.value.canSchedule)
        vm.onText("What are you reading?")
        assertTrue(vm.uiState.value.canSchedule)
        vm.onSchedule()
        advanceUntilIdle()
        assertEquals(today.plusDays(2) to "What are you reading?", repo.scheduled.single())
        assertEquals(3, vm.uiState.value.questions.size)
        assertEquals("", vm.uiState.value.text)
        assertTrue(vm.uiState.value.scheduled)
        assertEquals(today.plusDays(3), vm.uiState.value.day)
    }

    @Test
    fun `a taken day or a day outside the window cannot be scheduled`() = runTest {
        val repo = FakeQuestions().apply { listResult = ApiResult.Success(listOf(ScheduledQuestion("a", today, "Q1?"))) }
        val vm = QuestionsAdminViewModel(repo) { now }
        vm.load()
        advanceUntilIdle()
        vm.onText("Another one?")
        vm.onDay(today)
        assertTrue(vm.uiState.value.dayTaken)
        assertFalse(vm.uiState.value.canSchedule)
        vm.onDay(today.plusDays(61))
        assertEquals("refused on the phone", today, vm.uiState.value.day)
        vm.onDay(today.minusDays(1))
        assertEquals(today, vm.uiState.value.day)
    }

    @Test
    fun `the server's 409 is kept, and a 403 says who may use the screen`() = runTest {
        val repo = FakeQuestions().apply {
            scheduleResult = ApiResult.Failure(ApiError.CONFLICT, "That day already has a question. Delete it first to write another.")
        }
        val vm = QuestionsAdminViewModel(repo) { now }
        vm.load()
        advanceUntilIdle()
        vm.onText("A question?")
        vm.onSchedule()
        advanceUntilIdle()
        assertEquals(ApiError.CONFLICT, vm.uiState.value.failure?.error)
        assertEquals("A question?", vm.uiState.value.text)
        val notAdmin = QuestionsAdminViewModel(FakeQuestions().apply { listResult = ApiResult.Failure(ApiError.FORBIDDEN) }) { now }
        notAdmin.load()
        advanceUntilIdle()
        assertTrue(notAdmin.uiState.value.notAllowed)
        val notMember = QuestionsAdminViewModel(FakeQuestions().apply {
            listResult = ApiResult.Failure(ApiError.FORBIDDEN, "Members only", MEMBERSHIP_NEEDED)
        }) { now }
        notMember.load()
        advanceUntilIdle()
        assertTrue(notMember.uiState.value.needsMembership)
        assertFalse(notMember.uiState.value.notAllowed)
    }

    @Test
    fun `deleting a question removes it, also when it was already gone`() = runTest {
        val q = ScheduledQuestion("a", today, "Q1?")
        val repo = FakeQuestions().apply { listResult = ApiResult.Success(listOf(q)) }
        val vm = QuestionsAdminViewModel(repo) { now }
        vm.load()
        advanceUntilIdle()
        repo.deleteResult = ApiResult.Failure(ApiError.NOT_FOUND)
        vm.onDelete(q)
        advanceUntilIdle()
        assertEquals(listOf("a"), repo.deleted)
        assertTrue(vm.uiState.value.questions.isEmpty())
    }

    @Test
    fun `only ids reach the admin address, and the request has exactly a day and the words`() = runTest {
        val api = object : QuestionAdminApi {
            val deleted = ArrayList<String>()
            var created: QuestionRequestDto? = null
            override suspend fun list(): List<QuestionAdminDto> = emptyList()
            override suspend fun create(body: QuestionRequestDto): QuestionAdminDto {
                created = body
                return QuestionAdminDto(id, body.day, body.text)
            }
            override suspend fun delete(id: String) {
                deleted += id
            }
        }
        val repository = RemoteQuestionAdminRepository(api, ApiCallHandler(FakeSessionStorage()))
        assertEquals(ApiResult.Failure(ApiError.NOT_FOUND), repository.delete("1 OR 1=1"))
        assertTrue(api.deleted.isEmpty())
        repository.schedule(LocalDate.of(2026, 11, 20), "  Best canteen dish?  ")
        assertEquals(QuestionRequestDto("2026-11-20", "Best canteen dish?"), api.created)
    }

    // ---- guards ----

    private fun source(path: String) = File("src/main/java/com/safesphere/app/$path").readText(Charsets.UTF_8)

    private fun string(name: String): String {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        return Regex("<string name=\"$name\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(xml)!!.groupValues[1]
    }

    @Test
    fun `This is about me asks first, is offered only on someone else's moment, and tells nobody who asked`() {
        val page = source("feature/moments/ui/MomentFragment.kt")
        assertTrue(page.contains("binding.othersBlock.isVisible = moment != null && !moment.mine"))
        assertTrue(page.contains(".setPositiveButton(R.string.moment_about_me_confirm) { _, _ -> reports.aboutMe(moment.id) }"))
        assertTrue(page.contains("ReportDialogs.show(this, ReportTarget.MOMENT)"))
        assertTrue(page.contains("secure(true)"))
        assertTrue(string("moment_about_me_message").contains("not told"))
        assertTrue(string("moment_about_me_sent").contains("Nobody is told"))
        for (name in listOf("moment_about_me", "moment_about_me_title", "moment_about_me_message", "moment_about_me_sent")) {
            assertFalse(name, string(name).contains("report", ignoreCase = true))
        }
    }

    /** N4-C walk finding: the moderator screens and the Settings row still spoke of posts, places, comments, signals. */
    @Test
    fun `moderator texts speak of moments, never of the retired posts, places, comments or signals`() {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        val names = Regex("<string name=\"((?:moderation_intro|content_cases_title|content_action_[a-z_]+|profile_moderation_description|you_more_description))\"")
            .findAll(xml).map { it.groupValues[1] }.toList()
        assertTrue(names.size >= 10)
        val retired = Regex("\\b(posts?|places?|comments?|signals?|perspectives?|posting)\\b")
        val problems = names.filter { retired.containsMatchIn(string(it).lowercase()) }
        assertTrue("retired words in: $problems", problems.isEmpty())
    }

    @Test
    fun `the moderator's moment photo shows only after a tap, the case stays screenshot-protected, the admin row only for admins`() {
        val case = source("feature/moderation/ui/ContentCaseFragment.kt")
        assertTrue(case.contains("if (MOMENT_PHOTO in state.shownPhotos)"))
        assertTrue(case.contains("FLAG_SECURE"))
        val more = source("feature/profile/ui/MoreFragment.kt")
        assertTrue(Regex("""nav\.navigate\(R\.id\.questionsAdminFragment\)\s*\}\.takeIf \{ admin \}""").containsMatchIn(more))
    }
}
