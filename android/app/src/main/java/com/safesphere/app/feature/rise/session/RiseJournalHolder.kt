package com.safesphere.app.feature.rise.session

import com.safesphere.app.feature.rise.data.RiseLoad
import com.safesphere.app.feature.rise.data.RiseStore
import com.safesphere.app.feature.rise.domain.RiseChange
import com.safesphere.app.feature.rise.domain.RiseJournal
import com.safesphere.app.feature.rise.domain.RiseRefusal
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface RiseState {
    data object Loading : RiseState
    data object SignedOut : RiseState

    /** The account's Rise file exists but cannot be opened. */
    data class Unreadable(val userId: Long) : RiseState
    data class Ready(val userId: Long, val journal: RiseJournal) : RiseState
}

/**
 * The one holder of the signed-in person's Rise record (application scope). It loads the account's encrypted file
 * when the account changes and saves every change at once. Another account on the phone never sees it (its own file,
 * its own associated data). No network, no notification, no service, no alarm: Rise does nothing in the background.
 */
class RiseJournalHolder(
    scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val store: RiseStore,
    userIds: Flow<Long?>,
    private val today: () -> LocalDate = LocalDate::now
) {
    private val _state = MutableStateFlow<RiseState>(RiseState.Loading)
    val state: StateFlow<RiseState> = _state.asStateFlow()

    private val writes = Mutex()

    init {
        scope.launch {
            userIds.distinctUntilChanged().collectLatest { userId ->
                if (userId == null) {
                    _state.value = RiseState.SignedOut
                    return@collectLatest
                }
                _state.value = RiseState.Loading
                _state.value = when (val loaded = withContext(io) { store.load(userId) }) {
                    RiseLoad.Empty -> RiseState.Ready(userId, RiseJournal())
                    is RiseLoad.Loaded -> RiseState.Ready(userId, loaded.journal)
                    RiseLoad.Unreadable -> RiseState.Unreadable(userId)
                }
            }
        }
    }

    fun today(): LocalDate = today.invoke()

    /** Applies a pure change and saves it. Refused when not ready or when the phone could not save it. */
    suspend fun change(transform: (RiseJournal, LocalDate) -> RiseChange): RiseChange = writes.withLock {
        val ready = _state.value as? RiseState.Ready ?: return RiseChange.Refused(RiseRefusal.NOT_FOUND)
        val result = transform(ready.journal, today())
        if (result is RiseChange.Done) {
            val saved = runCatching { withContext(io) { store.save(ready.userId, result.journal) } }
            if (saved.isFailure) return RiseChange.Refused(RiseRefusal.NOT_SAVED)
            // The account may have changed while saving: only the same person's state is replaced.
            if ((_state.value as? RiseState.Ready)?.userId == ready.userId) _state.value = RiseState.Ready(ready.userId, result.journal)
        }
        result
    }

    /** Settings and the intro (always allowed). */
    suspend fun update(transform: (RiseJournal) -> RiseJournal): Boolean =
        change { journal, _ -> RiseChange.Done(transform(journal)) } is RiseChange.Done

    /** "Delete all Rise data" (and "start again" when the file could not be opened). */
    suspend fun deleteAll(): Boolean = writes.withLock {
        val userId = when (val state = _state.value) {
            is RiseState.Ready -> state.userId
            is RiseState.Unreadable -> state.userId
            else -> return false
        }
        val deleted = runCatching { withContext(io) { store.delete(userId) } }.isSuccess
        if (deleted) _state.value = RiseState.Ready(userId, RiseJournal())
        deleted
    }
}

/**
 * Rise's opening lock (the person's choice, [RiseJournal.lockWithScreenLock]): unlocked for ONE person until Rise is
 * left (no Rise screen visible for [LEAVE_GRACE_MILLIS]: home button, screen off, another tab) or the account changes.
 * Moving between Rise's own screens keeps it open. Like the vault's, an app gate, not encryption bound to the lock.
 */
class RiseLock(private val scope: CoroutineScope, userIds: Flow<Long?>) {
    private val _unlockedFor = MutableStateFlow<Long?>(null)
    val unlockedFor: StateFlow<Long?> = _unlockedFor.asStateFlow()

    private var visibleScreens = 0
    private var pendingLock: Job? = null

    init {
        scope.launch { userIds.distinctUntilChanged().collect { lock() } }
    }

    fun unlock(userId: Long) { _unlockedFor.value = userId }

    fun lock() {
        pendingLock?.cancel()
        _unlockedFor.value = null
    }

    /** A Rise screen became visible (onStart). */
    fun onRiseVisible() {
        visibleScreens++
        pendingLock?.cancel()
    }

    /** A Rise screen went away (onStop). The next Rise screen usually starts within this grace. */
    fun onRiseHidden() {
        visibleScreens = (visibleScreens - 1).coerceAtLeast(0)
        if (visibleScreens > 0) return
        pendingLock?.cancel()
        pendingLock = scope.launch {
            delay(LEAVE_GRACE_MILLIS)
            if (visibleScreens == 0) _unlockedFor.value = null
        }
    }

    companion object {
        const val LEAVE_GRACE_MILLIS = 1_000L
    }
}
