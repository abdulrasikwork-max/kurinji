package com.safesphere.app.feature.moderation

import com.safesphere.app.R
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.community.domain.AppealStatus
import com.safesphere.app.feature.community.domain.ContributionKind
import com.safesphere.app.feature.community.domain.ModerationReason
import com.safesphere.app.feature.moderation.data.remote.ActionRequestDto
import com.safesphere.app.feature.moderation.data.remote.AppealDto
import com.safesphere.app.feature.moderation.data.remote.CaseDetailDto
import com.safesphere.app.feature.moderation.data.remote.CaseSummaryDto
import com.safesphere.app.feature.moderation.data.remote.CoordinationDto
import com.safesphere.app.feature.moderation.data.remote.ParticipantDto
import com.safesphere.app.feature.moderation.domain.CaseAction
import com.safesphere.app.feature.moderation.domain.CaseDetail
import com.safesphere.app.feature.moderation.domain.CaseOrigin
import com.safesphere.app.feature.moderation.domain.CaseStatus
import com.safesphere.app.feature.moderation.domain.ContributorTier
import com.safesphere.app.feature.moderation.domain.CoordinationPattern
import com.safesphere.app.feature.moderation.domain.CoordinationReason
import com.safesphere.app.feature.moderation.domain.GroupPlaces
import com.safesphere.app.feature.moderation.domain.GroupSize
import com.safesphere.app.feature.moderation.domain.ModerationAccess
import com.safesphere.app.feature.moderation.domain.ModerationPolicy
import com.safesphere.app.feature.moderation.domain.SignalModeration
import com.safesphere.app.feature.moderation.ui.CaseEvent
import com.safesphere.app.feature.moderation.ui.CaseState
import com.safesphere.app.feature.moderation.ui.ModerationCaseViewModel
import com.safesphere.app.feature.moderation.ui.ModerationEvent
import com.safesphere.app.feature.moderation.ui.ModerationTexts
import com.safesphere.app.feature.moderation.ui.ModerationViewModel
import com.safesphere.app.feature.moderation.ui.QueueState
import com.safesphere.app.feature.profile.ui.ProfileViewModel
import com.safesphere.app.testing.FakeAuthRepository
import com.safesphere.app.testing.FakeModerationRepository
import com.safesphere.app.testing.MainDispatcherRule
import com.safesphere.app.testing.appeal
import com.safesphere.app.testing.caseDetail
import com.safesphere.app.testing.caseSummary
import com.safesphere.app.testing.participant
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

