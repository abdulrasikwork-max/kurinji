package com.safesphere.app.feature.circlealert.session

import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.core.location.LocationFix
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.sms.SmsAvailability
import com.safesphere.app.core.sms.SmsOutcome
import com.safesphere.app.core.sms.SmsSender
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.circlealert.domain.AlertChannel
import com.safesphere.app.feature.circlealert.domain.AlertTarget
import com.safesphere.app.feature.circlealert.domain.AlertTrigger
import com.safesphere.app.feature.circlealert.domain.CircleAlertPolicy
import com.safesphere.app.feature.circlealert.domain.CircleAlertRepository
import com.safesphere.app.feature.circlealert.domain.CircleAlertSnapshot
import com.safesphere.app.feature.circlealert.domain.CircleAlertStore
import com.safesphere.app.feature.circlealert.domain.CircleDelivery
import com.safesphere.app.feature.circlealert.domain.CircleMessageKind
import com.safesphere.app.feature.circlealert.domain.CircleMessages
import com.safesphere.app.feature.circlealert.domain.CircleRecipient
import com.safesphere.app.feature.circlealert.domain.DeliveryStatus
import com.safesphere.app.feature.emergency.domain.EmergencyIds
import com.safesphere.app.feature.emergency.location.LocationSharingState
import com.safesphere.app.feature.emergency.session.EmergencyRebinder
import com.safesphere.app.feature.emergency.location.LocationSharingStatus
import com.safesphere.app.feature.safetycircle.domain.SafetyContact
import com.safesphere.app.feature.safewalk.domain.CheckInPhase
import com.safesphere.app.feature.safewalk.domain.ConcernKind
import com.safesphere.app.feature.safewalk.domain.JourneyStatus
import com.safesphere.app.feature.safewalk.session.SafeWalkState
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one holder for Safety Circle communication (Phase 4D). Runs in the application scope; [scope] must be
 * single-threaded (main in the app, a test dispatcher in tests).
 *
 * - Watches the Safe Walk ([walkState]) and gets the SOS start/end from the SOS screen; [CircleAlertPolicy] (pure)
 *   decides what the circle is told; this class only sends and records.
 * - Sends automatically ONLY when the person opted in, SEND_SMS is granted and there are enabled contacts
 *   ([CircleAlertState.autoActive]). Otherwise nothing is sent and the screens offer the SMS app instead.
 * - SMS needs no internet: contacts are cached on the phone. What happened is reported to the server later through
 *   a persisted queue (status only, no number, no text). SENT is never shown or reported as "delivered".
 * - Nothing here logs a number, a name, a message or a location.
 */
