package com.safesphere.app.feature.emergency.session

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.emergency.domain.ActiveEmergencyRecord
import com.safesphere.app.feature.emergency.domain.ActiveEmergencyStore
import com.safesphere.app.feature.emergency.domain.EmergencyEvent
import com.safesphere.app.feature.emergency.domain.EmergencyIds
import com.safesphere.app.feature.emergency.domain.EmergencyRepository
import com.safesphere.app.feature.emergency.domain.EmergencyStatus
import com.safesphere.app.feature.emergency.domain.LocalEnd
import com.safesphere.app.feature.emergency.domain.SosRecordRequest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Something that keeps per-emergency state under the emergency's id (location sharing, Safety Circle alerts). */
interface EmergencyRebinder {
    /** The server now knows the SOS kept under [localId]: from here on it is [serverId]. */
    fun rebindEmergency(localId: Long, serverId: Long)
}

/**
 * @param current the SOS running on this phone (null = none), with a local id until the server has it
 * @param unsent  SOS that ENDED on this phone before the server was told (oldest first)
 * @param lastServerAnswer the server's view of the SOS it last recorded or ended (for the screen)
 * @param refused the server will not record [current] (not a connection problem); the phone stops asking
 */
data class SosRecorderState(
    val loaded: Boolean = false,
    val current: ActiveEmergencyRecord? = null,
    val unsent: List<ActiveEmergencyRecord> = emptyList(),
    val lastServerAnswer: EmergencyEvent? = null,
    val refused: Boolean = false
)

/**
 * Phase 11, "SOS without internet": the one holder that starts an SOS on the phone and records it on the server
 * whenever the server can be reached (application scope, single-threaded main, like the other holders).
 *
 * Why: before Phase 11 an SOS existed only once the server had answered. Without mobile data (no balance, a
 * basement, a rural road, an overloaded network) the SOS screen only said "could not record": the Safety Circle got
 * no SMS although SMS needs no internet, and no location was taken. Now the countdown's end starts the SOS HERE at
 * once (location sharing and the Safety Circle work offline) and this class tells the server later:
 *  - every SOS gets a random reference; the server answers the same reference with the same emergency, so a lost
 *    answer or a retry hours later never makes a second one;
 *  - the first attempt sends no phone time (online = the server's own time, as before); later attempts send the
 *    phone's start time (and end, if it also ended offline); the server clamps them;
 *  - when the server's id arrives, [EmergencyRebinder]s move their state from the local id to it;
 *  - an SOS ended while offline waits in [SosRecorderState.unsent] and is told to the server later.
 * Kept on disk: ids, the reference, times, a "112 tapped" flag. Never a location or a number. A sign-out clears it.
 */
