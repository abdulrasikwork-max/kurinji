package com.safesphere.app.feature.safewalk.session

import com.safesphere.app.core.device.ConnectivityMonitor
import com.safesphere.app.core.escalation.EscalationLevel
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.safewalk.domain.ActiveJourneyRecord
import com.safesphere.app.feature.safewalk.domain.ActiveJourneyStore
import com.safesphere.app.feature.safewalk.domain.CheckInPhase
import com.safesphere.app.feature.safewalk.domain.CheckInPolicy
import com.safesphere.app.feature.safewalk.domain.Journey
import com.safesphere.app.feature.safewalk.domain.JourneyRepository
import com.safesphere.app.feature.safewalk.domain.JourneyStatus
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one holder for "On my way" (N6, D22; code name Safe Walk, Phase 4B), shared by the screen, the banners and the
 * notification actions. Runs in the application scope; [scope] must be single-threaded (main in the app, a test
 * dispatcher in tests).
 *
 * A reminder, not tracking: no location, no foreground service, no route / stop / battery checks, no destination
 * lookup, no reports of missed reminders to anyone. At the chosen time the phone shows "There yet?" [I'm there]
 * [More time]; 5 minutes later one more nudge with SOS / Call 112; then IRL stays quiet. The times come from the pure
 * [CheckInPolicy]; an alarm that needs no permission ([ReminderScheduler]) wakes the phone for them even when IRL is not
 * running, and the notification actions work after the process died ([checkInFromNotification], [completeFromNotification]).
 *
 * Works offline: More time, I'm there and Cancel act on the phone at once; the server is told through an ordered queue
 * with backoff. The reminder time and a pending end are kept in [ActiveJourneyStore] (ids and times only).
 */
class SafeWalkSession(
    private val scope: CoroutineScope,
    private val repository: JourneyRepository,
    private val store: ActiveJourneyStore,
    connectivityMonitor: ConnectivityMonitor,
    private val alerts: SafeWalkAlerts,
    private val reminders: ReminderScheduler,
    session: Flow<AuthSession?>,
    private val currentUserId: suspend () -> Long?,
    private val policy: CheckInPolicy = CheckInPolicy(),
    private val now: () -> Instant = Instant::now
) : SafeWalkController {

    private val _state = MutableStateFlow(SafeWalkState())
    override val state: StateFlow<SafeWalkState> = _state.asStateFlow()

    private sealed interface Command {
        val journeyId: Long

        data class CheckIn(override val journeyId: Long) : Command
        data class End(override val journeyId: Long, val status: JourneyStatus) : Command
    }

    private val commands = ArrayDeque<Command>()
    private val commandSignal = Channel<Unit>(Channel.CONFLATED)
    private val timerSignal = Channel<Unit>(Channel.CONFLATED)
    private val startMutex = Mutex()
    private var timerJob: Job? = null
    private var shownPhase: CheckInPhase? = null

    init {
        scope.launch { commandLoop() }
        scope.launch {
            connectivityMonitor.isOnline.collect { online ->
                _state.value = _state.value.copy(isOnline = online)
                if (online) commandSignal.trySend(Unit)
            }
        }
        scope.launch {
            var lastUser: Long? = null
            session.collect { current ->
                val user = current?.user?.id
                // A real sign-out, or another account signing in on this phone: the walk belongs to the previous one.
                // Phase 11 C1: otherwise a session change is a new token (refresh, signing in again): resend what waits.
                if (current == null || (lastUser != null && user != lastUser)) onSignedOut() else commandSignal.trySend(Unit)
                lastUser = user
            }
        }
    }

    // ---- public API -----------------------------------------------------------------------------

    override suspend fun start(label: String?, minutes: Int): ApiResult<Journey> = startMutex.withLock {
        if (_state.value.isActive) return@withLock ApiResult.Failure(ApiError.CONFLICT, null)
        val result = repository.start(label?.trim()?.ifBlank { null }, minutes)
        if (result is ApiResult.Success) begin(result.data, serverDeadline(result.data), restoredOffline = false)
        result
    }

    override suspend fun recover() = startMutex.withLock {
        if (_state.value.isActive) return@withLock
        val userId = currentUserId() ?: return@withLock
        val record = store.load()?.takeIf { it.userId == userId }
        val pendingEnd = record?.pendingEnd
        if (record != null && pendingEnd != null) {
            // Ended on this phone before a restart, server not told yet: tell it now.
            enqueue(Command.End(record.journeyId, pendingEnd))
            return@withLock
        }
        when (val result = repository.getHistory()) {
            is ApiResult.Success -> {
                val active = result.data.firstOrNull { it.status == JourneyStatus.ACTIVE && it.nextCheckInDueAt != null }
                if (active == null) {
                    if (record != null) store.clear()
                    reminders.cancel()
                    return@withLock
                }
                val ownRecord = record?.takeIf { it.journeyId == active.id }
                var dueAt = serverDeadline(active)
                if (ownRecord != null && ownRecord.localDueAt.isAfter(dueAt)) {
                    // More time was tapped on this phone (offline) and the server never heard: keep that and resend.
                    dueAt = ownRecord.localDueAt
                    enqueue(Command.CheckIn(active.id))
                }
                begin(active, dueAt, restoredOffline = false)
            }
            is ApiResult.Failure -> {
                // Offline, or (Phase 11 C1) "sign in to sync": the walk kept on this phone goes on.
                if (record == null) return@withLock
                begin(record.toJourney(), record.localDueAt, restoredOffline = true)
            }
        }
    }

    override fun checkIn() {
        val current = _state.value
        val journey = current.journey ?: return
        if (!current.isActive) return
        val dueAt = now().plus(Duration.ofMinutes(journey.checkInIntervalMinutes.toLong()))
        _state.value = current.copy(localDueAt = dueAt)
        reminders.schedule(journey.id, dueAt, policy.nudgeAt(dueAt))
        enqueue(Command.CheckIn(journey.id))
        timerSignal.trySend(Unit)
        scope.launch { persist() }
    }

    /**
     * "More time" tapped on a notification. The process may have been started just for this, so the walk is recovered
     * first. [onDone] is called when the local change has happened (or there was no walk).
     */
    fun checkInFromNotification(onDone: () -> Unit) = fromNotification(onDone) { checkIn() }

    /** "I'm there" tapped on a notification (same rules as [checkInFromNotification]). */
    fun completeFromNotification(onDone: () -> Unit) = fromNotification(onDone) { complete() }

    private fun fromNotification(onDone: () -> Unit, action: () -> Unit) {
        scope.launch {
            try {
                if (!_state.value.isActive) recover()
                action()
                // Give the queued server call a short chance to go out while the receiver keeps us alive.
                withTimeoutOrNull(NOTIFICATION_SYNC_WAIT_MILLIS) { while (commands.isNotEmpty()) delay(100) }
            } finally {
                onDone()
            }
        }
    }

    /**
     * The phone's alarm went off for [journeyId] (the process may have been started just for this). Shows "There yet?" or
     * the nudge if that walk is still running and the time has come; a stale alarm (walk ended, More time tapped) shows
     * nothing. [onDone] is called when the notification is up (or there was nothing to show).
     */
    fun onAlarm(journeyId: Long, onDone: () -> Unit) {
        scope.launch {
            try {
                if (!_state.value.isActive) recover()
                val current = _state.value
                val dueAt = current.localDueAt
                if (current.isActive && current.journey?.id == journeyId && dueAt != null) {
                    val status = policy.evaluate(dueAt, now())
                    _state.value = current.copy(checkIn = status)
                    if ((status.phase == CheckInPhase.MISSED || status.phase == CheckInPhase.NO_RESPONSE) &&
                        status.phase != shownPhase
                    ) {
                        shownPhase = status.phase
                        alerts.show(status.phase)
                    }
                    timerSignal.trySend(Unit)
                }
            } finally {
                onDone()
            }
        }
    }

    override fun complete() = endLocally(JourneyStatus.COMPLETED)

    override fun cancel() = endLocally(JourneyStatus.CANCELLED)

    override fun onEmergencyStarted() {
        val current = _state.value
        val journey = current.journey ?: return
        if (!current.isActive) return
        stopReminders()
        commands.removeAll { it.journeyId == journey.id }
        _state.value = current.copy(endedStatus = JourneyStatus.EMERGENCY, checkIn = null, pendingSync = commands.size)
        scope.launch { store.clear() }
    }

    override fun acknowledgeEnded() {
        if (_state.value.endedStatus != null || _state.value.signedOut) {
            _state.value = SafeWalkState(isOnline = _state.value.isOnline, pendingSync = commands.size)
        }
    }

    // ---- walk lifecycle -------------------------------------------------------------------------

    private suspend fun begin(journey: Journey, localDueAt: Instant, restoredOffline: Boolean) {
        timerJob?.cancel()
        shownPhase = null
        _state.value = SafeWalkState(
            journey = journey,
            localDueAt = localDueAt,
            isOnline = _state.value.isOnline,
            restoredOffline = restoredOffline,
            pendingSync = commands.size
        )
        reminders.schedule(journey.id, localDueAt, policy.nudgeAt(localDueAt))
        persist()
        timerJob = scope.launch { timerLoop() }
    }

    /** While IRL runs, the screen follows the same schedule as the alarms (the alarms also work without IRL running). */
    private suspend fun timerLoop() {
        while (true) {
            val current = _state.value
            val dueAt = current.localDueAt ?: return
            if (!current.isActive) return
            val status = policy.evaluate(dueAt, now())
            _state.value = current.copy(checkIn = status)
            if (status.phase != shownPhase) {
                shownPhase = status.phase
                if (status.phase == CheckInPhase.MISSED || status.phase == CheckInPhase.NO_RESPONSE) alerts.show(status.phase)
                else alerts.clear()
            }
            val next = status.nextChangeAt
            if (next == null) {
                timerSignal.receive()
            } else {
                withTimeoutOrNull(Duration.between(now(), next).toMillis().coerceAtLeast(0)) { timerSignal.receive() }
            }
        }
    }

    private fun endLocally(status: JourneyStatus) {
        val current = _state.value
        val journey = current.journey ?: return
        if (!current.isActive) return
        stopReminders()
        commands.removeAll { it.journeyId == journey.id } // a queued "More time" no longer matters
        _state.value = current.copy(endedStatus = status, checkIn = null)
        // Order matters: remember the pending end BEFORE the server call can succeed and clear the record.
        scope.launch {
            currentUserId()?.let { userId ->
                store.save(record(userId, journey, current.localDueAt ?: now()).copy(pendingEnd = status))
            }
            enqueue(Command.End(journey.id, status))
        }
    }

    private fun stopReminders() {
        timerJob?.cancel()
        timerJob = null
        shownPhase = null
        reminders.cancel()
        alerts.clear()
    }

    private fun onSignedOut() {
        if (_state.value.journey == null && commands.isEmpty()) return
        stopReminders()
        commands.clear()
        _state.value = _state.value.copy(signedOut = true, checkIn = null, pendingSync = 0)
    }

    // ---- server queue ---------------------------------------------------------------------------

    private fun enqueue(command: Command) {
        commands.addLast(command)
        _state.value = _state.value.copy(pendingSync = commands.size)
        commandSignal.trySend(Unit)
    }

    private suspend fun commandLoop() {
        var retryDelay = INITIAL_RETRY_MILLIS
        while (true) {
            val command = commands.firstOrNull()
            if (command == null) {
                commandSignal.receive()
                continue
            }
            val result = when (command) {
                is Command.CheckIn -> repository.checkIn(command.journeyId)
                is Command.End -> if (command.status == JourneyStatus.COMPLETED) repository.complete(command.journeyId)
                    else repository.cancel(command.journeyId)
            }
            when {
                result is ApiResult.Success -> {
                    commands.remove(command)
                    retryDelay = INITIAL_RETRY_MILLIS
                    onCommandSucceeded(command, result.data)
                }
                result is ApiResult.Failure && result.error in RETRYABLE -> {
                    withTimeoutOrNull(retryDelay) { commandSignal.receive() }
                    retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_MILLIS)
                }
                // Phase 11 C1: "sign in to sync": keep the commands and wait for the next sign-in, token or connection.
                result is ApiResult.Failure && result.error == ApiError.UNAUTHORIZED -> commandSignal.receive()
                else -> {
                    commands.remove(command)
                    onCommandRejected(command)
                }
            }
            _state.value = _state.value.copy(pendingSync = commands.size)
        }
    }

    private suspend fun onCommandSucceeded(command: Command, journey: Journey) {
        when (command) {
            is Command.CheckIn -> {
                val current = _state.value
                val moreQueued = commands.any { it is Command.CheckIn && it.journeyId == command.journeyId }
                if (current.isActive && current.journey?.id == journey.id && !moreQueued) {
                    // Adopt the server's time (the server's clock is the reference).
                    val dueAt = serverDeadline(journey)
                    _state.value = current.copy(localDueAt = dueAt, journey = journey, restoredOffline = false)
                    reminders.schedule(journey.id, dueAt, policy.nudgeAt(dueAt))
                    timerSignal.trySend(Unit)
                    persist()
                }
            }
            is Command.End -> store.clear()
        }
    }

    /** VALIDATION / CONFLICT / NOT_FOUND: retrying will not help. Maybe the walk was ended elsewhere (an SOS). */
    private suspend fun onCommandRejected(command: Command) {
        if (command is Command.End) {
            store.clear() // already ended on the server, or gone
            return
        }
        val current = _state.value
        if (!current.isActive || current.journey?.id != command.journeyId) return
        val history = repository.getHistory() as? ApiResult.Success ?: return
        val serverJourney = history.data.firstOrNull { it.id == command.journeyId }
        if (serverJourney == null || serverJourney.status != JourneyStatus.ACTIVE) {
            stopReminders()
            commands.removeAll { it.journeyId == command.journeyId }
            _state.value = _state.value.copy(endedStatus = serverJourney?.status ?: JourneyStatus.CANCELLED, checkIn = null)
            store.clear()
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun serverDeadline(journey: Journey): Instant {
        val due = journey.nextCheckInDueAt
            ?: return now().plus(Duration.ofMinutes(journey.checkInIntervalMinutes.toLong()))
        return CheckInPolicy.localDeadline(due, journey.serverTime, now())
    }

    private suspend fun persist() {
        val current = _state.value
        val journey = current.journey ?: return
        val dueAt = current.localDueAt ?: return
        if (!current.isActive) return
        val userId = currentUserId() ?: return
        store.save(record(userId, journey, dueAt))
    }

    private fun record(userId: Long, journey: Journey, dueAt: Instant) = ActiveJourneyRecord(
        userId = userId,
        journeyId = journey.id,
        checkInIntervalMinutes = journey.checkInIntervalMinutes,
        startedAt = journey.startedAt,
        localDueAt = dueAt
    )

    private fun ActiveJourneyRecord.toJourney() = Journey(
        id = journeyId,
        status = JourneyStatus.ACTIVE,
        destinationLabel = null, // never stored on the phone; comes back with the next server answer
        checkInIntervalMinutes = checkInIntervalMinutes,
        escalationLevel = EscalationLevel.NORMAL,
        startedAt = startedAt,
        endedAt = null,
        nextCheckInDueAt = null,
        serverTime = now()
    )

    companion object {
        /** BroadcastReceiver.goAsync() allows about 10 s; stay well below. */
        const val NOTIFICATION_SYNC_WAIT_MILLIS = 5_000L
        const val INITIAL_RETRY_MILLIS = 5_000L
        const val MAX_RETRY_MILLIS = 60_000L
        private val RETRYABLE = setOf(ApiError.NO_CONNECTION, ApiError.SERVER, ApiError.UNKNOWN)
    }
}