/** Phase 7 moderator screens on the phone: access gate, queue, case decisions, appeals, safe defaults. */
@OptIn(ExperimentalCoroutinesApi::class)
class ModerationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeModerationRepository()

    // ---- Policy ----------------------------------------------------------------------------------------------------

    @Test
    fun `reasons offered match what the server accepts, with no free text`() {
        assertEquals(listOf(ModerationReason.NO_VIOLATION), ModerationPolicy.reasonsFor(CaseAction.DISMISS))
        assertTrue(ModerationReason.DUPLICATE in ModerationPolicy.reasonsFor(CaseAction.REMOVE))
        assertFalse(ModerationReason.DUPLICATE in ModerationPolicy.reasonsFor(CaseAction.RESTRICT))
        assertTrue(ModerationReason.NEEDS_REVIEW in ModerationPolicy.reasonsFor(CaseAction.QUARANTINE))
    }

    @Test
    fun `no decision is offered on a case the moderator took part in, or on a closed case`() {
        assertEquals(listOf(CaseAction.QUARANTINE, CaseAction.DISMISS, CaseAction.REMOVE),
            ModerationPolicy.availableActions(caseDetail()))
        assertTrue(ModerationPolicy.availableActions(caseDetail(youTookPart = true)).isEmpty())
        assertTrue(ModerationPolicy.availableActions(caseDetail(caseSummary(youTookPart = true))).isEmpty())
        assertTrue(ModerationPolicy.availableActions(caseDetail(caseSummary(status = CaseStatus.CLOSED))).isEmpty())
        assertEquals("an already hidden signal is not hidden twice", listOf(CaseAction.DISMISS, CaseAction.REMOVE),
            ModerationPolicy.availableActions(caseDetail(caseSummary(moderation = SignalModeration.QUARANTINED))))
        assertFalse(ModerationPolicy.canRestrict(caseDetail(youTookPart = true), participant()))
        assertFalse(ModerationPolicy.canRestrict(caseDetail(), participant(tier = ContributorTier.RESTRICTED)))
    }

    @Test
    fun `nobody decides the appeal of their own restriction`() {
        assertTrue(ModerationPolicy.canDecide(appeal()))
        assertFalse(ModerationPolicy.canDecide(appeal(mine = true)))
    }

    @Test
    fun `participants are read reporters first, flaggers last`() {
        val ordered = ModerationPolicy.ordered(listOf(participant(role = null), participant(role = ContributionKind.CONFIRM),
            participant(role = ContributionKind.REPORT)))
        assertEquals(listOf(ContributionKind.REPORT, ContributionKind.CONFIRM, null), ordered.map { it.role })
    }

    // ---- DTOs: unknown means "offer nothing" -------------------------------------------------------------------------

    @Test
    fun `missing conflict flags from the server mean no decision is offered`() {
        val summary = CaseSummaryDto("c1", "FLAGS", "OPEN", null, "2026-10-20", "HARASSMENT", "NONE",
            mapOf("TARGETS_SOMEONE" to 2, "SOMETHING_NEW" to 1), 1, "LOW", null).toDomain()!!
        assertTrue(summary.youTookPart)
        assertEquals(1, summary.flags.size)
        val detail = CaseDetailDto(null, "tdr1v9q", null, true, null, 0, emptyList(), null)
        assertNull("a case without a readable summary is dropped", detail.toDomain())
        assertTrue(AppealDto("a", "OPEN", null, null, null, null, null, null, null).toDomain()!!.youDecidedTheRestriction)
        assertNull(CaseSummaryDto("c", "BOREDOM", "OPEN", null, null, "HARASSMENT", null, null, null, null, false).toDomain())
    }

    @Test
    fun `pseudonyms and cells stay out of logs`() {
        val participant = ParticipantDto("0123456789ab", "REPORT", null, "STANDARD", "NEW", true, null)
        assertFalse(participant.toString().contains("0123456789ab"))
        assertFalse(ActionRequestDto("RESTRICT", "SPAM_OR_COORDINATED", "0123456789ab", 7).toString().contains("0123456789ab"))
        assertFalse(CaseDetailDto(null, "tdr1v9q", "tdr1v9", true, null, 0, null, false).toString().contains("tdr1v9q"))
    }

    // ---- Phase 9C: the coordination pattern on a case -----------------------------------------------------------------

    @Test
    fun `the coordination pattern is read in bands, and anything not understood is left out`() {
        val pattern = CoordinationDto(listOf("SUPPORT_CORE", "SOMETHING_NEW"), "THREE_TO_FIVE", "TEN_OR_MORE").toDomain()!!
        assertEquals(listOf(CoordinationReason.SUPPORT_CORE), pattern.reasons)
        assertEquals(GroupSize.THREE_TO_FIVE, pattern.groupSize)
        assertEquals(GroupPlaces.TEN_OR_MORE, pattern.groupPlaces)
        assertNull("nothing understood, nothing shown", CoordinationDto(listOf("SOMETHING_NEW"), null, null).toDomain())
        val summary = CaseSummaryDto("c1", "COORDINATION", "OPEN", null, "2026-10-21", "POOR_LIGHTING", "NONE", null, 1,
            "LOW", false)
        assertNull("an older server sends no pattern",
            CaseDetailDto(summary, "tdr1v9q", "tdr1v9", true, null, 0, emptyList(), false).toDomain()!!.coordination)
    }

    @Test
    fun `a pattern always comes with the guidance, and a pattern that has gone says so`() {
        val withPattern = caseDetail().copy(coordination = CoordinationPattern(
            listOf(CoordinationReason.EXCLUSIVE_PAIR), GroupSize.TWO, GroupPlaces.TWO_TO_FOUR))
        val lines = ModerationTexts.coordinationLines(withPattern)
        assertEquals(R.string.coordination_heading, lines.first())
        assertEquals(R.string.coordination_guidance, lines.last())
        assertTrue(R.string.coordination_exclusive_pair in lines)
        assertTrue(R.string.coordination_size_two in lines && R.string.coordination_places_few in lines)
        val gone = caseDetail(caseSummary().copy(origin = CaseOrigin.COORDINATION))
        assertEquals(listOf(R.string.coordination_gone), ModerationTexts.coordinationLines(gone))
        assertTrue("a flags case without a pattern shows nothing", ModerationTexts.coordinationLines(caseDetail()).isEmpty())
        assertEquals(CoordinationReason.entries.size,
            CoordinationReason.entries.map { ModerationTexts.coordinationReason(it) }.toSet().size)
        assertEquals(GroupSize.entries.size, GroupSize.entries.map { ModerationTexts.groupSize(it) }.toSet().size)
        assertEquals(GroupPlaces.entries.size, GroupPlaces.entries.map { ModerationTexts.groupPlaces(it) }.toSet().size)
    }

    @Test
    fun `group members are read only as group members, and an older server sends none`() {
        val summary = CaseSummaryDto("c1", "COORDINATION", "OPEN", null, "2026-10-21", "POOR_LIGHTING", "NONE", null, 2,
            "MODERATE", false)
        val detail = CaseDetailDto(summary, "tdr1v9q", "tdr1v9", true, null, 0, emptyList(), false,
            CoordinationDto(listOf("SUPPORT_CORE"), "THREE_TO_FIVE", "TEN_OR_MORE"),
            listOf(ParticipantDto("aaaaaaaaaaaa", null, null, "STANDARD", "RECENT", true, null),
                ParticipantDto("bbbbbbbbbbbb", "REPORT", null, "STANDARD", "RECENT", true, null))).toDomain()!!
        assertEquals("anything with a role is not read as a group member", listOf("aaaaaaaaaaaa"),
            detail.groupMembers.map { it.ref })
        assertTrue(CaseDetailDto(summary, "tdr1v9q", "tdr1v9", true, null, 0, emptyList(), false).toDomain()!!
            .groupMembers.isEmpty())
        assertFalse(CaseDetailDto(summary, "tdr1v9q", "tdr1v9", true, null, 0, null, false, null,
            listOf(ParticipantDto("aaaaaaaaaaaa", null, null, null, null, null, null))).toString().contains("aaaaaaaaaaaa"))
        assertTrue(ActionRequestDto("RESTRICT", "SPAM_OR_COORDINATED", null, 7, true).toString().contains("wholeGroup=true"))
    }

    @Test
    fun `the whole group can be paused only on an open coord-1 case the moderator may act on`() {
        fun withPattern(vararg reasons: CoordinationReason, detail: CaseDetail = caseDetail()) =
            detail.copy(coordination = CoordinationPattern(reasons.toList(), GroupSize.THREE_TO_FIVE, GroupPlaces.FIVE_TO_NINE))
        assertTrue(ModerationPolicy.canRestrictGroup(withPattern(CoordinationReason.SUPPORT_CORE)))
        assertTrue(ModerationPolicy.canRestrictGroup(withPattern(CoordinationReason.EXCLUSIVE_PAIR)))
        assertFalse("a Phase 7 linked group: all of it is already the case's participants",
            ModerationPolicy.canRestrictGroup(withPattern(CoordinationReason.LINKED_GROUP)))
        assertFalse(ModerationPolicy.canRestrictGroup(caseDetail()))
        assertFalse(ModerationPolicy.canRestrictGroup(withPattern(CoordinationReason.SUPPORT_CORE,
            detail = caseDetail(youTookPart = true))))
        assertFalse(ModerationPolicy.canRestrictGroup(withPattern(CoordinationReason.SUPPORT_CORE,
            detail = caseDetail(caseSummary(status = CaseStatus.CLOSED)))))
    }

    @Test
    fun `keeping a coordination case's signal says the people will count separately for this place`() {
        assertEquals(R.string.coordination_confirm_dismiss,
            ModerationTexts.confirmation(CaseAction.DISMISS, CaseOrigin.COORDINATION))
        assertEquals(R.string.moderation_confirm_dismiss, ModerationTexts.confirmation(CaseAction.DISMISS, CaseOrigin.FLAGS))
        assertEquals(R.string.moderation_confirm_dismiss, ModerationTexts.confirmation(CaseAction.DISMISS))
        assertEquals(R.string.moderation_confirm_remove,
            ModerationTexts.confirmation(CaseAction.REMOVE, CaseOrigin.COORDINATION))
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        val dismiss = Regex("<string name=\"coordination_confirm_dismiss\">(.*?)</string>").find(xml)!!.groupValues[1]
        assertTrue(dismiss.contains("separate people for this place"))
        assertTrue(dismiss.contains("other places are not affected"))
        val pause = Regex("<string name=\"coordination_confirm_pause_group\">(.*?)</string>").find(xml)!!.groupValues[1]
        assertTrue(pause.contains("SOS") && pause.contains("review") && pause.contains("not proof"))
    }

    @Test
    fun `pausing the whole group sends one decision with no pseudonym, and only when it can succeed`() = runTest {
        val vm = ModerationCaseViewModel(repository, "c1")
        vm.load()
        advanceUntilIdle()
        vm.onRestrictGroup(ModerationReason.SPAM_OR_COORDINATED, 7)
        advanceUntilIdle()
        assertTrue("no coordination group behind this case: nothing sent", repository.acts.isEmpty())

        repository.detailResult = ApiResult.Success(caseDetail(caseSummary().copy(origin = CaseOrigin.COORDINATION)).copy(
            coordination = CoordinationPattern(listOf(CoordinationReason.SUPPORT_CORE), GroupSize.THREE_TO_FIVE,
                GroupPlaces.FIVE_TO_NINE),
            groupMembers = listOf(participant(ref = "aaaaaaaaaaaa", role = null).copy(flag = null))))
        vm.load()
        advanceUntilIdle()
        vm.onRestrictGroup(ModerationReason.DUPLICATE, 7)             // not a pause reason
        vm.onRestrictGroup(ModerationReason.SPAM_OR_COORDINATED, 365) // not an offered length
        advanceUntilIdle()
        assertTrue(repository.acts.isEmpty())
        vm.onRestrictGroup(ModerationReason.SPAM_OR_COORDINATED, 30)
        advanceUntilIdle()
        assertEquals(listOf(FakeModerationRepository.Act("c1", CaseAction.RESTRICT, ModerationReason.SPAM_OR_COORDINATED,
            null, 30, wholeGroup = true)), repository.acts)
        val ready = vm.ui.value.state as CaseState.Ready
        assertEquals(1, ready.detail.groupMembers.size)
    }

    @Test
    fun `coordination words describe a pattern and accuse nobody`() {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        val texts = Regex("<string name=\"(coordination_[a-z_]+|case_origin_coordination)\">(.*?)</string>")
            .findAll(xml).associate { it.groupValues[1] to it.groupValues[2].lowercase() }
        assertTrue("Phase 9C strings not found", texts.size >= 12)
        val forbidden = listOf("fake", "fakes", "fraud", "bot", "bots", "sock", "puppet", "puppets", "liar", "lying",
            "criminal", "suspicious", "dangerous", "guilty", "scam", "abuse", "abusive")
        val problems = texts.flatMap { (name, text) ->
            forbidden.filter { Regex("\\b$it\\b").containsMatchIn(text) }.map { "$name: $it" }
        }
        assertTrue(problems.joinToString(), problems.isEmpty())
        assertTrue(texts.getValue("coordination_guidance").contains("not proof"))
    }

    // ---- Queue -------------------------------------------------------------------------------------------------------

    @Test
    fun `a normal account sees nothing and no case is even requested`() = runTest {
        repository.accessResult = ApiResult.Success(ModerationAccess.NONE)
        val vm = ModerationViewModel(repository)
        vm.load()
        advanceUntilIdle()
        assertEquals(QueueState.NotAllowed, vm.ui.value.queue)
        assertEquals(0, repository.casesCalls)
    }

    @Test
    fun `a role removed meanwhile (403) ends in NotAllowed, not in old data`() = runTest {
        val vm = ModerationViewModel(repository)
        vm.load()
        advanceUntilIdle()
        assertTrue(vm.ui.value.queue is QueueState.Ready)
        repository.casesResult = ApiResult.Failure(ApiError.FORBIDDEN, "Moderators only")
        vm.load()
        advanceUntilIdle()
        assertEquals(QueueState.NotAllowed, vm.ui.value.queue)
    }

    @Test
    fun `the queue shows open cases and appeals`() = runTest {
        repository.appealsResult = ApiResult.Success(listOf(appeal()))
        val vm = ModerationViewModel(repository)
        vm.load()
        advanceUntilIdle()
        val ready = vm.ui.value.queue as QueueState.Ready
        assertEquals("c1", ready.cases.single().id)
        assertEquals("a1", ready.appeals.single().id)
    }

    @Test
    fun `appeal decisions are sent once, never for the moderator's own restriction`() = runTest {
        val vm = ModerationViewModel(repository)
        vm.load()
        advanceUntilIdle()
        vm.onDecideAppeal(appeal(id = "mine", mine = true), AppealStatus.OVERTURNED)
        vm.onDecideAppeal(appeal(), AppealStatus.OPEN)
        advanceUntilIdle()
        assertTrue(repository.decisions.isEmpty())

        vm.onDecideAppeal(appeal(), AppealStatus.OVERTURNED)
        advanceUntilIdle()
        assertEquals(listOf("a1" to AppealStatus.OVERTURNED), repository.decisions)
        assertEquals(ModerationEvent.AppealDecided(AppealStatus.OVERTURNED), vm.ui.value.event)
    }

    @Test
    fun `a refused appeal decision shows the server's reason`() = runTest {
        repository.decideResult = ApiResult.Failure(ApiError.FORBIDDEN,
            "The moderator who made a decision cannot decide its appeal.")
        val vm = ModerationViewModel(repository)
        vm.onDecideAppeal(appeal(), AppealStatus.UPHELD)
        advanceUntilIdle()
        val event = vm.ui.value.event as ModerationEvent.Failed
        assertEquals(ApiError.FORBIDDEN, event.failure.error)
    }

    // ---- One case ----------------------------------------------------------------------------------------------------

    @Test
    fun `a case the moderator took part in (403) is not shown`() = runTest {
        repository.detailResult = ApiResult.Failure(ApiError.FORBIDDEN,
            "You took part in this signal; another moderator must review it.")
        val vm = ModerationCaseViewModel(repository, "c1")
        vm.load()
        advanceUntilIdle()
        assertEquals(CaseState.NotAllowed("You took part in this signal; another moderator must review it."), vm.ui.value.state)
    }

    @Test
    fun `decisions send only the action, a reason code and for a pause the pseudonym and days`() = runTest {
        val vm = ModerationCaseViewModel(repository, "c1")
        vm.load()
        advanceUntilIdle()
        vm.onDecide(CaseAction.REMOVE, ModerationReason.TARGETS_SOMEONE)
        advanceUntilIdle()
        vm.onRestrict(participant(), ModerationReason.SPAM_OR_COORDINATED, 30)
        advanceUntilIdle()
        assertEquals(
            listOf(
                FakeModerationRepository.Act("c1", CaseAction.REMOVE, ModerationReason.TARGETS_SOMEONE, null, null),
                FakeModerationRepository.Act("c1", CaseAction.RESTRICT, ModerationReason.SPAM_OR_COORDINATED, "0123456789ab", 30)
            ),
            repository.acts
        )
        assertEquals(CaseEvent.Decided(CaseAction.RESTRICT), vm.ui.value.event)
    }

    @Test
    fun `impossible decisions are never sent`() = runTest {
        repository.detailResult = ApiResult.Success(caseDetail(youTookPart = true))
        val vm = ModerationCaseViewModel(repository, "c1")
        vm.load()
        advanceUntilIdle()
        vm.onDecide(CaseAction.REMOVE, ModerationReason.FALSE_INFORMATION)
        vm.onRestrict(participant(), ModerationReason.FALSE_INFORMATION, 7)
        advanceUntilIdle()
        assertTrue(repository.acts.isEmpty())

        repository.detailResult = ApiResult.Success(caseDetail())
        vm.load()
        advanceUntilIdle()
        vm.onDecide(CaseAction.DISMISS, ModerationReason.FALSE_INFORMATION) // wrong reason for DISMISS
        vm.onRestrict(participant(), ModerationReason.DUPLICATE, 7)          // not a pause reason
        vm.onRestrict(participant(), ModerationReason.FALSE_INFORMATION, 365) // not an offered length
        advanceUntilIdle()
        assertTrue(repository.acts.isEmpty())
    }

    @Test
    fun `a 409 (another moderator was faster) reloads the case`() = runTest {
        val vm = ModerationCaseViewModel(repository, "c1")
        vm.load()
        advanceUntilIdle()
        repository.actResult = ApiResult.Failure(ApiError.CONFLICT, "This case is already closed.")
        repository.detailResult = ApiResult.Success(caseDetail(caseSummary(status = CaseStatus.CLOSED)))
        vm.onDecide(CaseAction.DISMISS, ModerationReason.NO_VIOLATION)
        advanceUntilIdle()
        val ready = vm.ui.value.state as CaseState.Ready
        assertEquals(CaseStatus.CLOSED, ready.detail.summary.status)
        assertTrue(ready.actions.isEmpty())
    }

    // ---- Profile entry ------------------------------------------------------------------------------------------------

    @Test
    fun `the Profile entry appears only when the server says so, and disappears on any failure`() = runTest {
        val profile = ProfileViewModel(FakeAuthRepository(FakeAuthRepository.DEFAULT_USER), repository)
        profile.refreshAccess()
        advanceUntilIdle()
        assertTrue(profile.canModerate.value)

        repository.accessResult = ApiResult.Success(ModerationAccess.NONE)
        profile.refreshAccess()
        advanceUntilIdle()
        assertFalse(profile.canModerate.value)

        repository.accessResult = ApiResult.Success(ModerationAccess(true, false))
        profile.refreshAccess()
        advanceUntilIdle()
        repository.accessResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        profile.refreshAccess()
        advanceUntilIdle()
        assertFalse(profile.canModerate.value)
    }
}
