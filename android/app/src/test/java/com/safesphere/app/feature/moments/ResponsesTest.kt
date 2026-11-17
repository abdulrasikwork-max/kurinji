package com.safesphere.app.feature.moments

import com.safesphere.app.R
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.moments.data.remote.ForAuthorDto
import com.safesphere.app.feature.moments.data.remote.HappenedRefDto
import com.safesphere.app.feature.moments.data.remote.MomentAuthorDto
import com.safesphere.app.feature.moments.data.remote.MomentDto
import com.safesphere.app.feature.moments.data.remote.MoreDto
import com.safesphere.app.feature.moments.data.remote.PeopleDto
import com.safesphere.app.feature.moments.data.remote.PersonDto
import com.safesphere.app.feature.moments.data.remote.PlanViewDto
import com.safesphere.app.feature.moments.data.remote.ResponsesDto
import com.safesphere.app.feature.moments.data.remote.SameDto
import com.safesphere.app.feature.moments.data.remote.SayMoreDto
import com.safesphere.app.feature.moments.data.remote.TextBodyDto
import com.safesphere.app.feature.moments.data.remote.WhenDto
import com.safesphere.app.feature.moments.domain.AuthorLines
import com.safesphere.app.feature.moments.domain.FollowUp
import com.safesphere.app.feature.moments.domain.Moment
import com.safesphere.app.feature.moments.domain.MomentKind
import com.safesphere.app.feature.moments.domain.MomentResponses
import com.safesphere.app.feature.moments.domain.MomentStatus
import com.safesphere.app.feature.moments.domain.PlanPeople
import com.safesphere.app.feature.moments.domain.PlanPerson
import com.safesphere.app.feature.moments.domain.PlanStanding
import com.safesphere.app.feature.moments.domain.PlanState
import com.safesphere.app.feature.moments.domain.SameBand
import com.safesphere.app.feature.moments.domain.SameState
import com.safesphere.app.feature.moments.domain.TodayPage
import com.safesphere.app.feature.moments.ui.MomentViewModel
import com.safesphere.app.feature.moments.ui.PeopleSections
import com.safesphere.app.feature.moments.ui.PersonAction
import com.safesphere.app.feature.moments.ui.ResponseLabels
import com.safesphere.app.feature.moments.ui.ResponseWords
import com.safesphere.app.feature.moments.ui.TodayViewModel
import com.safesphere.app.testing.FakeMomentRepository
import com.safesphere.app.testing.FakeResponseRepository
import com.safesphere.app.testing.MainDispatcherRule
import com.safesphere.app.testing.moment
import java.io.File
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase N5-A1 on the phone: how responses are read (fail closed), the words (D14: names for the author, a band for
 * everyone else, on a card only after one's own Same; D16: never "declined"), what may be done when (the response
 * matrix), the author's people sheet, the moment page's actions, Today reading one card again, and the source guards
 * (no number in the words, nothing stored).
 */
class ResponsesTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val id = "0a1b2c3d-0000-4000-8000-00000000000a"
    private val plan = "0a1b2c3d-0000-4000-8000-00000000000c"
    private val ref = "0a1b2c3d-0000-4000-8000-00000000000d"

    private fun dto(kind: String = "SAY", mine: Boolean = false, responses: ResponsesDto?) = MomentDto(
        id = id, kind = kind, audience = "COMMUNITY", author = MomentAuthorDto(null, null, "Asha K", null), mine = mine,
        text = "Canteen at four?", `when` = WhenDto("TODAY", "MORNING"), status = "LIVE", responses = responses)

    private fun say(responses: MomentResponses, mine: Boolean = false, status: MomentStatus = MomentStatus.LIVE): Moment =
        moment(id, mine = mine, status = status).copy(responses = responses)

    private fun whosIn(plan: PlanState?, mine: Boolean = false, status: MomentStatus = MomentStatus.LIVE,
                       author: AuthorLines? = null): Moment =
        moment(id, mine = mine, status = status, kind = MomentKind.WHOS_IN)
            .copy(responses = MomentResponses.NONE.copy(plan = plan, author = author))

    private fun lines(same: List<String> = emptyList(), sayMore: List<String> = emptyList(),
                      asking: List<String> = emptyList(), inPlan: List<String> = emptyList(), others: Boolean = false) =
        AuthorLines(same, others, sayMore, others, asking, others, inPlan, others)

    // ---- Reading (fail closed) ----------------------------------------------------------------------------------------

    @Test
    fun `a band this app does not know shows no band, and Same is never read on a Who's in`() {
        val r = dto(responses = ResponsesDto(same = SameDto(mine = true, band = "SEVERAL"))).toDomain()!!.responses
        assertEquals(SameState(true, null), r.same)
        val known = dto(responses = ResponsesDto(same = SameDto(false, "A_FEW"))).toDomain()!!.responses
        assertEquals(SameBand.A_FEW, known.same!!.band)
        val plan = dto(kind = "WHOS_IN", responses = ResponsesDto(same = SameDto(true, "MANY"),
            plan = PlanViewDto("NONE", true, false, null))).toDomain()!!.responses
        assertNull(plan.same)
        assertFalse(plan.sayMore)
    }

    @Test
    fun `the author never gets a band, and only the author gets names (at most three, no blank ones)`() {
        val forAuthor = ForAuthorDto(same = listOf("Ravi", " ", null, "Kavin", "Meena", "Arun"), otherSame = true)
        val mine = dto(mine = true, responses = ResponsesDto(same = SameDto(false, "MANY"), author = forAuthor)).toDomain()!!.responses
        assertNull(mine.same)
        assertEquals(listOf("Ravi", "Kavin", "Meena"), mine.author!!.same)
        assertTrue(mine.author!!.otherSame)
        val theirs = dto(responses = ResponsesDto(author = forAuthor)).toDomain()!!.responses
        assertNull("names sent to someone else are dropped", theirs.author)
    }

    @Test
    fun `an unknown standing shows no plan controls, AUTHOR is never accepted for someone else`() {
        assertNull(dto(kind = "WHOS_IN", responses = ResponsesDto(plan = PlanViewDto("DECLINED", true))).toDomain()!!.responses.plan)
        assertNull(dto(kind = "WHOS_IN", responses = ResponsesDto(plan = PlanViewDto("AUTHOR", true))).toDomain()!!.responses.plan)
        val asked = dto(kind = "WHOS_IN", responses = ResponsesDto(plan = PlanViewDto("ASKED", true, false, "not-a-uuid")))
            .toDomain()!!.responses.plan!!
        assertEquals(PlanState(PlanStanding.ASKED, open = true, calledOff = false, planId = null), asked)
        val mine = dto(kind = "WHOS_IN", mine = true, responses = ResponsesDto(plan = PlanViewDto("NONE", false, true, plan)))
            .toDomain()!!.responses.plan!!
        assertEquals(PlanStanding.AUTHOR, mine.you)
        assertEquals(plan, mine.planId)
        assertNull("no responses at all", dto(responses = null).toDomain()!!.responses.plan)
        assertEquals(MomentResponses.NONE, dto(kind = "WHOS_IN", responses = null).toDomain()!!.responses)
    }

    @Test
    fun `the follow-up - exact time only for the author, blank words are none, only on a Say`() {
        val more = MoreDto(" It was the filter coffee ", WhenDto("TODAY", "AFTERNOON"), "2026-11-17T10:00:00Z")
        val theirs = dto(responses = ResponsesDto(more = more)).toDomain()!!.responses.more!!
        assertEquals("It was the filter coffee", theirs.text)
        assertNull(theirs.at)
        assertEquals(com.safesphere.app.feature.moments.domain.DayPart.AFTERNOON, theirs.shared!!.part)
        assertEquals(java.time.Instant.parse("2026-11-17T10:00:00Z"), dto(mine = true, responses = ResponsesDto(more = more)).toDomain()!!.responses.more!!.at)
        assertNull(dto(responses = ResponsesDto(more = MoreDto("  "))).toDomain()!!.responses.more)
        assertNull(dto(kind = "WHOS_IN", responses = ResponsesDto(more = more)).toDomain()!!.responses.more)
    }

    @Test
    fun `reply ids are UUIDs or nothing, canReply never on one's own, This happened needs a real id`() {
        val r = dto(responses = ResponsesDto(canReply = true, replyId = "../x")).toDomain()!!.responses
        assertTrue(r.canReply)
        assertNull(r.replyId)
        val mine = dto(mine = true, responses = ResponsesDto(canReply = true, replyId = plan)).toDomain()!!.responses
        assertFalse(mine.canReply)
        assertNull(mine.replyId)
        assertNull(HappenedRefDto("nope", "x").toDomain())
        assertNull(HappenedRefDto(plan, "  ").toDomain()!!.text)
        assertEquals("Canteen", HappenedRefDto(plan, "Canteen").toDomain()!!.text)
    }

    @Test
    fun `people lists drop nameless rows, odd ids and codes, and say Say more is only the asker's own`() {
        val people = PeopleDto(
            asking = listOf(PersonDto(ref, "Ravi", "x/../y", null, "BTECH_IT", 2027, true), PersonDto(ref, " "), null),
            `in` = listOf(PersonDto("bad", "Meena", programme = "b.tech; drop", year = 1900))
        ).toDomain()
        assertEquals(1, people.asking.size)
        assertEquals(ref, people.asking[0].ref)
        assertNull(people.asking[0].profileId)
        assertEquals("BTECH_IT", people.asking[0].programme)
        assertTrue(people.asking[0].myPeople)
        val meena = people.inPlan.single()
        assertNull(meena.ref)
        assertNull(meena.programme)
        assertNull(meena.year)
        assertTrue(people.same.isEmpty() && people.sayMore.isEmpty() && people.setAside.isEmpty())
        assertFalse(dto(responses = ResponsesDto(sayMore = SayMoreDto(null))).toDomain()!!.responses.sayMore)
        assertFalse("the follow-up words never show in logs", TextBodyDto("private words").toString().contains("private"))
    }

    // ---- Words --------------------------------------------------------------------------------------------------------

    @Test
    fun `D14 - others get a band of the other people, with their own tap said first`() {
        assertNull(ResponseLabels.same(SameState(false, null)))
        assertEquals(R.string.response_same_someone, ResponseLabels.same(SameState(false, SameBand.SOMEONE)))
        assertEquals(R.string.response_same_few, ResponseLabels.same(SameState(false, SameBand.A_FEW)))
        assertEquals(R.string.response_same_many, ResponseLabels.same(SameState(false, SameBand.MANY)))
        assertEquals(R.string.response_same_you, ResponseLabels.same(SameState(true, null)))
        assertEquals(R.string.response_same_you_someone, ResponseLabels.same(SameState(true, SameBand.SOMEONE)))
        assertEquals(R.string.response_same_you_few, ResponseLabels.same(SameState(true, SameBand.A_FEW)))
        assertEquals(R.string.response_same_you_many, ResponseLabels.same(SameState(true, SameBand.MANY)))
        // On a Today card only after one's own Same.
        assertNull(ResponseLabels.sameOnCard(SameState(false, SameBand.MANY)))
        assertNull(ResponseLabels.cardLine(say(MomentResponses.NONE.copy(same = SameState(false, SameBand.MANY)))))
        assertEquals(ResponseWords(R.string.response_same_you_many),
            ResponseLabels.cardLine(say(MomentResponses.NONE.copy(same = SameState(true, SameBand.MANY)))))
    }

    @Test
    fun `D16 - an asker reads waiting, in, closed, called off or not in any more, never declined`() {
        fun standing(you: PlanStanding, open: Boolean = true, calledOff: Boolean = false, onCard: Boolean = false) =
            ResponseLabels.standing(PlanState(you, open, calledOff, null), "Asha", onCard)
        assertEquals(ResponseWords(R.string.response_plan_waiting, arg = "Asha"), standing(PlanStanding.ASKED))
        assertEquals(ResponseWords(R.string.response_plan_closed), standing(PlanStanding.ASKED, open = false))
        assertEquals(ResponseWords(R.string.response_plan_called_off), standing(PlanStanding.ASKED, calledOff = true))
        assertEquals(ResponseWords(R.string.response_plan_in), standing(PlanStanding.IN, open = false))
        assertEquals(ResponseWords(R.string.response_plan_called_off), standing(PlanStanding.IN, calledOff = true))
        assertEquals(ResponseWords(R.string.response_plan_removed), standing(PlanStanding.REMOVED, calledOff = true))
        assertNull(standing(PlanStanding.NONE))
        assertEquals(ResponseWords(R.string.response_plan_not_taking), standing(PlanStanding.NONE, open = false))
        assertNull("a card stays quiet for someone who has not asked", standing(PlanStanding.NONE, open = false, onCard = true))
        assertEquals(ResponseWords(R.string.response_plan_called_off), standing(PlanStanding.NONE, calledOff = true, onCard = true))
        assertNull(standing(PlanStanding.AUTHOR))
    }

    @Test
    fun `the author reads names - who waits for an answer first, the right verb for one or more`() {
        val sayLines = ResponseLabels.author(say(MomentResponses.NONE.copy(author = lines(same = listOf("Ravi", "Kavin"),
            sayMore = listOf("Meena"))), mine = true))
        assertEquals(listOf(
            ResponseWords(R.string.response_author_say_more_one, listOf("Meena")),
            ResponseWords(R.string.response_author_same_many, listOf("Ravi", "Kavin"))), sayLines)
        val answered = ResponseLabels.author(say(MomentResponses.NONE.copy(more = FollowUp("x", null, null),
            author = lines(sayMore = listOf("Meena"))), mine = true))
        assertEquals(listOf(ResponseWords(R.string.response_author_said_more)), answered)
        val others = ResponseLabels.author(say(MomentResponses.NONE.copy(author = lines(same = listOf("Ravi"), others = true)), mine = true))
        assertEquals(ResponseWords(R.string.response_author_same_many, listOf("Ravi"), others = true), others.single())

        val planLines = ResponseLabels.author(whosIn(PlanState(PlanStanding.AUTHOR, false, true, null), mine = true,
            author = lines(asking = listOf("Arun"), inPlan = listOf("Ravi", "Kavin"))))
        assertEquals(listOf(R.string.response_author_called_off, R.string.response_author_asking_one,
            R.string.response_author_in_many), planLines.map { it.res })
        assertEquals("the card shows the first line only", planLines.first(),
            ResponseLabels.cardLine(whosIn(PlanState(PlanStanding.AUTHOR, false, true, null), mine = true,
                author = lines(asking = listOf("Arun"), inPlan = listOf("Ravi", "Kavin")))))
    }

    // ---- What may be done when (N5 §3) --------------------------------------------------------------------------------

    @Test
    fun `Same and Say more only on someone else's Say on Today, Say more ends once the author said more`() {
        val open = ResponseLabels.actions(say(MomentResponses.NONE.copy(same = SameState(false, null))))
        assertTrue(open.same && open.sayMore)
        assertFalse(open.askIn || open.addMore || open.callOff || open.people)
        val ended = ResponseLabels.actions(say(MomentResponses.NONE.copy(same = SameState(true, null)), status = MomentStatus.ENDED))
        assertFalse(ended.same || ended.sayMore)
        val said = ResponseLabels.actions(say(MomentResponses.NONE.copy(same = SameState(false, null), more = FollowUp("x", null, null))))
        assertTrue(said.same)
        assertFalse(said.sayMore)
        val mine = ResponseLabels.actions(say(MomentResponses.NONE, mine = true))
        assertFalse(mine.same || mine.sayMore || mine.addMore)
    }

    @Test
    fun `the author adds more once, only after someone asked and while others can see it on Today`() {
        val asked = MomentResponses.NONE.copy(author = lines(sayMore = listOf("Meena")))
        assertTrue(ResponseLabels.actions(say(asked, mine = true)).addMore)
        assertTrue(ResponseLabels.actions(say(asked, mine = true)).people)
        assertFalse(ResponseLabels.actions(say(asked.copy(more = FollowUp("x", null, null)), mine = true)).addMore)
        assertFalse(ResponseLabels.actions(say(asked, mine = true, status = MomentStatus.ENDED)).addMore)
        assertFalse(ResponseLabels.actions(say(MomentResponses.NONE.copy(author = lines(same = listOf("Ravi"))), mine = true)).addMore)
    }

    @Test
    fun `I'm in only while asks are open, leaving is always offered while asked or in`() {
        fun actions(you: PlanStanding, open: Boolean = true, calledOff: Boolean = false, status: MomentStatus = MomentStatus.LIVE) =
            ResponseLabels.actions(whosIn(PlanState(you, open, calledOff, null), status = status))
        assertTrue(actions(PlanStanding.NONE).askIn)
        assertFalse(actions(PlanStanding.NONE, open = false).askIn)
        assertFalse(actions(PlanStanding.NONE, calledOff = true).askIn)
        assertFalse(actions(PlanStanding.NONE, status = MomentStatus.ENDED).askIn)
        assertFalse(actions(PlanStanding.REMOVED).askIn)
        assertTrue(actions(PlanStanding.ASKED).withdraw)
        assertFalse("a closed ask has nothing to take back", actions(PlanStanding.ASKED, open = false).withdraw)
        assertTrue("nobody is trapped in a plan, also after Today", actions(PlanStanding.IN, open = false, status = MomentStatus.ENDED).withdraw)
        assertTrue(actions(PlanStanding.IN).people)
        assertFalse(actions(PlanStanding.REMOVED).withdraw || actions(PlanStanding.REMOVED).people)
        assertEquals(R.string.response_action_cant_make_it, ResponseLabels.withdrawLabel(PlanState(PlanStanding.IN, true, false, null)))
        assertEquals(R.string.response_action_take_back, ResponseLabels.withdrawLabel(PlanState(PlanStanding.ASKED, true, false, null)))
    }

    @Test
    fun `the author calls off a plan others can see, once`() {
        val open = PlanState(PlanStanding.AUTHOR, open = true, calledOff = false, planId = null)
        assertTrue(ResponseLabels.actions(whosIn(open, mine = true)).callOff)
        assertFalse(ResponseLabels.actions(whosIn(open.copy(calledOff = true), mine = true)).callOff)
        assertFalse(ResponseLabels.actions(whosIn(open, mine = true, status = MomentStatus.WAITING)).callOff)
        assertTrue("a running plan after Today", ResponseLabels.actions(whosIn(open.copy(open = false, planId = plan), mine = true,
            status = MomentStatus.ENDED)).callOff)
        assertFalse(ResponseLabels.actions(whosIn(open.copy(open = false), mine = true)).callOff)
    }

    // ---- The people sheet ---------------------------------------------------------------------------------------------

    private fun person(name: String, ref: String? = this.ref) = PlanPerson(ref, name, null, null, null, null, false)

    @Test
    fun `the author's sheet - Add only while open, Not this time, Bring back, Remove - no decline exists`() {
        val people = PlanPeople(same = listOf(person("Sam", null)), sayMore = emptyList(), asking = listOf(person("Arun")),
            setAside = listOf(person("Bala")), inPlan = listOf(person("Ravi")))
        val open = PeopleSections.of(people, author = true, open = true)
        assertEquals(listOf(R.string.response_people_asking, R.string.response_people_in, R.string.response_people_set_aside,
            R.string.response_people_same), open.map { it.title })
        assertEquals(listOf(PersonAction.SET_ASIDE, PersonAction.ADD), open[0].rows.single().actions)
        assertEquals(listOf(PersonAction.REMOVE), open[1].rows.single().actions)
        assertEquals(listOf(PersonAction.BRING_BACK), open[2].rows.single().actions)
        assertTrue(open[3].rows.single().actions.isEmpty())
        val closed = PeopleSections.of(people, author = true, open = false)
        assertEquals(listOf(PersonAction.SET_ASIDE), closed[0].rows.single().actions)
        val member = PeopleSections.of(people, author = false, open = true)
        assertEquals(listOf(R.string.response_people_in), member.map { it.title })
        assertTrue(member.single().rows.single().actions.isEmpty())
        assertTrue(PeopleSections.of(PlanPeople(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()), true, true).isEmpty())
    }

    // ---- The moment page ----------------------------------------------------------------------------------------------

    @Test
    fun `Same and Say more toggle against what the reader said, and the answer replaces the moment`() = runTest {
        val repo = FakeMomentRepository()
        val start = say(MomentResponses.NONE.copy(same = SameState(false, SameBand.A_FEW)))
        repo.momentResult = ApiResult.Success(start)
        val responses = FakeResponseRepository()
        val vm = MomentViewModel(repo, responses)
        vm.load(id)
        advanceUntilIdle()
        val after = start.copy(responses = start.responses.copy(same = SameState(true, SameBand.A_FEW)))
        responses.momentAnswer = ApiResult.Success(after)
        vm.onSame()
        advanceUntilIdle()
        assertEquals("same:$id:true", responses.calls.last())
        assertEquals(after, vm.uiState.value.moment)
        assertTrue("the wall is told", vm.uiState.value.changed)
        vm.onChangedHandled()
        vm.onSame()
        advanceUntilIdle()
        assertEquals("same:$id:false", responses.calls.last())
        vm.onSayMore()
        advanceUntilIdle()
        assertEquals("sayMore:$id:true", responses.calls.last())
    }

    @Test
    fun `a refusal shows the server's words and keeps the moment, a vanished moment reads as gone`() = runTest {
        val repo = FakeMomentRepository()
        repo.momentResult = ApiResult.Success(whosIn(PlanState(PlanStanding.NONE, true, false, null)))
        val responses = FakeResponseRepository()
        val vm = MomentViewModel(repo, responses)
        vm.load(id)
        advanceUntilIdle()
        responses.momentAnswer = ApiResult.Failure(ApiError.CONFLICT, "This plan is not taking new people")
        vm.onAskIn()
        advanceUntilIdle()
        assertEquals("This plan is not taking new people", vm.uiState.value.failure!!.message)
        assertFalse(vm.uiState.value.busy)
        assertEquals(PlanStanding.NONE, vm.uiState.value.moment!!.responses.plan!!.you)
        vm.onFailureShown()
        responses.momentAnswer = ApiResult.Failure(ApiError.NOT_FOUND)
        vm.onAskIn()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.gone)
        assertNull(vm.uiState.value.moment)
    }

    @Test
    fun `taking it back reads the moment again, also when there was nothing left to take back`() = runTest {
        val repo = FakeMomentRepository()
        val asked = whosIn(PlanState(PlanStanding.ASKED, true, false, null))
        repo.momentResult = ApiResult.Success(asked)
        val responses = FakeResponseRepository()
        val vm = MomentViewModel(repo, responses)
        vm.load(id)
        advanceUntilIdle()
        repo.momentResult = ApiResult.Success(whosIn(PlanState(PlanStanding.NONE, true, false, null)))
        vm.onWithdraw()
        advanceUntilIdle()
        assertEquals("leave:$id", responses.calls.last())
        assertEquals(PlanStanding.NONE, vm.uiState.value.moment!!.responses.plan!!.you)
        responses.leaveAnswer = ApiResult.Failure(ApiError.NOT_FOUND)
        vm.onWithdraw()
        advanceUntilIdle()
        assertNull(vm.uiState.value.failure)
        responses.leaveAnswer = ApiResult.Failure(ApiError.NO_CONNECTION)
        vm.onWithdraw()
        advanceUntilIdle()
        assertEquals(ApiError.NO_CONNECTION, vm.uiState.value.failure!!.error)
    }

    @Test
    fun `the author's follow-up - blank never leaves the phone, a support line is shown once`() = runTest {
        val repo = FakeMomentRepository()
        val mine = say(MomentResponses.NONE.copy(author = lines(sayMore = listOf("Meena"))), mine = true)
        repo.momentResult = ApiResult.Success(mine)
        val responses = FakeResponseRepository()
        val vm = MomentViewModel(repo, responses)
        vm.load(id)
        advanceUntilIdle()
        vm.onAddMore("   ")
        advanceUntilIdle()
        assertTrue(responses.calls.isEmpty())
        responses.momentAnswer = ApiResult.Success(mine.copy(supportSuggested = true,
            responses = mine.responses.copy(more = FollowUp("It was hard", null, null))))
        vm.onAddMore("It was hard")
        advanceUntilIdle()
        assertEquals("addMore:$id:It was hard", responses.calls.single())
        assertTrue(vm.uiState.value.support)
        assertFalse(vm.uiState.value.actions.addMore)
        vm.onSupportShown()
        assertFalse(vm.uiState.value.support)
    }

    @Test
    fun `the author's people - read on opening, each change answers the lists and reads the moment again`() = runTest {
        val repo = FakeMomentRepository()
        val mine = whosIn(PlanState(PlanStanding.AUTHOR, true, false, null), mine = true, author = lines(asking = listOf("Arun")))
        repo.momentResult = ApiResult.Success(mine)
        val responses = FakeResponseRepository()
        val lists = PlanPeople(emptyList(), emptyList(), listOf(person("Arun")), emptyList(), emptyList())
        responses.peopleAnswer = ApiResult.Success(lists)
        val vm = MomentViewModel(repo, responses)
        vm.load(id)
        advanceUntilIdle()
        vm.loadPeople()
        advanceUntilIdle()
        assertEquals(lists, vm.uiState.value.people)
        vm.onAdd(ref)
        advanceUntilIdle()
        vm.onSetAside(ref, aside = true)
        advanceUntilIdle()
        vm.onSetAside(ref, aside = false)
        advanceUntilIdle()
        vm.onRemove(ref)
        advanceUntilIdle()
        assertEquals(listOf("people:$id", "add:$id:$ref", "setAside:$id:$ref:true", "setAside:$id:$ref:false", "remove:$id:$ref"),
            responses.calls)
        assertTrue(vm.uiState.value.changed)
        vm.onPeopleClosed()
        assertNull(vm.uiState.value.people)
        responses.momentAnswer = ApiResult.Success(mine.copy(responses = mine.responses.copy(plan = mine.responses.plan!!.copy(calledOff = true))))
        vm.onCallOff()
        advanceUntilIdle()
        assertEquals("callOff:$id", responses.calls.last())
        assertFalse(vm.uiState.value.actions.callOff)
    }

    @Test
    fun `Today reads one answered card again, and drops it when it is gone for the reader`() = runTest {
        val repo = FakeMomentRepository()
        repo.pages += ApiResult.Success(TodayPage(null, listOf(moment(id), moment("other")), null))
        val vm = TodayViewModel(repo) { 0L }
        vm.start()
        advanceUntilIdle()
        val answered = say(MomentResponses.NONE.copy(same = SameState(true, null)))
        repo.momentResult = ApiResult.Success(answered)
        vm.onUpdated(id)
        advanceUntilIdle()
        assertEquals(answered, vm.uiState.value.moments.first())
        vm.onUpdated("not-on-the-wall")
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.moments.size)
        repo.momentResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        vm.onUpdated(id)
        advanceUntilIdle()
        assertEquals(answered, vm.uiState.value.moments.first())
        repo.momentResult = ApiResult.Failure(ApiError.NOT_FOUND)
        vm.onUpdated(id)
        advanceUntilIdle()
        assertEquals(listOf("other"), vm.uiState.value.moments.map { it.id })
    }

    // ---- Source guards ------------------------------------------------------------------------------------------------

    @Test
    fun `response words carry no number and never say declined, seen or read`() {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        val words = Regex("<string name=\"(response_[a-z0-9_]+)\"[^>]*>(.*?)</string>").findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertTrue(words.size > 40)
        for ((name, text) in words) {
            val plain = text.replace(Regex("%[0-9][$]s"), "")
            assertFalse(name, plain.contains("%d") || Regex("\\d").containsMatchIn(plain))
            assertFalse(name, Regex("\\b(declined?|rejected|seen|read by|typing|online)\\b").containsMatchIn(text.lowercase()))
        }
    }

    @Test
    fun `responses store nothing on the phone and never name a count`() {
        val files = listOf("domain/ResponseModels.kt", "data/remote/ResponseDtos.kt", "data/RemoteResponseRepository.kt",
            "ui/ResponseLabels.kt", "ui/MomentPeopleSheet.kt").map { File("src/main/java/com/safesphere/app/feature/moments/$it") }
        for (file in files) {
            val text = file.readText(Charsets.UTF_8)
            for (bad in listOf("DataStore", "SharedPreferences", "openFileOutput", "count:", "Count(", "val count", "size.toString")) {
                assertFalse("${file.name}: $bad", text.contains(bad))
            }
        }
    }
}
