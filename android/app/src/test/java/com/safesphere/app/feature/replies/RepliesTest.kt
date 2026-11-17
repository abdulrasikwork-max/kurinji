package com.safesphere.app.feature.replies

import com.safesphere.app.R
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.MEMBERSHIP_NEEDED
import com.safesphere.app.feature.moments.data.remote.NoticeDto
import com.safesphere.app.feature.moments.data.remote.PersonDto
import com.safesphere.app.feature.moments.domain.MomentKind
import com.safesphere.app.feature.moments.domain.MomentResponses
import com.safesphere.app.feature.moments.domain.NoticeState
import com.safesphere.app.feature.moments.domain.PlanPerson
import com.safesphere.app.feature.moments.domain.PlanStanding
import com.safesphere.app.feature.moments.domain.PlanState
import com.safesphere.app.feature.moments.ui.ResponseLabels
import com.safesphere.app.feature.moments.ui.ResponseWords
import com.safesphere.app.feature.replies.data.ConversationDto
import com.safesphere.app.feature.replies.data.MessageBodyDto
import com.safesphere.app.feature.replies.data.MessageDto
import com.safesphere.app.feature.replies.data.MomentRefDto
import com.safesphere.app.feature.replies.data.RepliesDto
import com.safesphere.app.feature.replies.data.ReplyItemDto
import com.safesphere.app.feature.replies.data.SettingsDto
import com.safesphere.app.feature.replies.data.YouDto
import com.safesphere.app.feature.replies.domain.ConversationKind
import com.safesphere.app.feature.replies.domain.MessageEvent
import com.safesphere.app.feature.replies.domain.MessageKind
import com.safesphere.app.feature.replies.domain.PrivateReplies
import com.safesphere.app.feature.replies.domain.RepliesPage
import com.safesphere.app.feature.replies.domain.ReplyItem
import com.safesphere.app.feature.replies.domain.ReplyType
import com.safesphere.app.feature.replies.domain.Turn
import com.safesphere.app.feature.replies.domain.You
import com.safesphere.app.feature.replies.ui.ConversationViewModel
import com.safesphere.app.feature.replies.ui.PrivateRepliesViewModel
import com.safesphere.app.feature.replies.ui.RepliesLabels
import com.safesphere.app.feature.replies.ui.RepliesMarker
import com.safesphere.app.feature.replies.ui.RepliesRow
import com.safesphere.app.feature.replies.ui.RepliesViewModel
import com.safesphere.app.testing.FakeRepliesRepository
import com.safesphere.app.testing.MainDispatcherRule
import com.safesphere.app.testing.brief
import com.safesphere.app.testing.conversation
import com.safesphere.app.testing.message
import com.safesphere.app.testing.moment
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase N5-A2 on the phone: Replies (sections, words, the dot), a plan or a private reply (turns, sending, ending,
 * starting), the Private replies setting, and the guards: nothing read-state, nothing stored, no number.
 */
class RepliesTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val m = "0a1b2c3d-0000-4000-8000-00000000000a"
    private val c = "0a1b2c3d-0000-4000-8000-00000000000c"
    private val msg = "0a1b2c3d-0000-4000-8000-00000000000e"

    private fun ref(kind: String = "SAY") = MomentRefDto(m, kind, "Canteen at four?", null, false, false, "Asha", true)

    // ---- Reading (fail closed) ----------------------------------------------------------------------------------------

    @Test
    fun `a conversation this app cannot read is no conversation, an unknown turn sends nothing`() {
        assertNull(ConversationDto(c, "GROUP", ref()).toDomain())
        assertNull(ConversationDto("not-a-uuid", "REPLY", ref()).toDomain())
        assertNull(ConversationDto(c, "REPLY", MomentRefDto(m, "SAY", authorName = " ")).toDomain())
        val odd = ConversationDto(c, "REPLY", ref(), you = YouDto("TYPING", canSend = true), open = true).toDomain()!!
        assertEquals(Turn.CLOSED, odd.you.turn)
        assertFalse(odd.you.canSend)
        val waiting = ConversationDto(c, "REPLY", ref(), you = YouDto("WAITING", canSend = true), open = true).toDomain()!!
        assertFalse("nobody sends twice in a row", waiting.you.canSend)
        val closed = ConversationDto(c, "PLAN", ref("WHOS_IN"), you = YouDto("OPEN", canSend = true), open = false).toDomain()!!
        assertFalse(closed.you.canSend)
        val mine = ConversationDto(c, "REPLY", ref(), you = YouDto("YOUR_TURN", canSend = true), open = true).toDomain()!!
        assertTrue(mine.you.canSend)
    }

    @Test
    fun `messages - words or a known quiet line, the notice and the support line only for the writer`() {
        assertNull(MessageDto(msg, "TEXT", text = "  ").toDomain())
        assertNull(MessageDto(msg, "SYSTEM", event = "SEEN").toDomain())
        assertNull(MessageDto(msg, "VOICE", text = "x").toDomain())
        val system = MessageDto(msg, "SYSTEM", text = "ignored", event = "LEFT").toDomain()!!
        assertEquals(MessageEvent.LEFT, system.event)
        assertNull(system.text)
        val theirs = MessageDto(msg, "TEXT", "hi", mine = false, collapsed = true, notice = NoticeDto("HELD"),
            supportSuggested = true).toDomain()!!
        assertTrue(theirs.collapsed)
        assertNull("someone else's notice is never kept", theirs.notice)
        assertFalse(theirs.supportSuggested)
        val own = MessageDto(msg, "TEXT", "hi", mine = true, collapsed = true, notice = NoticeDto("WHATEVER"),
            supportSuggested = true).toDomain()!!
        assertFalse("the writer reads their own words", own.collapsed)
        assertEquals("an unknown state is the cautious one", NoticeState.HELD, own.notice!!.state)
        assertTrue(own.supportSuggested)
        assertFalse(MessageBodyDto("private words").toString().contains("private"))
    }

    @Test
    fun `Replies - unknown lines and lines in the wrong section are dropped, Same never lights anything`() {
        val page = RepliesDto(
            needsYou = listOf(ReplyItemDto("ASKS", ref(), fresh = true), ReplyItemDto("SAME", ref()), ReplyItemDto("LIKES", ref())),
            plans = listOf(ReplyItemDto("PLAN", ref("WHOS_IN"), c, turn = "OPEN", at = "2026-11-17T10:00:00Z")),
            conversations = listOf(ReplyItemDto("REPLY", ref(), "bad id"), ReplyItemDto("REPLY", MomentRefDto())),
            same = listOf(ReplyItemDto("SAME", ref(), people = List(5) { PersonDto(name = "P$it") }, fresh = true)),
            fresh = true
        ).toDomain()
        assertEquals(listOf(ReplyType.ASKS), page.needsYou.map { it.type })
        assertEquals(c, page.plans.single().conversationId)
        assertEquals(Turn.OPEN, page.plans.single().turn)
        assertNull("not a UUID", page.conversations.single().conversationId)
        assertEquals(3, page.same.single().people.size)
        assertFalse(page.same.single().fresh)
        assertTrue(page.needsYou.single().fresh)
        assertEquals(PrivateReplies.MY_PEOPLE, SettingsDto(null).toDomain())
        assertEquals(PrivateReplies.MY_PEOPLE, SettingsDto("FRIENDS").toDomain())
        assertEquals(PrivateReplies.NOBODY, SettingsDto("NOBODY").toDomain())
    }

    // ---- Words --------------------------------------------------------------------------------------------------------

    private fun item(type: ReplyType, people: List<String> = emptyList(), more: Boolean = false, mine: Boolean = false,
                     turn: Turn? = null, conversation: String? = null) =
        ReplyItem(type, brief(mine = mine), conversation, people.map { PlanPerson(null, it, null, null, null, null, false) },
            more, turn, true, null, false)

    @Test
    fun `Replies has four sections in order, leaves out empty ones, and ends`() {
        assertEquals(listOf(RepliesRow.Loading), RepliesLabels.rows(null, loading = true))
        assertEquals(listOf(RepliesRow.Empty), RepliesLabels.rows(RepliesPage(emptyList(), emptyList(), emptyList(), emptyList(), false), false))
        val page = RepliesPage(listOf(item(ReplyType.ASKS)), emptyList(), listOf(item(ReplyType.REPLY)), listOf(item(ReplyType.SAME)), false)
        val rows = RepliesLabels.rows(page, false)
        assertEquals(listOf(RepliesRow.Section(R.string.replies_section_needs_you), RepliesRow.Line(page.needsYou[0]),
            RepliesRow.Section(R.string.replies_section_conversations), RepliesRow.Line(page.conversations[0]),
            RepliesRow.Section(R.string.replies_section_same), RepliesRow.Line(page.same[0]), RepliesRow.End), rows)
    }

    @Test
    fun `lines name people, never a number, and an asker is never declined`() {
        assertEquals(ResponseWords(R.string.response_author_asking_many, listOf("Ravi", "Kavin"), false),
            RepliesLabels.title(item(ReplyType.ASKS, listOf("Ravi", "Kavin"))))
        assertEquals(ResponseWords(R.string.response_author_same_one, listOf("Ravi")), RepliesLabels.title(item(ReplyType.SAME, listOf("Ravi"))))
        assertEquals(ResponseWords(R.string.response_author_same_many, listOf("Ravi"), true),
            RepliesLabels.title(item(ReplyType.SAME, listOf("Ravi"), more = true)))
        assertEquals(ResponseWords(R.string.response_plan_waiting, arg = "Asha"), RepliesLabels.title(item(ReplyType.WAITING)))
        assertEquals(ResponseWords(R.string.response_plan_removed), RepliesLabels.title(item(ReplyType.NOT_IN)))
        assertEquals(ResponseWords(R.string.replies_line_plan_mine), RepliesLabels.title(item(ReplyType.PLAN, mine = true)))
        assertEquals(ResponseWords(R.string.replies_line_plan, arg = "Asha"), RepliesLabels.title(item(ReplyType.PLAN)))
        assertEquals(ResponseWords(R.string.replies_line_reply, arg = "Meena"), RepliesLabels.title(item(ReplyType.REPLY, listOf("Meena"))))
        assertEquals(ResponseWords(R.string.replies_line_more, arg = "Asha"), RepliesLabels.title(item(ReplyType.MORE)))
        assertEquals(R.string.replies_turn_yours, RepliesLabels.turn(Turn.YOUR_TURN))
        assertEquals(R.string.replies_turn_waiting, RepliesLabels.turn(Turn.WAITING))
        assertNull(RepliesLabels.turn(Turn.OPEN))
        assertTrue(RepliesLabels.opensConversation(item(ReplyType.REPLY, conversation = c)))
        assertFalse(RepliesLabels.opensConversation(item(ReplyType.SAME)))
    }

    @Test
    fun `why one cannot write, and who can end`() {
        assertEquals(R.string.conversation_waiting, RepliesLabels.cannotSend(conversation(turn = Turn.WAITING, canSend = false)))
        assertEquals(R.string.conversation_ended, RepliesLabels.cannotSend(conversation(turn = Turn.ENDED, canSend = false)))
        assertEquals(R.string.conversation_closed, RepliesLabels.cannotSend(conversation(open = false, canSend = false)))
        assertEquals(R.string.conversation_paused, RepliesLabels.cannotSend(conversation(canSend = false).let {
            it.copy(you = You(Turn.YOUR_TURN, false, paused = true)) }))
        assertTrue(RepliesLabels.canEnd(conversation()))
        assertTrue("either person, also while waiting", RepliesLabels.canEnd(conversation(turn = Turn.WAITING, canSend = false)))
        assertFalse(RepliesLabels.canEnd(conversation(kind = ConversationKind.PLAN, turn = Turn.OPEN)))
        assertFalse(RepliesLabels.canEnd(conversation(turn = Turn.ENDED)))
        assertEquals(R.string.conversation_event_left, RepliesLabels.event(MessageEvent.LEFT, mine = false))
        assertEquals(R.string.conversation_event_in_you, RepliesLabels.event(MessageEvent.IN, mine = true))
    }

    // ---- Replies and the dot ------------------------------------------------------------------------------------------

    @Test
    fun `opening Replies reads it, then tells the server and clears the dot, a failure keeps the dot`() = runTest {
        val repo = FakeRepliesRepository()
        val marker = RepliesMarker(repo) { 0L }
        repo.markers += ApiResult.Success(true)
        marker.check()
        assertTrue(marker.fresh.value)
        val vm = RepliesViewModel(repo, marker)
        repo.openedResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        vm.onShown()
        advanceUntilIdle()
        assertEquals(listOf("marker", "replies", "opened"), repo.calls)
        assertTrue("not told the server, so the dot may stay", marker.fresh.value)
        repo.openedResult = ApiResult.Success(Unit)
        vm.onShown()
        advanceUntilIdle()
        assertFalse(marker.fresh.value)
        assertEquals(listOf(RepliesRow.Empty), vm.uiState.value.rows)
    }

    @Test
    fun `Replies is for members, and says so without an error`() = runTest {
        val repo = FakeRepliesRepository()
        repo.page = ApiResult.Failure(ApiError.FORBIDDEN, "Verify first", MEMBERSHIP_NEEDED)
        val vm = RepliesViewModel(repo, RepliesMarker(repo) { 0L })
        vm.onShown()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.needsMembership)
        assertNull(vm.uiState.value.failure)
        assertTrue(vm.uiState.value.rows.isEmpty())
        assertFalse("opened is never sent without a read", "opened" in repo.calls)
    }

    @Test
    fun `the dot - at most every 15 s unless forced, one call at a time, a refusal clears it`() = runTest {
        val repo = FakeRepliesRepository()
        var now = 0L
        val marker = RepliesMarker(repo) { now }
        repo.markers += ApiResult.Success(true)
        marker.check()
        marker.check()
        assertEquals(1, repo.calls.size)
        now = RepliesMarker.MIN_GAP_MS
        repo.markers += ApiResult.Failure(ApiError.NO_CONNECTION)
        marker.check()
        assertTrue("a lost connection keeps the dot", marker.fresh.value)
        repo.markers += ApiResult.Failure(ApiError.FORBIDDEN, null, MEMBERSHIP_NEEDED)
        marker.check(force = true)
        assertFalse(marker.fresh.value)
        assertEquals(3, repo.calls.size)

        val gate = CompletableDeferred<Unit>()
        repo.markerGate = gate
        val first = launch { marker.check(force = true) }
        runCurrent()
        marker.check(force = true)
        gate.complete(Unit)
        first.join()
        assertEquals("the second check while one runs is skipped", 4, repo.calls.size)
        assertEquals(120_000L, RepliesMarker.POLL_MS)
    }

    // ---- A conversation -----------------------------------------------------------------------------------------------

    @Test
    fun `a private reply - send, then wait for an answer, and the screen reads where it stands`() = runTest {
        val repo = FakeRepliesRepository()
        repo.conversationResult = ApiResult.Success(conversation(c))
        val vm = ConversationViewModel(repo)
        vm.load(c)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canWrite)
        vm.send("   ")
        advanceUntilIdle()
        assertEquals(listOf("conversation:$c"), repo.calls)
        repo.conversationResult = ApiResult.Success(conversation(c, turn = Turn.WAITING, canSend = false,
            messages = listOf(message("sent", mine = true))))
        vm.send(" On my way ")
        runCurrent()
        assertEquals("send:$c:On my way", repo.calls[1])
        advanceUntilIdle()
        assertEquals("conversation:$c", repo.calls.last())
        assertFalse(vm.uiState.value.canWrite)
        assertTrue(vm.uiState.value.sent)
        assertEquals(listOf("sent"), vm.uiState.value.conversation!!.messages.map { it.id })
    }

    @Test
    fun `a refused message shows the server's words and reads the conversation again`() = runTest {
        val repo = FakeRepliesRepository()
        repo.conversationResult = ApiResult.Success(conversation(c))
        val vm = ConversationViewModel(repo)
        vm.load(c)
        advanceUntilIdle()
        repo.sendResult = ApiResult.Failure(ApiError.CONFLICT, "Wait for an answer before you write again")
        repo.conversationResult = ApiResult.Success(conversation(c, turn = Turn.WAITING, canSend = false))
        vm.send("again")
        advanceUntilIdle()
        assertEquals("Wait for an answer before you write again", vm.uiState.value.failure!!.message)
        assertFalse(vm.uiState.value.canWrite)
        assertFalse(vm.uiState.value.sent)
    }

    @Test
    fun `a new private reply starts with its first message, and a support line is shown once`() = runTest {
        val repo = FakeRepliesRepository()
        repo.startResult = ApiResult.Success(conversation("new", turn = Turn.WAITING, canSend = false,
            messages = listOf(message("first", mine = true, support = true))))
        val vm = ConversationViewModel(repo)
        vm.start(m, "Asha")
        assertTrue(vm.uiState.value.canWrite)
        vm.send("It has been a long week")
        advanceUntilIdle()
        assertEquals("start:$m:It has been a long week", repo.calls.first())
        assertEquals("new", vm.uiState.value.conversation!!.id)
        assertTrue(vm.uiState.value.support)
        vm.onSupportShown()
        vm.refresh()
        advanceUntilIdle()
        assertEquals("conversation:new", repo.calls.last())

        val refused = FakeRepliesRepository()
        refused.startResult = ApiResult.Failure(ApiError.FORBIDDEN, "Asha only takes private replies from their people")
        val other = ConversationViewModel(refused)
        other.start(m, "Asha")
        other.send("hi")
        advanceUntilIdle()
        assertEquals("Asha only takes private replies from their people", other.uiState.value.failure!!.message)
        assertTrue("the words stay to try later", other.uiState.value.canWrite)
    }

    @Test
    fun `ending a private reply keeps it readable, a vanished one reads as gone`() = runTest {
        val repo = FakeRepliesRepository()
        repo.conversationResult = ApiResult.Success(conversation(c))
        val vm = ConversationViewModel(repo)
        vm.load(c)
        advanceUntilIdle()
        repo.conversationResult = ApiResult.Success(conversation(c, turn = Turn.ENDED, canSend = false))
        vm.end()
        advanceUntilIdle()
        assertEquals(listOf("conversation:$c", "end:$c", "conversation:$c"), repo.calls)
        assertEquals(Turn.ENDED, vm.uiState.value.conversation!!.you.turn)
        repo.conversationResult = ApiResult.Failure(ApiError.NOT_FOUND)
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.gone)
        val quietRepo = FakeRepliesRepository()
        quietRepo.conversationResult = ApiResult.Success(conversation(c))
        val quiet = ConversationViewModel(quietRepo)
        quiet.load(c)
        advanceUntilIdle()
        quiet.onUncover(msg)
        assertEquals(setOf(msg), quiet.uiState.value.uncovered)
    }

    // ---- The setting and the moment page -----------------------------------------------------------------------------

    @Test
    fun `Private replies - read before choosing, saved only when changed`() = runTest {
        val repo = FakeRepliesRepository()
        val vm = PrivateRepliesViewModel(repo)
        vm.onChoose()
        advanceUntilIdle()
        assertEquals(PrivateReplies.MY_PEOPLE, vm.uiState.value.value)
        assertTrue(vm.uiState.value.choose)
        vm.onChooseShown()
        vm.onChosen(PrivateReplies.MY_PEOPLE)
        advanceUntilIdle()
        assertEquals(listOf("settings"), repo.calls)
        vm.onChosen(PrivateReplies.NOBODY)
        advanceUntilIdle()
        assertEquals("setSettings:NOBODY", repo.calls.last())
        assertEquals(PrivateReplies.NOBODY, vm.uiState.value.value)
        assertTrue(vm.uiState.value.saved)
        assertEquals(listOf(PrivateReplies.MY_PEOPLE, PrivateReplies.COMMUNITY, PrivateReplies.NOBODY), PrivateRepliesViewModel.CHOICES)
    }

    @Test
    fun `the moment page offers a private reply when the server allows one, and the plan to its members`() {
        val say = moment(m)
        assertFalse(ResponseLabels.actions(say).reply)
        assertTrue(ResponseLabels.actions(say.copy(responses = MomentResponses.NONE.copy(canReply = true))).reply)
        assertTrue("one's own reply always opens", ResponseLabels.actions(say.copy(status = com.safesphere.app.feature.moments.domain.MomentStatus.ENDED,
            responses = MomentResponses.NONE.copy(replyId = c))).reply)
        assertFalse(ResponseLabels.actions(moment(m, mine = true).copy(responses = MomentResponses.NONE.copy(canReply = true))).reply)
        val plan = moment(m, kind = MomentKind.WHOS_IN)
        assertTrue(ResponseLabels.actions(plan.copy(responses = MomentResponses.NONE.copy(plan = PlanState(PlanStanding.IN, false, false, c)))).openPlan)
        assertFalse(ResponseLabels.actions(plan.copy(responses = MomentResponses.NONE.copy(plan = PlanState(PlanStanding.ASKED, true, false, c)))).openPlan)
        assertTrue(ResponseLabels.actions(moment(m, mine = true, kind = MomentKind.WHOS_IN).copy(
            responses = MomentResponses.NONE.copy(plan = PlanState(PlanStanding.AUTHOR, true, false, c)))).openPlan)
    }

    // ---- Guards -------------------------------------------------------------------------------------------------------

    @Test
    fun `no words of Replies or conversations carry a number or a read state`() {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        val words = Regex("<string name=\"((?:replies|conversation|reply_sheet|private_replies)_[a-z0-9_]+)\"[^>]*>(.*?)</string>")
            .findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
        assertTrue(words.size > 50)
        for ((name, text) in words) {
            val plain = text.replace(Regex("%[0-9][$]s"), "")
            assertFalse(name, Regex("[0-9]").containsMatchIn(plain) || plain.contains("%d"))
            assertFalse(name, Regex("\\b(seen|read by|delivered|typing|online|last active|declined?)\\b").containsMatchIn(plain.lowercase()))
        }
    }

    @Test
    fun `Replies stores nothing, uses the one Retrofit and the dot is never a number`() {
        val root = File("src/main/java/com/safesphere/app/feature/replies")
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText(Charsets.UTF_8)
            for (bad in listOf("DataStore", "SharedPreferences", "openFileOutput", "Retrofit.Builder", "OkHttpClient(",
                    "feature.safety", "feature.emergency", "feature.safewalk", "feature.membership")) {
                assertFalse("${file.name}: $bad", text.contains(bad))
            }
        }
        val main = File("src/main/java/com/safesphere/app/MainActivity.kt").readText(Charsets.UTF_8)
        assertTrue(main.contains("clearNumber()"))
        assertFalse(main.contains("badge.number") || main.contains(".number ="))
        assertTrue(main.contains("RepliesMarker.POLL_MS"))
        val conversation = File("$root/ui/ConversationFragment.kt").readText(Charsets.UTF_8)
        assertTrue("screenshots blocked", conversation.contains("secure(true)"))
        assertTrue(File("$root/ui/RepliesFragment.kt").readText(Charsets.UTF_8).contains("secure(true)"))
    }
}
