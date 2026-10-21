package com.safesphere.app.feature.safewalk.session

import com.safesphere.app.R
import com.safesphere.app.core.device.BatteryLevel
import com.safesphere.app.core.device.BatteryMonitor
import com.safesphere.app.core.device.BatteryPolicy
import com.safesphere.app.core.device.BatteryStatus
import com.safesphere.app.core.device.ConnectivityMonitor
import com.safesphere.app.core.escalation.EscalationLevel
import com.safesphere.app.core.location.DestinationLookup
import com.safesphere.app.core.location.DestinationResolver
import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.location.LocationFix
import com.safesphere.app.core.location.LocationProvider
import com.safesphere.app.core.location.LocationUpdate
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.tracking.TrackingNotice
import com.safesphere.app.core.tracking.TrackingNoticeSource
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.emergency.location.LocationSamplingPolicy
import com.safesphere.app.feature.safewalk.domain.ActiveJourneyRecord
import com.safesphere.app.feature.safewalk.domain.ActiveJourneyStore
import com.safesphere.app.feature.safewalk.domain.CheckInPhase
import com.safesphere.app.feature.safewalk.domain.CheckInPolicy
import com.safesphere.app.feature.safewalk.domain.CheckInStatus
import com.safesphere.app.feature.safewalk.domain.Concern
import com.safesphere.app.feature.safewalk.domain.ConcernKind
import com.safesphere.app.feature.safewalk.domain.Journey
import com.safesphere.app.feature.safewalk.domain.JourneyRepository
import com.safesphere.app.feature.safewalk.domain.JourneyStatus
import com.safesphere.app.feature.safewalk.domain.MovementPolicy
import com.safesphere.app.feature.safewalk.domain.MovementState
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one holder for Safe Walk (Phase 4B + 4C), shared by the Safe Walk screen, Home, the check-in notification
 * action and the tracking foreground service ([TrackingNoticeSource]). Runs in the application scope.
 *
 * - Timer + escalation: [CheckInPolicy] (pure) decides the phase from the deadline, an open concern and the
 *   time; this class only waits until the next change, shows the matching notification and reports new levels
 *   (1, then 2) to the server. It never escalates to level 3 by itself.
 * - Movement checks (4C): every fix goes through [MovementPolicy] (pure): Safety Bubble, unexpected stop,
 *   arrival hint. A new concern opens the "Are you OK?" prompt early (level 1); nothing else changes.
 * - Battery (4C): [BatteryPolicy] (pure) is read once a minute: warnings, slower location when low, one last
 *   location upload when critical.
 * - Works offline: "I'm OK", "I've arrived" and "Cancel" act on the phone immediately; the server is told
 *   through an ordered command queue with backoff. The deadline, an open concern and a pending end are kept in
 *   [ActiveJourneyStore] so a restart (even offline) resumes correctly.
 * - Privacy: only the LAST known location is sent (the server overwrites it); no track, no location on disk.
 *
 * Threading: [scope] must be single-threaded (main in the app, a test dispatcher in tests).
 */
