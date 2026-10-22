package com.safesphere.app.feature.rise.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.ui.ScreenLockGate
import com.safesphere.app.feature.rise.domain.GoalPolicy
import com.safesphere.app.feature.rise.domain.Outcome
import com.safesphere.app.feature.rise.domain.Preparation
import com.safesphere.app.feature.rise.domain.ProofPolicy
import com.safesphere.app.feature.rise.domain.Replay
import com.safesphere.app.feature.rise.domain.RiseChange
import com.safesphere.app.feature.rise.domain.RiseDomain
import com.safesphere.app.feature.rise.domain.RiseJournal
import com.safesphere.app.feature.rise.domain.RiseRefusal
import com.safesphere.app.feature.rise.domain.SelfDoubt
import com.safesphere.app.feature.rise.session.RiseJournalHolder
import com.safesphere.app.feature.rise.session.RiseLock
import com.safesphere.app.feature.rise.session.RiseState
import java.time.LocalDate
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What a Rise screen may show. Words are never shown before [Ready] (lock first, intro first). */
sealed interface RiseScreen {
    data object Loading : RiseScreen
    data object SignedOut : RiseScreen
    data object Intro : RiseScreen
    data object Locked : RiseScreen
    data object Unreadable : RiseScreen
    data class Ready(val journal: RiseJournal) : RiseScreen
}

sealed interface RiseEvent {
    data class GoalSaved(val goalId: Long) : RiseEvent
    data class Refused(val reason: RiseRefusal) : RiseEvent
    data object NoScreenLock : RiseEvent
}

/** One ViewModel class for every Rise screen; each screen has its own instance over the ONE [RiseJournalHolder]. */
class RiseViewModel(private val holder: RiseJournalHolder, private val lock: RiseLock) : ViewModel() {

    val screen: StateFlow<RiseScreen> = combine(holder.state, lock.unlockedFor) { state, unlockedFor ->
        when (state) {
            RiseState.Loading -> RiseScreen.Loading
            RiseState.SignedOut -> RiseScreen.SignedOut
            is RiseState.Unreadable -> RiseScreen.Unreadable
            is RiseState.Ready -> when {
                !state.journal.introSeen -> RiseScreen.Intro
                state.journal.lockWithScreenLock && unlockedFor != state.userId -> RiseScreen.Locked
                else -> RiseScreen.Ready(state.journal)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RiseScreen.Loading)

    private val _events = MutableSharedFlow<RiseEvent>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<RiseEvent> = _events.asSharedFlow()

    /**
     * The step screen's unsaved choices (they survive a rotation with this ViewModel; process death loses only them).
     * [savedOutcome] = the outcome just saved, so the screen shows the proof card / "That wasn't your fault" next.
     */
    var draft: Preparation = Preparation()
    var draftOutcome: Outcome? = null
    var draftFirst: Boolean = false
    var savedOutcome: Outcome? = null

    private fun currentUser(): Long? = (holder.state.value as? RiseState.Ready)?.userId

    // ---- intro, lock, settings -------------------------------------------------------------------------------

    fun finishIntro(lockWithScreenLock: Boolean) {
        val user = currentUser() ?: return
        // Choosing the lock right here should not lock the person out of what they just opened.
        if (lockWithScreenLock) lock.unlock(user)
        viewModelScope.launch { holder.update { it.copy(introSeen = true, lockWithScreenLock = lockWithScreenLock) } }
    }

    fun onScreenLockResult(result: ScreenLockGate.Result) {
        val user = currentUser() ?: return
        when (result) {
            ScreenLockGate.Result.CONFIRMED -> lock.unlock(user)
            ScreenLockGate.Result.NO_SCREEN_LOCK -> {
                lock.unlock(user)
                _events.tryEmit(RiseEvent.NoScreenLock)
            }
            ScreenLockGate.Result.CANCELLED -> Unit
        }
    }

    fun onRiseVisible() = lock.onRiseVisible()
    fun onRiseHidden() = lock.onRiseHidden()

    fun setQuietMode(quiet: Boolean) {
        viewModelScope.launch { holder.update { it.copy(quietMode = quiet) } }
    }

    fun setLockWithScreenLock(enabled: Boolean) {
        val user = currentUser() ?: return
        if (enabled) lock.unlock(user) // already inside: stays open until Rise is left
        viewModelScope.launch { holder.update { it.copy(lockWithScreenLock = enabled) } }
    }

    fun deleteAll() {
        viewModelScope.launch {
            if (!holder.deleteAll()) _events.tryEmit(RiseEvent.Refused(RiseRefusal.NOT_SAVED))
        }
    }

    // ---- goals, tries, outcomes ------------------------------------------------------------------------------

    /** Only a new goal leads somewhere (its own screen); every other change stays on the screen that made it. */
    fun addGoal(domain: RiseDomain, words: String, stepLines: String) =
        applyChange({ journal, today -> GoalPolicy.addGoal(journal, domain, words, stepLines, today) }) { done ->
            done.id?.let { _events.tryEmit(RiseEvent.GoalSaved(it)) }
        }

    fun startTry(goalId: Long, stepId: Long, preparation: Preparation) = applyChange({ journal, today ->
        GoalPolicy.startTry(journal, goalId, stepId, preparation, today)
    })

    fun recordOutcome(goalId: Long, stepId: Long, outcome: Outcome, first: Boolean) = applyChange({ journal, today ->
        GoalPolicy.recordOutcome(journal, goalId, stepId, outcome, first, today)
    })

    fun setAside(goalId: Long, aside: Boolean) = applyChange({ journal, _ -> GoalPolicy.setAside(journal, goalId, aside) })

    fun deleteGoal(goalId: Long) = applyChange({ journal, _ -> GoalPolicy.deleteGoal(journal, goalId) })

    /** The person's own record for the self-doubts picked right now (null = nothing to show). */
    fun replayFor(doubts: Set<SelfDoubt>): Replay? =
        (screen.value as? RiseScreen.Ready)?.journal?.let { ProofPolicy.replayFor(it, doubts) }

    private fun applyChange(change: (RiseJournal, LocalDate) -> RiseChange, onDone: (RiseChange.Done) -> Unit = {}) {
        viewModelScope.launch {
            when (val result = holder.change(change)) {
                is RiseChange.Refused -> _events.tryEmit(RiseEvent.Refused(result.reason))
                is RiseChange.Done -> onDone(result)
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { RiseViewModel(container.riseJournal, container.riseLock) }
        }
    }
}