class CircleAlertCoordinator(
    private val scope: CoroutineScope,
    private val store: CircleAlertStore,
    private val repository: CircleAlertRepository,
    private val smsSender: SmsSender,
    private val walkState: StateFlow<SafeWalkState>,
    private val emergencyLocation: StateFlow<LocationSharingState>,
    session: Flow<AuthSession?>,
    private val policy: CircleAlertPolicy = CircleAlertPolicy(),
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault
) : CircleAlertController, EmergencyRebinder {

    private val _state = MutableStateFlow(CircleAlertState())
    override val state: StateFlow<CircleAlertState> = _state.asStateFlow()

    private var snapshot: CircleAlertSnapshot? = null
    private var personName: String = ""
    private var activeEmergency: Pair<Long, Instant>? = null
    private var emergencyRecheck: Job? = null
    private var lastWalk: Pair<Long, CheckInPhase?>? = null

    private val persistSignal = Channel<Unit>(Channel.CONFLATED)
    private val uploadSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch { session.collect { onSession(it) } }
        scope.launch { smsSender.results.collect { onSmsResult(it.requestId, it.outcome) } }
        scope.launch { walkState.collect(::onWalk) }
        scope.launch { emergencyLocation.collect { evaluateEmergency() } }
        scope.launch { persistLoop() }
        scope.launch { uploadLoop() }
    }

    // ---- public API ----------------------------------------------------------------------------

    override fun setAutoSms(enabled: Boolean) {
        refreshAvailability()
        mutate { it.copy(autoSms = enabled) }
    }

    override fun refreshAvailability() {
        _state.value = _state.value.copy(availability = smsSender.availability())
    }

    override fun updateRecipients(contacts: List<SafetyContact>) {
        val recipients = contacts.filter { it.isEnabled }
            .sortedWith(compareByDescending<SafetyContact> { it.isPrimary }.thenBy { it.priority }.thenBy { it.name })
            .map { CircleRecipient(it.id, it.name, it.phoneNumber) }
        mutate { it.copy(recipients = recipients) }
    }

    override fun onEmergencyActive(emergencyId: Long, startedAt: Instant) {
        if (activeEmergency?.first == emergencyId) return
        activeEmergency = emergencyId to startedAt
        evaluateEmergency()
    }

    override fun onEmergencyEnded(emergencyId: Long, resolved: Boolean) {
        if (activeEmergency?.first == emergencyId) {
            activeEmergency = null
            emergencyRecheck?.cancel()
        }
        apply(AlertTarget.Emergency(emergencyId), AlertTrigger.EmergencyEnded(emergencyId, resolved), location = null)
    }

    override fun composerText(target: AlertTarget, kind: CircleMessageKind): String {
        val (point, at) = latestLocation(target)
        val eventTime = (target as? AlertTarget.Emergency)?.let { activeEmergency?.takeIf { e -> e.first == it.id }?.second } ?: now()
        return CircleMessages.compose(kind, personName, eventTime, zone(), point, at, walkConcern(target), automatic = false)
    }

    override fun onComposerOpened(target: AlertTarget, kind: CircleMessageKind, recipient: CircleRecipient) {
        record(
            CircleDelivery(
                UUID.randomUUID().toString(), target, kind, recipient.contactId, recipient.name,
                AlertChannel.SMS_COMPOSER, DeliveryStatus.COMPOSER_OPENED, now()
            )
        )
    }

    /**
     * Phase 11: the server now has the SOS this phone started under [localId]. What the circle was told (memory,
     * deliveries, reports waiting for the server) moves to [serverId], so nothing is sent twice and every report
     * reaches the right emergency.
     */
    override fun rebindEmergency(localId: Long, serverId: Long) {
        val from = AlertTarget.Emergency(localId)
        val to = AlertTarget.Emergency(serverId)
        activeEmergency?.takeIf { it.first == localId }?.let { activeEmergency = serverId to it.second }
        mutate { s ->
            val memory = s.memory.emergencies[localId]?.let { moved ->
                s.memory.copy(emergencies = s.memory.emergencies - localId + (serverId to moved))
            } ?: s.memory
            s.copy(
                memory = memory,
                deliveries = s.deliveries.map { if (it.target == from) it.copy(target = to) else it },
                pendingReports = s.pendingReports.map { if (it.target == from) it.copy(target = to) else it }
            )
        }
        uploadSignal.trySend(Unit)
    }

    override fun onDialPromptOpened(emergencyId: Long) {
        if (EmergencyIds.isLocal(emergencyId)) return // recorded by SosRecorder once the server has the SOS
        scope.launch {
            var wait = INITIAL_RETRY_MILLIS
            repeat(DIAL_PROMPT_ATTEMPTS) {
                val result = repository.recordDialPrompt(emergencyId)
                if (result is ApiResult.Success) return@launch
                if (result is ApiResult.Failure && result.error !in RETRYABLE) return@launch
                delay(wait)
                wait = (wait * 2).coerceAtMost(MAX_RETRY_MILLIS)
            }
        }
    }

    // ---- triggers ------------------------------------------------------------------------------

    private suspend fun onSession(session: AuthSession?) {
        if (session == null) {
            // Logout or expired token: forget everything kept for the previous person.
            snapshot = null
            personName = ""
            activeEmergency = null
            emergencyRecheck?.cancel()
            store.clear()
            _state.value = CircleAlertState(loaded = true, availability = smsSender.availability())
            return
        }
        personName = session.user.name
        if (snapshot?.userId == session.user.id) return
        val stored = store.load()?.takeIf { it.userId == session.user.id }
        snapshot = stored ?: CircleAlertSnapshot(userId = session.user.id)
        publish()
        uploadSignal.trySend(Unit)
        // A walk or SOS may already have changed while the record was loading: look again (the memory prevents repeats).
        lastWalk = null
        onWalk(walkState.value)
        evaluateEmergency()
    }

    private fun onWalk(walk: SafeWalkState) {
        val journey = walk.journey ?: run { lastWalk = null; return }
        val previous = lastWalk?.takeIf { it.first == journey.id }
        val phase = walk.phase
        lastWalk = journey.id to phase
        val target = AlertTarget.Walk(journey.id)
        val location = walk.lastFix
        when {
            walk.endedStatus == JourneyStatus.EMERGENCY ->
                apply(target, AlertTrigger.WalkBecameEmergency(journey.id), location)
            walk.endedStatus == JourneyStatus.COMPLETED || walk.endedStatus == JourneyStatus.CANCELLED ->
                apply(target, AlertTrigger.WalkAnswered(journey.id), location)
            phase == CheckInPhase.NO_RESPONSE && previous?.second != CheckInPhase.NO_RESPONSE ->
                apply(target, AlertTrigger.WalkNoResponse(journey.id, walk.checkIn?.concern), location)
            (phase == CheckInPhase.ON_TRACK || phase == CheckInPhase.DUE_SOON) && previous?.second != phase ->
                apply(target, AlertTrigger.WalkAnswered(journey.id), location)
        }
    }

    private fun evaluateEmergency() {
        val (id, startedAt) = activeEmergency ?: return
        val sharing = emergencyLocation.value.takeIf { it.emergencyId == id }
        val fix = sharing?.lastFix
        val expected = sharing?.status !in NO_FIX_COMING
        val decision = apply(AlertTarget.Emergency(id), AlertTrigger.EmergencyActive(id, startedAt, fix != null, expected), fix)
        val recheckAt = decision ?: return
        emergencyRecheck?.cancel()
        emergencyRecheck = scope.launch {
            delay(Duration.between(now(), recheckAt).toMillis().coerceAtLeast(0))
            evaluateEmergency()
        }
    }

    /**
     * Runs the pure policy and sends what it decides. Only when automatic alerts are really possible: otherwise the
     * memory is left alone, so nothing is later claimed as "sent". Returns when to evaluate again, if needed.
     */
    private fun apply(target: AlertTarget, trigger: AlertTrigger, location: LocationFix?): Instant? {
        val current = snapshot ?: return null
        val availability = smsSender.availability()
        if (availability != _state.value.availability) _state.value = _state.value.copy(availability = availability)
        if (!current.autoSms || availability != SmsAvailability.AVAILABLE || current.recipients.isEmpty()) return null

        val decision = policy.decide(current.memory, trigger, now())
        if (decision.memory != current.memory) mutate { it.copy(memory = decision.memory) }
        decision.send.forEach { kind -> sendToCircle(target, kind, trigger, location) }
        return decision.reevaluateAt
    }

    private fun sendToCircle(target: AlertTarget, kind: CircleMessageKind, trigger: AlertTrigger, location: LocationFix?) {
        val current = snapshot ?: return
        val eventTime = when (trigger) {
            is AlertTrigger.EmergencyActive -> if (kind == CircleMessageKind.SOS_STARTED) trigger.startedAt else now()
            else -> now()
        }
        val concern = (trigger as? AlertTrigger.WalkNoResponse)?.concern
        val text = CircleMessages.compose(
            kind, personName, eventTime, zone(), location?.let(GeoPoint::of), location?.recordedAt, concern, automatic = true
        )
        current.recipients.forEach { recipient ->
            val ref = UUID.randomUUID().toString()
            // Recorded as SENDING first, so a result that arrives immediately finds it.
            record(CircleDelivery(ref, target, kind, recipient.contactId, recipient.name, AlertChannel.SMS_AUTO, DeliveryStatus.SENDING, now()))
            if (!smsSender.send(ref, recipient.phoneNumber, text)) onSmsResult(ref, SmsOutcome.FAILED)
        }
    }

    private fun onSmsResult(clientRef: String, outcome: SmsOutcome) {
        val current = snapshot ?: return
        val delivery = current.deliveries.firstOrNull { it.clientRef == clientRef } ?: return
        val status = when (outcome) {
            SmsOutcome.SENT -> DeliveryStatus.SENT
            SmsOutcome.DELIVERED -> DeliveryStatus.DELIVERED
            SmsOutcome.FAILED -> DeliveryStatus.FAILED
        }
        // Never move backwards (a late "sent" must not hide "delivered").
        if (delivery.status == DeliveryStatus.DELIVERED) return
        record(delivery.copy(status = status))
    }

    // ---- bookkeeping ---------------------------------------------------------------------------

    /** Adds or replaces a delivery (same reference) and queues it for the server unless it is still SENDING. */
    private fun record(delivery: CircleDelivery) {
        mutate { s ->
            val deliveries = (s.deliveries.filterNot { it.clientRef == delivery.clientRef } + delivery).takeLast(MAX_DELIVERIES)
            val pending = if (delivery.status == DeliveryStatus.SENDING) {
                s.pendingReports
            } else {
                (s.pendingReports.filterNot { it.clientRef == delivery.clientRef } + delivery).takeLast(MAX_PENDING)
            }
            s.copy(deliveries = deliveries, pendingReports = pending)
        }
        if (delivery.status != DeliveryStatus.SENDING) uploadSignal.trySend(Unit)
    }

    private fun mutate(transform: (CircleAlertSnapshot) -> CircleAlertSnapshot) {
        val current = snapshot ?: return
        snapshot = transform(current)
        publish()
        persistSignal.trySend(Unit)
    }

    private fun publish() {
        val s = snapshot ?: return
        _state.value = CircleAlertState(
            loaded = true,
            autoSms = s.autoSms,
            availability = smsSender.availability(),
            recipients = s.recipients,
            deliveries = s.deliveries,
            pendingReports = s.pendingReports.size
        )
    }

    private suspend fun persistLoop() {
        while (true) {
            persistSignal.receive()
            snapshot?.let { store.save(it) }
        }
    }

    /** Reports to the server in order, per target, at most 20 at a time; offline -> backoff, like the other queues. */
    private suspend fun uploadLoop() {
        var retryDelay = INITIAL_RETRY_MILLIS
        while (true) {
            val batch = snapshot?.pendingReports?.let { pending ->
                // Phase 11: reports about an SOS the server does not have yet wait until it has it (rebind).
                val first = pending.firstOrNull { !it.target.isLocalEmergency() } ?: return@let null
                pending.filter { it.target == first.target }.take(MAX_BATCH)
            }
            if (batch == null) {
                uploadSignal.receive()
                continue
            }
            when (val result = repository.report(batch.first().target, batch)) {
                is ApiResult.Success -> {
                    retryDelay = INITIAL_RETRY_MILLIS
                    removePending(batch)
                }
                is ApiResult.Failure -> when (result.error) {
                    in RETRYABLE -> {
                        withTimeoutOrNull(retryDelay) { uploadSignal.receive() }
                        retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_MILLIS)
                    }
                    ApiError.UNAUTHORIZED -> uploadSignal.receive() // the session is gone; wait for the next one
                    else -> removePending(batch) // rejected (e.g. the walk was deleted): retrying cannot help
                }
            }
        }
    }

    /** Removes exactly what was sent; a newer status for the same reference (queued meanwhile) stays. */
    private fun removePending(sent: List<CircleDelivery>) {
        mutate { s -> s.copy(pendingReports = s.pendingReports.filterNot { it in sent }) }
    }

    private fun latestLocation(target: AlertTarget): Pair<GeoPoint?, Instant?> {
        val fix = when (target) {
            is AlertTarget.Walk -> walkState.value.lastFix?.takeIf { walkState.value.journey?.id == target.id }
            is AlertTarget.Emergency -> emergencyLocation.value.takeIf { it.emergencyId == target.id }?.lastFix
        }
        return fix?.let { GeoPoint.of(it) to it.recordedAt } ?: (null to null)
    }

    private fun AlertTarget.isLocalEmergency(): Boolean = this is AlertTarget.Emergency && EmergencyIds.isLocal(id)

    private fun walkConcern(target: AlertTarget): ConcernKind? =
        if (target is AlertTarget.Walk && walkState.value.journey?.id == target.id) walkState.value.checkIn?.concern else null

    companion object {
        const val MAX_DELIVERIES = 60
        const val MAX_PENDING = 200
        const val MAX_BATCH = 20
        const val INITIAL_RETRY_MILLIS = 5_000L
        const val MAX_RETRY_MILLIS = 60_000L
        const val DIAL_PROMPT_ATTEMPTS = 5
        private val RETRYABLE = setOf(ApiError.NO_CONNECTION, ApiError.SERVER, ApiError.UNKNOWN, ApiError.TOO_MANY_ATTEMPTS)

        /** No location fix can arrive in these states, so the SOS message does not wait for one. */
        private val NO_FIX_COMING = setOf(LocationSharingStatus.LOCATION_DISABLED, LocationSharingStatus.SERVICES_UNAVAILABLE)
    }
}