class SafeWalkSession(
    private val scope: CoroutineScope,
    private val repository: JourneyRepository,
    private val store: ActiveJourneyStore,
    private val locationProvider: LocationProvider,
    connectivityMonitor: ConnectivityMonitor,
    private val alerts: SafeWalkAlerts,
    session: Flow<AuthSession?>,
    private val currentUserId: suspend () -> Long?,
    private val batteryMonitor: BatteryMonitor,
    private val destinationResolver: DestinationResolver,
    private val policy: CheckInPolicy = CheckInPolicy(),
    private val movementPolicy: MovementPolicy = MovementPolicy(),
    private val batteryPolicy: BatteryPolicy = BatteryPolicy(),
    private val samplingPolicy: LocationSamplingPolicy =
        LocationSamplingPolicy(minDistanceMeters = 25.0, maxSilence = Duration.ofSeconds(120)),
    private val now: () -> Instant = Instant::now
) : SafeWalkController, TrackingNoticeSource {

    private val _state = MutableStateFlow(SafeWalkState())
    override val state: StateFlow<SafeWalkState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<TrackingNotice?>(null)
    override fun currentNotice(): TrackingNotice? = _notice.value
    override val notices: Flow<TrackingNotice?> = _notice.asStateFlow()

    private sealed interface Command {
        val journeyId: Long

        data class CheckIn(override val journeyId: Long) : Command
        data class Escalate(override val journeyId: Long, val level: EscalationLevel, val concern: ConcernKind?) : Command
        data class Location(override val journeyId: Long, val fix: LocationFix) : Command
        data class End(override val journeyId: Long, val status: JourneyStatus) : Command
    }

    /** How a walk begins: from a fresh start or from a restored record. */
    private data class WalkSetup(
        val movementChecks: Boolean,
        val destinationSearch: DestinationSearch = DestinationSearch.NOT_ASKED,
        val destinationDescription: String? = null,
        val movement: MovementState = MovementState(),
        val concern: Concern? = null
    )

    private val commands = ArrayDeque<Command>()
    private val commandSignal = Channel<Unit>(Channel.CONFLATED)
    private val timerSignal = Channel<Unit>(Channel.CONFLATED)
    private val locationRestarts = MutableStateFlow(0)
    private val startMutex = Mutex()

    private var walkJob: Job? = null
    private var reportedLevel = 0
    private var alertedKey: Pair<CheckInPhase, ConcernKind?>? = null
    private var lastRecordedFix: LocationFix? = null

    init {
        scope.launch { commandLoop() }
        scope.launch {
            connectivityMonitor.isOnline.collect { online ->
                update { it.copy(isOnline = online) }
                if (online) commandSignal.trySend(Unit)
            }
        }
        scope.launch { session.collect { if (it == null) onSignedOut() } }
    }

    // ---- public API -----------------------------------------------------------------------------

    override suspend fun start(destinationLabel: String?, checkInIntervalMinutes: Int, movementChecks: Boolean): ApiResult<Journey> =
        startMutex.withLock {
            if (_state.value.isActive) return@withLock ApiResult.Failure(ApiError.CONFLICT, null)
            val label = destinationLabel?.trim()?.ifBlank { null }
            var setup = WalkSetup(movementChecks)
            var destination: GeoPoint? = null
            if (movementChecks) {
                val startFix = if (locationProvider.access() != LocationAccess.NONE) currentFix() else null
                if (startFix != null) setup = setup.copy(movement = movementPolicy.onFix(MovementState(), startFix, null))
                if (label != null) {
                    // The map lookup needs the typed text; a nearby fix narrows it to this area.
                    when (val found = destinationResolver.resolve(label, startFix?.let(GeoPoint::of))) {
                        is DestinationLookup.Found -> {
                            destination = found.point
                            setup = setup.copy(destinationSearch = DestinationSearch.FOUND, destinationDescription = found.description)
                        }
                        DestinationLookup.NotFound -> setup = setup.copy(destinationSearch = DestinationSearch.NOT_FOUND)
                        DestinationLookup.Unavailable -> setup = setup.copy(destinationSearch = DestinationSearch.UNAVAILABLE)
                    }
                }
            }
            val result = repository.start(label, checkInIntervalMinutes, destination)
            if (result is ApiResult.Success) begin(result.data, serverDeadline(result.data), restoredOffline = false, setup)
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
                    return@withLock
                }
                val ownRecord = record?.takeIf { it.journeyId == active.id }
                var dueAt = serverDeadline(active)
                if (ownRecord != null && ownRecord.localDueAt.isAfter(dueAt)) {
                    // The person checked in on this phone (offline) and the server never heard: keep that and resend.
                    dueAt = ownRecord.localDueAt
                    enqueue(Command.CheckIn(active.id))
                }
                val setup = WalkSetup(movementChecks = ownRecord?.movementChecks ?: true, concern = ownRecord?.concern)
                begin(active, dueAt, restoredOffline = false, setup)
            }
            is ApiResult.Failure -> {
                if (result.error == ApiError.UNAUTHORIZED || record == null) return@withLock
                begin(record.toJourney(), record.localDueAt, restoredOffline = true, WalkSetup(record.movementChecks, concern = record.concern))
            }
        }
    }

    override fun checkIn() {
        val current = _state.value
        val journey = current.journey ?: return
        if (!current.isActive) return
        val dueAt = now().plus(Duration.ofMinutes(journey.checkInIntervalMinutes.toLong()))
        reportedLevel = EscalationLevel.NORMAL.code
        // "I'm OK" also answers whatever the movement checks see right now (no repeat until it clears).
        update { it.copy(localDueAt = dueAt, concern = null, movement = movementPolicy.acknowledge(it.movement)) }
        enqueue(Command.CheckIn(journey.id))
        timerSignal.trySend(Unit) // re-evaluate at once: back to ON_TRACK, alert cleared
        scope.launch { persist() }
    }

    /**
     * "I'm OK" tapped on a notification. The process may have been restarted just for this, so the walk is
     * recovered first. [onDone] is called when the local check-in has happened (or there was no walk).
     */
    fun checkInFromNotification(onDone: () -> Unit) {
        scope.launch {
            try {
                if (!_state.value.isActive) recover()
                checkIn()
                // Give the queued server call a short chance to go out while the receiver keeps us alive.
                withTimeoutOrNull(NOTIFICATION_SYNC_WAIT_MILLIS) {
                    while (commands.any { it is Command.CheckIn }) delay(100)
                }
            } finally {
                onDone()
            }
        }
    }

    override fun complete() = endLocally(JourneyStatus.COMPLETED)

    override fun cancel() = endLocally(JourneyStatus.CANCELLED)

    override fun refreshLocationAccess() {
        val current = _state.value
        if (!current.isActive) return
        val access = locationProvider.access()
        if (access != current.access) {
            update { it.copy(access = access) }
            locationRestarts.value++
        }
    }

    override fun setMovementChecks(enabled: Boolean) {
        val current = _state.value
        if (!current.isActive || current.movementChecks == enabled) return
        // Fresh state either way: switched back on, the bubble starts again from the next fix.
        // An open "Are you OK?" prompt stays open: only "I'm OK" answers it.
        update { it.copy(movementChecks = enabled, movement = MovementState()) }
        scope.launch { persist() }
    }

    override fun onEmergencyStarted() {
        val current = _state.value
        val journey = current.journey ?: return
        if (!current.isActive) return
        stopWalkWork()
        commands.removeAll { it.journeyId == journey.id }
        update { it.copy(endedStatus = JourneyStatus.EMERGENCY, checkIn = null, pendingSync = commands.size) }
        scope.launch { store.clear() }
    }

    override fun acknowledgeEnded() {
        if (_state.value.endedStatus != null || _state.value.signedOut) {
            update { SafeWalkState(isOnline = it.isOnline, pendingSync = commands.size) }
        }
    }

    // ---- walk lifecycle -------------------------------------------------------------------------

    private suspend fun begin(journey: Journey, localDueAt: Instant, restoredOffline: Boolean, setup: WalkSetup) {
        walkJob?.cancel()
        reportedLevel = journey.escalationLevel.code
        alertedKey = null
        lastRecordedFix = null
        update {
            SafeWalkState(
                journey = journey,
                localDueAt = localDueAt,
                access = locationProvider.access(),
                isOnline = it.isOnline,
                restoredOffline = restoredOffline,
                pendingSync = commands.size,
                movementChecks = setup.movementChecks,
                destinationSearch = setup.destinationSearch,
                destinationDescription = setup.destinationDescription,
                movement = setup.movement,
                concern = setup.concern
            )
        }
        persist()
        walkJob = scope.launch {
            coroutineScope {
                launch { timerLoop() }
                launch { batteryLoop(journey.id) }
                collectLocation(journey)
            }
        }
    }

    private fun endLocally(status: JourneyStatus) {
        val current = _state.value
        val journey = current.journey ?: return
        if (!current.isActive) return
        stopWalkWork()
        commands.removeAll { it.journeyId == journey.id } // check-ins/escalations/location no longer matter
        update { it.copy(endedStatus = status, checkIn = null, concern = null) }
        // Order matters: remember the pending end BEFORE the server call can succeed and clear the record.
        scope.launch {
            currentUserId()?.let { userId ->
                store.save(record(userId, current, journey, current.localDueAt ?: now()).copy(pendingEnd = status))
            }
            enqueue(Command.End(journey.id, status))
        }
    }

    private fun stopWalkWork() {
        walkJob?.cancel()
        walkJob = null
        alerts.clear()
        alerts.clearBatteryCritical()
        alertedKey = null
    }

    private fun onSignedOut() {
        if (_state.value.journey == null && commands.isEmpty()) return
        stopWalkWork()
        commands.clear()
        update { it.copy(signedOut = true, checkIn = null, pendingSync = 0) }
    }

    /** Waits until the policy's next change (or a check-in / new concern) and reacts. Decisions live in [CheckInPolicy]. */
    private suspend fun timerLoop() {
        while (true) {
            val current = _state.value
            val dueAt = current.localDueAt ?: return
            if (!current.isActive) return
            val status = policy.evaluate(dueAt, now(), current.concern)
            update { it.copy(checkIn = status) }
            react(current.journey!!.id, status)
            val next = status.nextChangeAt
            if (next == null) {
                timerSignal.receive()
            } else {
                withTimeoutOrNull(Duration.between(now(), next).toMillis().coerceAtLeast(0)) { timerSignal.receive() }
            }
        }
    }

    private fun react(journeyId: Long, status: CheckInStatus) {
        val key = status.phase to status.concern
        if (key != alertedKey) {
            alertedKey = key
            if (status.phase == CheckInPhase.ON_TRACK) alerts.clear() else alerts.show(status)
        }
        // One step at a time, never above what the policy reached (at most level 2).
        while (reportedLevel < status.level.code) {
            reportedLevel++
            val level = EscalationLevel.fromCode(reportedLevel)
            val concern = if (level == EscalationLevel.CHECK_IN_MISSED) status.concern else null
            enqueue(Command.Escalate(journeyId, level, concern))
        }
    }

    private suspend fun collectLocation(journey: Journey) {
        locationRestarts.collectLatest {
            val access = locationProvider.access()
            update { it.copy(access = access) }
            if (access == LocationAccess.NONE) return@collectLatest
            val interval = batteryPolicy.locationIntervalMillis(_state.value.battery.level, LOCATION_INTERVAL_MILLIS)
            locationProvider.updates(interval).collect { locationUpdate ->
                if (locationUpdate is LocationUpdate.Fix) {
                    val fix = locationUpdate.fix
                    update { it.copy(lastFix = fix) }
                    if (samplingPolicy.shouldRecord(lastRecordedFix, fix, journey.startedAt)) {
                        lastRecordedFix = fix
                        enqueue(Command.Location(journey.id, fix))
                    }
                    checkMovement(fix)
                }
            }
        }
    }

    /** Phase 4C: feeds the pure movement checks; a NEW concern opens the "Are you OK?" prompt early. */
    private fun checkMovement(fix: LocationFix) {
        val current = _state.value
        if (!current.isActive || !current.movementChecks) return
        var movement = movementPolicy.onFix(current.movement, fix, current.journey?.destination)
        var concern = current.concern
        val kind = movement.newConcern
        if (kind != null) {
            movement = movementPolicy.markReported(movement, kind)
            val promptOpen = current.phase == CheckInPhase.MISSED || current.phase == CheckInPhase.NO_RESPONSE
            // Already being asked "Are you OK?": no second prompt, the open one already covers it.
            if (concern == null && !promptOpen) concern = Concern(kind, now())
        }
        update { it.copy(movement = movement, concern = concern) }
        if (concern != current.concern) {
            timerSignal.trySend(Unit)
            scope.launch { persist() }
        }
    }

    /** Phase 4C battery policy, read once a minute while the walk runs. */
    private suspend fun batteryLoop(journeyId: Long) {
        while (true) {
            checkBattery(journeyId)
            delay(BATTERY_CHECK_MILLIS)
        }
    }

    private fun checkBattery(journeyId: Long) {
        val current = _state.value
        if (!current.isActive) return
        val percent = batteryMonitor.batteryPercent()
        val charging = batteryMonitor.isCharging()
        val previous = current.battery.level
        val level = batteryPolicy.evaluate(previous, percent, charging)
        update { it.copy(battery = BatteryStatus(percent, charging == true, level)) }
        if (level == previous) return
        if (batteryPolicy.locationIntervalMillis(level, LOCATION_INTERVAL_MILLIS) !=
            batteryPolicy.locationIntervalMillis(previous, LOCATION_INTERVAL_MILLIS)
        ) {
            locationRestarts.value++ // ask for location at the new rate
        }
        if (level == BatteryLevel.CRITICAL) {
            alerts.showBatteryCritical()
            // The phone may die soon: make the server's "last known location" as fresh as possible.
            val fix = current.lastFix
            if (fix != null && fix != lastRecordedFix) {
                lastRecordedFix = fix
                enqueue(Command.Location(journeyId, fix))
            }
        } else if (previous == BatteryLevel.CRITICAL) {
            alerts.clearBatteryCritical()
        }
    }

    /** One fresh fix for the destination search, or null after a short wait (the walk starts anyway). */
    private suspend fun currentFix(): LocationFix? = withTimeoutOrNull(START_FIX_TIMEOUT_MILLIS) {
        locationProvider.updates(START_FIX_INTERVAL_MILLIS).filterIsInstance<LocationUpdate.Fix>().first().fix
    }

    // ---- server sync ----------------------------------------------------------------------------

    private fun enqueue(command: Command) {
        // Only the newest location matters (the server keeps just the last one).
        if (command is Command.Location) commands.removeAll { it is Command.Location && it.journeyId == command.journeyId }
        commands.addLast(command)
        update { it.copy(pendingSync = commands.size) }
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
            val result = execute(command)
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
                result is ApiResult.Failure && result.error == ApiError.UNAUTHORIZED -> commands.clear()
                else -> {
                    commands.remove(command)
                    onCommandRejected(command, (result as ApiResult.Failure).error)
                }
            }
            update { it.copy(pendingSync = commands.size) }
        }
    }

    private suspend fun execute(command: Command): ApiResult<Any?> = when (command) {
        is Command.CheckIn -> repository.checkIn(command.journeyId)
        is Command.Escalate -> repository.escalate(command.journeyId, command.level, command.concern)
        is Command.Location -> repository.updateLocation(command.journeyId, command.fix)
        is Command.End -> when (command.status) {
            JourneyStatus.COMPLETED -> repository.complete(command.journeyId)
            else -> repository.cancel(command.journeyId)
        }
    }

    private suspend fun onCommandSucceeded(command: Command, data: Any?) {
        when (command) {
            is Command.CheckIn -> {
                val journey = data as? Journey ?: return
                val current = _state.value
                val moreCheckInsQueued = commands.any { it is Command.CheckIn && it.journeyId == command.journeyId }
                if (current.isActive && current.journey?.id == journey.id && !moreCheckInsQueued) {
                    // Adopt the server's deadline (the server's clock is the reference). The server's journey also
                    // brings back the destination after an offline restore, so route checks can resume.
                    update { it.copy(localDueAt = serverDeadline(journey), journey = journey, restoredOffline = false) }
                    timerSignal.trySend(Unit)
                    persist()
                }
            }
            is Command.Location -> update { it.copy(lastLocationSavedAt = now()) }
            is Command.End -> store.clear()
            is Command.Escalate -> Unit
        }
    }

    /** VALIDATION / CONFLICT / NOT_FOUND: retrying will not help. */
    private suspend fun onCommandRejected(command: Command, error: ApiError) {
        if (command is Command.End) {
            store.clear() // already ended on the server, or gone
            return
        }
        val current = _state.value
        if (!current.isActive || current.journey?.id != command.journeyId) return
        if (error == ApiError.CONFLICT || error == ApiError.NOT_FOUND) reconcileWithServer(command.journeyId)
    }

    /** The server refused a change: maybe the walk was ended elsewhere. If so, end it here too. */
    private suspend fun reconcileWithServer(journeyId: Long) {
        val history = repository.getHistory() as? ApiResult.Success ?: return
        val serverJourney = history.data.firstOrNull { it.id == journeyId }
        if (serverJourney == null || serverJourney.status != JourneyStatus.ACTIVE) {
            stopWalkWork()
            commands.removeAll { it.journeyId == journeyId }
            update { it.copy(endedStatus = serverJourney?.status ?: JourneyStatus.CANCELLED, checkIn = null, concern = null) }
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
        store.save(record(userId, current, journey, dueAt))
    }

    private fun record(userId: Long, state: SafeWalkState, journey: Journey, dueAt: Instant) = ActiveJourneyRecord(
        userId = userId,
        journeyId = journey.id,
        checkInIntervalMinutes = journey.checkInIntervalMinutes,
        startedAt = journey.startedAt,
        localDueAt = dueAt,
        movementChecks = state.movementChecks,
        concern = state.concern
    )

    private fun ActiveJourneyRecord.toJourney() = Journey(
        id = journeyId,
        status = JourneyStatus.ACTIVE,
        destinationLabel = null, // never stored on the phone
        checkInIntervalMinutes = checkInIntervalMinutes,
        escalationLevel = EscalationLevel.NORMAL, // unknown offline; re-reporting is harmless (idempotent)
        startedAt = startedAt,
        endedAt = null,
        nextCheckInDueAt = null,
        serverTime = now(),
        destination = null // never stored on the phone; comes back with the next server answer
    )

    /** Every change goes through here so [state] and the tracking notice never disagree. */
    private fun update(transform: (SafeWalkState) -> SafeWalkState) {
        val next = transform(_state.value)
        _state.value = next
        _notice.value = noticeFor(next)
    }

    companion object {
        const val LOCATION_INTERVAL_MILLIS = 30_000L
        const val BATTERY_CHECK_MILLIS = 60_000L
        const val START_FIX_TIMEOUT_MILLIS = 3_000L
        private const val START_FIX_INTERVAL_MILLIS = 1_000L
        /** BroadcastReceiver.goAsync() allows about 10 s; stay well below. */
        const val NOTIFICATION_SYNC_WAIT_MILLIS = 5_000L
        const val INITIAL_RETRY_MILLIS = 5_000L
        const val MAX_RETRY_MILLIS = 60_000L
        private val RETRYABLE = setOf(ApiError.NO_CONNECTION, ApiError.SERVER, ApiError.UNKNOWN)

        /** The ongoing notification. Generic on purpose: no destination, no location (lock screen). */
        fun noticeFor(state: SafeWalkState): TrackingNotice? {
            if (!state.isTracking) return null
            val text = when {
                state.phase == CheckInPhase.MISSED || state.phase == CheckInPhase.NO_RESPONSE ->
                    if (state.checkIn?.concern != null) R.string.safe_walk_notification_concern
                    else R.string.safe_walk_notification_missed
                state.looksArrived -> R.string.safe_walk_notification_arrived
                else -> R.string.safe_walk_notification_active
            }
            return TrackingNotice(R.string.safe_walk_notification_title, text)
        }
    }
}