class SosRecorder(
    private val scope: CoroutineScope,
    private val store: ActiveEmergencyStore,
    private val repository: EmergencyRepository,
    isOnline: Flow<Boolean>,
    session: Flow<AuthSession?>,
    private val rebinders: () -> List<EmergencyRebinder>,
    /** Level 4 for an SOS whose "Call 112" was tapped before the server had it. */
    private val onDialPromptRecorded: (Long) -> Unit,
    private val now: () -> Instant = Instant::now,
    private val newReference: () -> String = { UUID.randomUUID().toString() }
) {

    private val _state = MutableStateFlow(SosRecorderState())
    val state: StateFlow<SosRecorderState> = _state.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var signedInUser: Long? = null
    private var attemptsForCurrent = 0

    init {
        scope.launch {
            val current = store.load()
            val unsent = store.loadUnsent()
            _state.value = SosRecorderState(loaded = true, current = current, unsent = unsent)
            loaded.complete(Unit)
            signal.trySend(Unit)
        }
        scope.launch {
            session.collect { session ->
                val previous = signedInUser
                signedInUser = session?.user?.id
                // A sign-out clears the store (AppContainer); here the memory goes too. Phase 11 C1: so does another
                // account signing in on this phone (its clean-up ran first). An expired token keeps everything: the
                // session stays, and the SOS is recorded as soon as a refresh or a new sign-in gives a usable token.
                val switched = previous != null && session != null && session.user.id != previous
                if ((session == null || switched) && loaded.isCompleted) _state.value = SosRecorderState(loaded = true)
                signal.trySend(Unit)
            }
        }
        scope.launch { isOnline.collect { online -> if (online) signal.trySend(Unit) } }
        scope.launch { syncLoop() }
    }

    /** The SOS running on this phone for [userId], once the record has been read (null = none). */
    suspend fun current(userId: Long): ActiveEmergencyRecord? {
        loaded.await()
        return _state.value.current?.takeIf { it.userId == userId }
    }

    /** The countdown reached zero: the SOS starts on this phone now and is recorded on the server when possible. */
    suspend fun startLocal(userId: Long): ActiveEmergencyRecord {
        loaded.await()
        val reference = newReference()
        val record = ActiveEmergencyRecord(
            userId = userId, emergencyId = EmergencyIds.localIdFor(reference), startedAt = now(),
            clientRef = reference, onServer = false
        )
        attemptsForCurrent = 0
        _state.value = _state.value.copy(current = record, refused = false)
        store.save(record)
        signal.trySend(Unit)
        return record
    }

    /** A running SOS the server already knows (recovery, or one started on another phone) is shown here. */
    suspend fun adopt(userId: Long, event: EmergencyEvent) {
        loaded.await()
        val current = _state.value.current
        if (current != null && current.userId == userId && current.emergencyId == event.id) return
        // A local SOS still waiting for the server is not replaced by a different one.
        if (current != null && current.userId == userId && !current.onServer) return
        val record = ActiveEmergencyRecord(userId, event.id, event.startedAt)
        _state.value = _state.value.copy(current = record, refused = false)
        store.save(record)
    }

    /**
     * The person ended the running SOS on this phone, but the server cannot be told now (it does not have the SOS
     * yet, or it cannot be reached). The end is kept and told to the server later, with its real time.
     */
    suspend fun endLocally(resolved: Boolean): ActiveEmergencyRecord? {
        loaded.await()
        val current = _state.value.current ?: return null
        val ended = current.copy(ended = LocalEnd(resolved, now()))
        val unsent = (_state.value.unsent + ended).takeLast(MAX_UNSENT)
        _state.value = _state.value.copy(current = null, unsent = unsent)
        store.clear()
        store.saveUnsent(unsent)
        signal.trySend(Unit)
        return ended
    }

    /** The running SOS ended and the server knows it (or it ended elsewhere): nothing is left to remember. */
    suspend fun forget() {
        loaded.await()
        if (_state.value.current == null) return
        _state.value = _state.value.copy(current = null, refused = false)
        store.clear()
    }

    /** "Call 112" was tapped. Recorded on the server for the SOS (now, or once the server has it). */
    fun onDialPromptOpened() {
        val current = _state.value.current ?: return
        if (current.onServer) {
            onDialPromptRecorded(current.emergencyId)
            return
        }
        if (current.dialPromptOpened) return
        val marked = current.copy(dialPromptOpened = true)
        _state.value = _state.value.copy(current = marked)
        scope.launch { store.save(marked) }
    }

    // ---- recording on the server -------------------------------------------------------------------------------

    private enum class Step { DONE, RETRY, WAIT }

    private suspend fun syncLoop() {
        loaded.await()
        var retryDelay = INITIAL_RETRY_MILLIS
        while (true) {
            val work = nextWork()
            if (work == null) {
                signal.receive()
                continue
            }
            when (sync(work)) {
                Step.DONE -> retryDelay = INITIAL_RETRY_MILLIS
                Step.RETRY -> {
                    // Wait for the backoff, or less if the connection comes back first. A wake-up that arrived
                    // before this failure is stale (it must not skip the backoff).
                    signal.tryReceive()
                    withTimeoutOrNull(retryDelay) { signal.receive() }
                    retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_MILLIS)
                }
                Step.WAIT -> signal.receive()
            }
        }
    }

    /** The running SOS first (it matters most), then endings in the order they happened. Only the signed-in person's. */
    private fun nextWork(): ActiveEmergencyRecord? {
        val user = signedInUser ?: return null
        val state = _state.value
        state.current?.takeIf { it.userId == user && !it.onServer && !state.refused }?.let { return it }
        return state.unsent.firstOrNull { it.userId == user }
    }

    private suspend fun sync(work: ActiveEmergencyRecord): Step {
        val reference = work.clientRef
        if (!work.onServer) {
            if (reference == null) return dropUnsent(work) // cannot happen: local records always have a reference
            val isCurrent = work.running
            val sendPhoneTimes = !isCurrent || attemptsForCurrent > 0 ||
                Duration.between(work.startedAt, now()) > PHONE_TIME_AFTER
            if (isCurrent) attemptsForCurrent++
            val result = repository.record(
                SosRecordRequest(reference, startedAt = work.startedAt.takeIf { sendPhoneTimes }, ended = work.ended)
            )
            return when (result) {
                is ApiResult.Success -> {
                    bind(work, result.data)
                    Step.DONE
                }
                is ApiResult.Failure -> when (result.error) {
                    // Another SOS is already running for this person (another phone): this one is the same emergency.
                    ApiError.CONFLICT -> if (isCurrent) joinActive(work) else Step.RETRY
                    ApiError.UNAUTHORIZED -> Step.WAIT // the session is gone; a sign-out clears the records
                    in RETRYABLE -> Step.RETRY
                    else -> {
                        if (isCurrent) _state.value = _state.value.copy(refused = true) else dropUnsent(work)
                        Step.DONE
                    }
                }
            }
        }
        // The server has it; the person ended it while the server could not be told.
        val end = work.ended ?: return dropUnsent(work)
        val result = if (end.resolved) {
            repository.resolve(work.emergencyId, password = null, endedAt = end.at)
        } else {
            repository.cancel(work.emergencyId, password = null, endedAt = end.at)
        }
        return when (result) {
            is ApiResult.Success -> {
                _state.value = _state.value.copy(lastServerAnswer = result.data)
                dropUnsent(work)
            }
            is ApiResult.Failure -> when (result.error) {
                ApiError.UNAUTHORIZED -> Step.WAIT
                in RETRYABLE -> Step.RETRY
                // Already ended, gone, or started on another phone (needs the password there): nothing more to say.
                else -> dropUnsent(work)
            }
        }
    }

    /** The server answered with its emergency for [work]: move everything to the server's id. */
    private suspend fun bind(work: ActiveEmergencyRecord, event: EmergencyEvent) {
        val localId = work.emergencyId
        val state = _state.value
        val current = state.current?.takeIf { it.clientRef == work.clientRef }
        val unsentIndex = state.unsent.indexOfFirst { it.clientRef == work.clientRef }
        rebinders().forEach { it.rebindEmergency(localId, event.id) }
        when {
            current != null -> {
                if (current.dialPromptOpened && event.status == EmergencyStatus.ACTIVE) onDialPromptRecorded(event.id)
                val bound = current.copy(emergencyId = event.id, startedAt = event.startedAt, onServer = true, dialPromptOpened = false)
                _state.value = state.copy(current = bound, lastServerAnswer = event)
                store.save(bound)
            }
            unsentIndex >= 0 -> {
                val record = state.unsent[unsentIndex]
                if (record.dialPromptOpened && event.status == EmergencyStatus.ACTIVE) onDialPromptRecorded(event.id)
                val bound = record.copy(emergencyId = event.id, onServer = true, dialPromptOpened = false)
                // Stored as already ended: done. Stored ACTIVE (an earlier attempt got through): end it next.
                val unsent = if (event.status == EmergencyStatus.ACTIVE) {
                    state.unsent.toMutableList().also { it[unsentIndex] = bound }
                } else {
                    state.unsent.filterIndexed { index, _ -> index != unsentIndex }
                }
                _state.value = state.copy(unsent = unsent, lastServerAnswer = event)
                store.saveUnsent(unsent)
            }
            else -> Unit // signed out meanwhile
        }
    }

    /** The server already has a running SOS for this person: show and keep that one (it is the same emergency). */
    private suspend fun joinActive(work: ActiveEmergencyRecord): Step {
        val history = repository.getHistory()
        val active = (history as? ApiResult.Success)?.data?.firstOrNull { it.status == EmergencyStatus.ACTIVE }
            ?: return Step.RETRY
        bind(work, active)
        return Step.DONE
    }

    private suspend fun dropUnsent(work: ActiveEmergencyRecord): Step {
        val unsent = _state.value.unsent.filterNot { it.clientRef == work.clientRef && it.emergencyId == work.emergencyId }
        _state.value = _state.value.copy(unsent = unsent)
        store.saveUnsent(unsent)
        return Step.DONE
    }

    companion object {
        const val INITIAL_RETRY_MILLIS = 5_000L
        const val MAX_RETRY_MILLIS = 60_000L
        const val MAX_UNSENT = 5

        /** Later than this after the start, the phone's own start time is sent (the SOS began offline). */
        val PHONE_TIME_AFTER: Duration = Duration.ofSeconds(60)

        private val RETRYABLE = setOf(ApiError.NO_CONNECTION, ApiError.SERVER, ApiError.UNKNOWN, ApiError.TOO_MANY_ATTEMPTS)
    }
}
