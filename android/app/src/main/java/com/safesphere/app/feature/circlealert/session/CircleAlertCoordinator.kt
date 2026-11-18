package com.safesphere.app.feature.circlealert.session

import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.circlealert.domain.AlertChannel
import com.safesphere.app.feature.circlealert.domain.AlertTarget
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
import com.safesphere.app.feature.safetycircle.domain.SafetyContact
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one holder for what the PHONE does for the Safety Circle (Phase 4D; trimmed in N6). Runs in the application scope;
 * [scope] must be single-threaded (main in the app, a test dispatcher in tests).
 *
 * N6 (D20, N-104): the phone never texts anyone by itself (no SMS permission). The SERVER texts the Circle when the person
 * chose it (SafetyAlertRepository). What stays here is the hand-sent fallback, which works without internet:
 * - the enabled contacts are cached on the phone, so the SMS app can be opened for each of them offline;
 * - the text for the SMS app (same allow-list as the server's), with the latest SOS location;
 * - each opened SMS app is reported to the server later (status only, no number, no text), through a persisted queue;
 * - "Call 112" is recorded (level 4).
 * Nothing here logs a number, a name, a message or a location.
 */
class CircleAlertCoordinator(
    private val scope: CoroutineScope,
    private val store: CircleAlertStore,
    private val repository: CircleAlertRepository,
    private val emergencyLocation: StateFlow<LocationSharingState>,
    session: Flow<AuthSession?>,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault
) : CircleAlertController, EmergencyRebinder {

    private val _state = MutableStateFlow(CircleAlertState())
    override val state: StateFlow<CircleAlertState> = _state.asStateFlow()

    private var snapshot: CircleAlertSnapshot? = null
    private var personName: String = ""
    private var activeEmergency: Pair<Long, Instant>? = null

    private val persistSignal = Channel<Unit>(Channel.CONFLATED)
    private val uploadSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch { session.collect { onSession(it) } }
        scope.launch { persistLoop() }
        scope.launch { uploadLoop() }
    }

    // ---- public API ----------------------------------------------------------------------------

    override fun updateRecipients(contacts: List<SafetyContact>) {
        val recipients = contacts.filter { it.isEnabled }
            .sortedWith(compareByDescending<SafetyContact> { it.isPrimary }.thenBy { it.priority }.thenBy { it.name })
            .map { CircleRecipient(it.id, it.name, it.phoneNumber) }
        mutate { it.copy(recipients = recipients) }
    }

    override fun onEmergencyActive(emergencyId: Long, startedAt: Instant) {
        if (activeEmergency?.first == emergencyId) return
        activeEmergency = emergencyId to startedAt
    }

    override fun onEmergencyEnded(emergencyId: Long, resolved: Boolean) {
        if (activeEmergency?.first == emergencyId) activeEmergency = null
    }

    override fun composerText(target: AlertTarget, kind: CircleMessageKind): String {
        val (point, at) = latestLocation(target)
        val eventTime = (target as? AlertTarget.Emergency)?.let { activeEmergency?.takeIf { e -> e.first == it.id }?.second } ?: now()
        return CircleMessages.compose(kind, personName, eventTime, zone(), point, at, concern = null, automatic = false)
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
     * Phase 11: the server now has the SOS this phone started under [localId]. What was recorded (deliveries, reports
     * waiting for the server) moves to [serverId], so every report reaches the right emergency.
     */
    override fun rebindEmergency(localId: Long, serverId: Long) {
        val from = AlertTarget.Emergency(localId)
        val to = AlertTarget.Emergency(serverId)
        activeEmergency?.takeIf { it.first == localId }?.let { activeEmergency = serverId to it.second }
        mutate { s ->
            s.copy(
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

    // ---- session -------------------------------------------------------------------------------

    private suspend fun onSession(session: AuthSession?) {
        // Another account signed in on this phone (Phase 11 C1): nothing of the previous one may carry over.
        val switched = session != null && snapshot != null && snapshot?.userId != session.user.id
        if (session == null || switched) {
            // A real sign-out: forget everything kept for the previous person. Phase 11 C1: an expired token is not a
            // sign-out, so the Safety Circle copy for offline SMS stays while the person is asked to sign in to sync.
            snapshot = null
            personName = ""
            activeEmergency = null
            store.clear()
            _state.value = CircleAlertState(loaded = true)
        }
        if (session == null) return
        personName = session.user.name
        if (snapshot?.userId == session.user.id) {
            uploadSignal.trySend(Unit) // Phase 11 C1: a new token (refresh, signed in again): send waiting reports
            return
        }
        val stored = store.load()?.takeIf { it.userId == session.user.id }
        snapshot = stored ?: CircleAlertSnapshot(userId = session.user.id)
        publish()
        persistSignal.trySend(Unit) // N6: rewrites a 4D record without its old automatic-SMS fields
        uploadSignal.trySend(Unit)
    }

    // ---- bookkeeping ---------------------------------------------------------------------------

    /** Adds or replaces a delivery (same reference) and queues it for the server. Walk targets are not reported (N6-B1). */
    private fun record(delivery: CircleDelivery) {
        mutate { s ->
            val deliveries = (s.deliveries.filterNot { it.clientRef == delivery.clientRef } + delivery).takeLast(MAX_DELIVERIES)
            val pending = if (delivery.target is AlertTarget.Walk) {
                s.pendingReports
            } else {
                (s.pendingReports.filterNot { it.clientRef == delivery.clientRef } + delivery).takeLast(MAX_PENDING)
            }
            s.copy(deliveries = deliveries, pendingReports = pending)
        }
        uploadSignal.trySend(Unit)
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

    /** Reports to the server in order, per emergency, at most 20 at a time; offline -> backoff, like the other queues. */
    private suspend fun uploadLoop() {
        var retryDelay = INITIAL_RETRY_MILLIS
        while (true) {
            val batch = snapshot?.pendingReports?.let { pending ->
                // Phase 11: reports about an SOS the server does not have yet wait until it has it (rebind).
                val first = pending.firstOrNull { it.target is AlertTarget.Emergency && !EmergencyIds.isLocal(it.target.id) }
                    ?: return@let null
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
                    // "Sign in to sync" (Phase 11 C1): reports stay; the next session or token sends them.
                    ApiError.UNAUTHORIZED -> uploadSignal.receive()
                    else -> removePending(batch) // rejected: retrying cannot help
                }
            }
        }
    }

    /** Removes exactly what was sent; a newer status for the same reference (queued meanwhile) stays. */
    private fun removePending(sent: List<CircleDelivery>) {
        mutate { s -> s.copy(pendingReports = s.pendingReports.filterNot { it in sent }) }
    }

    private fun latestLocation(target: AlertTarget): Pair<GeoPoint?, Instant?> {
        // N6 (D22): a walk has no location; only an SOS's own location ever goes into a text.
        val fix = (target as? AlertTarget.Emergency)?.let { e ->
            emergencyLocation.value.takeIf { it.emergencyId == e.id }?.lastFix
        }
        return fix?.let { GeoPoint.of(it) to it.recordedAt } ?: (null to null)
    }

    companion object {
        const val MAX_DELIVERIES = 60
        const val MAX_PENDING = 200
        const val MAX_BATCH = 20
        const val INITIAL_RETRY_MILLIS = 5_000L
        const val MAX_RETRY_MILLIS = 60_000L
        const val DIAL_PROMPT_ATTEMPTS = 5
        private val RETRYABLE = setOf(ApiError.NO_CONNECTION, ApiError.SERVER, ApiError.UNKNOWN, ApiError.TOO_MANY_ATTEMPTS)
    }
}
