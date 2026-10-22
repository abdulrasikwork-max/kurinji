package com.safesphere.app.feature.rise

import com.safesphere.app.core.ui.ScreenLockGate
import com.safesphere.app.feature.rise.data.EncryptedFileRiseStore
import com.safesphere.app.feature.rise.data.RiseLoad
import com.safesphere.app.feature.rise.data.RiseStore
import com.safesphere.app.feature.rise.domain.Expectation
import com.safesphere.app.feature.rise.domain.GoalPolicy
import com.safesphere.app.feature.rise.domain.Outcome
import com.safesphere.app.feature.rise.domain.Preparation
import com.safesphere.app.feature.rise.domain.ProofPolicy
import com.safesphere.app.feature.rise.domain.RiseChange
import com.safesphere.app.feature.rise.domain.RiseDomain
import com.safesphere.app.feature.rise.domain.RiseJournal
import com.safesphere.app.feature.rise.domain.RiseRefusal
import com.safesphere.app.feature.rise.domain.SelfDoubt
import com.safesphere.app.feature.rise.domain.Support
import com.safesphere.app.feature.rise.session.RiseJournalHolder
import com.safesphere.app.feature.rise.session.RiseLock
import com.safesphere.app.feature.rise.session.RiseState
import com.safesphere.app.feature.rise.ui.RiseScreen
import com.safesphere.app.feature.rise.ui.RiseViewModel
import com.safesphere.app.testing.FakeKeyWrapper
import com.safesphere.app.testing.MainDispatcherRule
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Phase 10B Rise "Proof": goal rules, the proof replay, the encrypted per-account file, the holder and the lock. */
@OptIn(ExperimentalCoroutinesApi::class)
class RiseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val today = LocalDate.parse("2026-10-22")

    // ---- GoalPolicy ---------------------------------------------------------------------------------------------

    private fun journalWithGoal(steps: String = "One\nTwo"): Pair<RiseJournal, Long> {
        val done = GoalPolicy.addGoal(RiseJournal(), RiseDomain.LEARN, "  A hackathon  ", steps, today) as RiseChange.Done
        return done.journal to done.id!!
    }

    @Test
    fun `a goal keeps the person's steps in order, trims words and limits length and count`() {
        val (journal, goalId) = journalWithGoal("\n  Try a lesson \n\nMake one thing\n" + "x".repeat(200))
        val goal = journal.goal(goalId)!!
        assertEquals("A hackathon", goal.words)
        assertEquals(listOf("Try a lesson", "Make one thing", "x".repeat(80)), goal.steps.map { it.words })
        val many = GoalPolicy.addGoal(RiseJournal(), RiseDomain.OTHER, "", (1..30).joinToString("\n") { "s$it" }, today) as RiseChange.Done
        assertEquals(GoalPolicy.MAX_STEPS, many.journal.goals.single().steps.size)
        assertEquals(RiseChange.Refused(RiseRefusal.NO_STEPS), GoalPolicy.addGoal(RiseJournal(), RiseDomain.GO, "x", " \n ", today))
        // Ids never repeat across goals, steps and tries.
        val ids = journal.goals.flatMap { g -> listOf(g.id) + g.steps.map { it.id } }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `at most 20 goals, and one try at a time per step`() {
        var journal = RiseJournal()
        repeat(GoalPolicy.MAX_GOALS) { journal = (GoalPolicy.addGoal(journal, RiseDomain.MOVE, "", "Go once", today) as RiseChange.Done).journal }
        assertEquals(RiseChange.Refused(RiseRefusal.TOO_MANY_GOALS), GoalPolicy.addGoal(journal, RiseDomain.MOVE, "", "Go", today))

        val (one, goalId) = journalWithGoal()
        val stepId = one.goal(goalId)!!.steps.first().id
        val trying = (GoalPolicy.startTry(one, goalId, stepId, Preparation(), today) as RiseChange.Done).journal
        assertEquals(RiseChange.Refused(RiseRefusal.ALREADY_TRYING), GoalPolicy.startTry(trying, goalId, stepId, Preparation(), today))
        assertEquals(RiseChange.Refused(RiseRefusal.NOT_TRYING), GoalPolicy.recordOutcome(one, goalId, stepId, Outcome.BETTER, false, today))
    }

    @Test
    fun `a first is kept only for a step that was done, and the next step is simply the next one`() {
        val (journal, goalId) = journalWithGoal()
        val goal = journal.goal(goalId)!!
        val step = goal.steps.first()
        var j = (GoalPolicy.startTry(journal, goalId, step.id, Preparation(), today) as RiseChange.Done).journal
        j = (GoalPolicy.recordOutcome(j, goalId, step.id, Outcome.DID_NOT_GO_AHEAD, first = true, today) as RiseChange.Done).journal
        assertFalse(j.goal(goalId)!!.steps.first().attempts.single().first)
        assertEquals(goal.steps[1].id, GoalPolicy.nextStep(goal, step.id)?.id)
        assertNull(GoalPolicy.nextStep(goal, goal.steps[1].id))
        // Putting aside and deleting change nothing else.
        val aside = (GoalPolicy.setAside(j, goalId, true) as RiseChange.Done).journal
        assertEquals(j.goals.single().steps, aside.goals.single().steps)
        assertTrue((GoalPolicy.deleteGoal(aside, goalId) as RiseChange.Done).journal.goals.isEmpty())
    }

    // ---- ProofPolicy --------------------------------------------------------------------------------------------

    private fun tried(journal: RiseJournal, goalId: Long, stepIndex: Int, prep: Preparation, outcome: Outcome): RiseJournal {
        val stepId = journal.goal(goalId)!!.steps[stepIndex].id
        val started = (GoalPolicy.startTry(journal, goalId, stepId, prep, today) as RiseChange.Done).journal
        return (GoalPolicy.recordOutcome(started, goalId, stepId, outcome, true, today) as RiseChange.Done).journal
    }

    @Test
    fun `the replay counts only earlier tries with the same self-doubt, and never something that wasn't OK`() {
        var (journal, goalId) = journalWithGoal("A\nB\nC\nD\nE")
        val onMyOwn = Preparation(Expectation.NOT_SURE, setOf(SelfDoubt.ON_MY_OWN))
        journal = tried(journal, goalId, 0, onMyOwn, Outcome.BETTER)
        journal = tried(journal, goalId, 1, onMyOwn, Outcome.HARDER)
        journal = tried(journal, goalId, 2, onMyOwn, Outcome.DID_NOT_GO_AHEAD)
        journal = tried(journal, goalId, 3, onMyOwn, Outcome.NOT_OK)
        journal = tried(journal, goalId, 4, Preparation(doubts = setOf(SelfDoubt.WHAT_OTHERS_THINK)), Outcome.BETTER)

        val replay = ProofPolicy.replayFor(journal, setOf(SelfDoubt.ON_MY_OWN))!!
        assertEquals(3, replay.times)
        assertEquals(1, replay.better)
        assertEquals(1, replay.harder)
        assertEquals(1, replay.notAhead)
        assertEquals(2, replay.didIt)
        assertNull(ProofPolicy.replayFor(journal, setOf(SelfDoubt.NEVER_DONE_THIS)))
        assertNull(ProofPolicy.replayFor(journal, emptySet()))
    }

    @Test
    fun `a safety worry alone never gets a replay, and mostly-harder records are said honestly`() {
        var (journal, goalId) = journalWithGoal("A\nB\nC")
        journal = tried(journal, goalId, 0, Preparation(safetyConcern = true), Outcome.BETTER)
        assertNull("a worry about safety is not a doubt to disprove", ProofPolicy.replayFor(journal, emptySet()))
        val doubt = Preparation(doubts = setOf(SelfDoubt.CAN_I))
        journal = tried(journal, goalId, 1, doubt, Outcome.HARDER)
        journal = tried(journal, goalId, 2, doubt, Outcome.HARDER)
        assertTrue(ProofPolicy.replayFor(journal, setOf(SelfDoubt.CAN_I))!!.mostlyHarder)
    }

    @Test
    fun `proof lists what was done, newest first, and the summary counts unsure tries that went better`() {
        var (journal, goalId) = journalWithGoal("A\nB\nC")
        journal = tried(journal, goalId, 0, Preparation(Expectation.CANT, supports = setOf(Support.WITH_SOMEONE)), Outcome.BETTER)
        journal = tried(journal, goalId, 1, Preparation(Expectation.NOT_SURE), Outcome.HARDER)
        journal = tried(journal, goalId, 2, Preparation(Expectation.CONFIDENT), Outcome.DID_NOT_GO_AHEAD)
        val proof = ProofPolicy.proof(journal)
        assertEquals(listOf("B", "A"), proof.map { it.stepWords })
        assertEquals(setOf(Support.WITH_SOMEONE), proof.last().supports)
        assertTrue(proof.all { it.first })
        val summary = ProofPolicy.summary(journal.goal(goalId)!!)
        assertEquals(2, summary.unsure)
        assertEquals(1, summary.unsureBetter)
        assertEquals(2, summary.done)
        assertEquals(2, ProofPolicy.firsts(journal))
    }

    // ---- EncryptedFileRiseStore ---------------------------------------------------------------------------------

    private fun tempDir(): File = Files.createTempDirectory("rise").toFile().also { it.deleteOnExit() }

    @Test
    fun `the file round-trips, holds no readable words, and is bound to its account`() = runBlocking {
        val dir = tempDir()
        val store = EncryptedFileRiseStore(dir, FakeKeyWrapper())
        var (journal, goalId) = journalWithGoal("Ask one question")
        journal = tried(journal, goalId, 0, Preparation(Expectation.CANT, setOf(SelfDoubt.NOT_FOR_SOMEONE_LIKE_ME)), Outcome.BETTER)
            .copy(quietMode = true, introSeen = true)
        store.save(7, journal)
        assertEquals(RiseLoad.Loaded(journal), store.load(7))

        val bytes = File(dir, "rise-7.bin").readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("hackathon"))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("LEARN"))
        // Another account on the phone: no file of its own -> empty; this account's file renamed -> unreadable (AAD).
        assertEquals(RiseLoad.Empty, store.load(8))
        File(dir, "rise-7.bin").copyTo(File(dir, "rise-9.bin"))
        assertEquals(RiseLoad.Unreadable, store.load(9))
    }

    @Test
    fun `a damaged file falls back to the previous version, and both damaged is unreadable, never silently empty`() = runBlocking {
        val dir = tempDir()
        val store = EncryptedFileRiseStore(dir, FakeKeyWrapper())
        val (first, _) = journalWithGoal("First")
        val (second, _) = journalWithGoal("Second")
        store.save(7, first)
        store.save(7, second)
        val main = File(dir, "rise-7.bin")
        main.writeBytes(main.readBytes().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() })
        assertEquals(RiseLoad.Loaded(first), store.load(7))
        File(dir, "rise-7.bin.bak").writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(RiseLoad.Unreadable, store.load(7))

        store.delete(7)
        assertEquals(RiseLoad.Empty, store.load(7))
        assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("rise-7") })
    }

    @Test
    fun `a lost phone key makes the file unreadable instead of crashing`() = runBlocking {
        val dir = tempDir()
        val wrapper = FakeKeyWrapper()
        EncryptedFileRiseStore(dir, wrapper).save(7, journalWithGoal().first)
        assertEquals(RiseLoad.Unreadable, EncryptedFileRiseStore(dir, FakeKeyWrapper()).load(7))
    }

    // ---- holder, lock, screen gating ----------------------------------------------------------------------------

    private class MemoryStore : RiseStore {
        val files = HashMap<Long, RiseJournal>()
        var failSaves = false
        override suspend fun load(userId: Long): RiseLoad = files[userId]?.let { RiseLoad.Loaded(it) } ?: RiseLoad.Empty
        override suspend fun save(userId: Long, journal: RiseJournal) {
            if (failSaves) throw java.io.IOException("disk full")
            files[userId] = journal
        }
        override suspend fun delete(userId: Long) { files.remove(userId) }
    }

    @Test
    fun `each account sees only its own Rise, a sign-out shows nothing, and a failed save changes nothing`() = runTest {
        val store = MemoryStore().apply { files[7] = journalWithGoal().first }
        val user = MutableStateFlow<Long?>(7)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val holder = RiseJournalHolder(backgroundScope, dispatcher, store, user) { today }
        runCurrent()
        assertEquals(1, (holder.state.value as RiseState.Ready).journal.goals.size)

        user.value = 8
        runCurrent()
        assertTrue((holder.state.value as RiseState.Ready).journal.goals.isEmpty())
        user.value = null
        runCurrent()
        assertEquals(RiseState.SignedOut, holder.state.value)

        user.value = 7
        runCurrent()
        store.failSaves = true
        val refused = holder.change { j, d -> GoalPolicy.addGoal(j, RiseDomain.GO, "", "Go", d) }
        assertEquals(RiseChange.Refused(RiseRefusal.NOT_SAVED), refused)
        assertEquals(1, (holder.state.value as RiseState.Ready).journal.goals.size)

        store.failSaves = false
        assertTrue(holder.deleteAll())
        assertTrue(store.files[7] == null)
        assertTrue((holder.state.value as RiseState.Ready).journal.goals.isEmpty())
    }

    @Test
    fun `nothing personal is shown before the intro and, if chosen, the screen lock`() = runTest {
        val store = MemoryStore()
        val user = MutableStateFlow<Long?>(7)
        val holder = RiseJournalHolder(backgroundScope, StandardTestDispatcher(testScheduler), store, user) { today }
        val lock = RiseLock(backgroundScope, user)
        val viewModel = RiseViewModel(holder, lock)
        runCurrent()
        advanceUntilIdle()
        assertEquals(RiseScreen.Intro, viewModel.screen.value)

        viewModel.finishIntro(lockWithScreenLock = true)
        runCurrent()
        advanceUntilIdle()
        assertTrue("just chose the lock: stays open", viewModel.screen.value is RiseScreen.Ready)

        // Leaving Rise (no Rise screen visible for the grace) locks it; coming back within the grace does not.
        viewModel.onRiseVisible()
        viewModel.onRiseHidden()
        viewModel.onRiseVisible()
        advanceTimeBy(RiseLock.LEAVE_GRACE_MILLIS + 1)
        runCurrent()
        assertTrue(viewModel.screen.value is RiseScreen.Ready)
        viewModel.onRiseHidden()
        advanceTimeBy(RiseLock.LEAVE_GRACE_MILLIS + 1)
        runCurrent()
        assertEquals(RiseScreen.Locked, viewModel.screen.value)

        viewModel.onScreenLockResult(ScreenLockGate.Result.CANCELLED)
        runCurrent()
        assertEquals(RiseScreen.Locked, viewModel.screen.value)
        viewModel.onScreenLockResult(ScreenLockGate.Result.CONFIRMED)
        runCurrent()
        assertTrue(viewModel.screen.value is RiseScreen.Ready)

        // Another account: locked again; its own Rise starts with the intro.
        user.value = 8
        runCurrent()
        advanceUntilIdle()
        assertEquals(RiseScreen.Intro, viewModel.screen.value)
        assertNotNull(store.files[7])
    }
}
