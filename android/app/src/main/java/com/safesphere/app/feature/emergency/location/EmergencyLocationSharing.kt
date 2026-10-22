package com.safesphere.app.feature.emergency.location

import com.safesphere.app.R
import com.safesphere.app.core.device.BatteryMonitor
import com.safesphere.app.core.device.ConnectivityMonitor
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.location.LocationFix
import com.safesphere.app.core.location.LocationProvider
import com.safesphere.app.core.location.LocationUnavailableReason
import com.safesphere.app.core.location.LocationUpdate
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.tracking.TrackingNotice
import com.safesphere.app.core.tracking.TrackingNoticeSource
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.emergency.domain.EmergencyIds
import com.safesphere.app.feature.emergency.domain.EmergencyLocationPoint
import com.safesphere.app.feature.emergency.domain.EmergencyLocationRepository
import com.safesphere.app.feature.emergency.session.EmergencyRebinder
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one shared holder for emergency location sharing (Phase 4A). The emergency screen reads [state];
 * the tracking foreground service reads [notice]. Work runs in the application [scope], so it continues
 * when the person leaves the SOS screen; the foreground service only keeps the process alive.
 *
 * Flow: fix from [LocationProvider] -> [LocationSamplingPolicy] -> in-memory queue (+ battery level)
 * -> uploaded in batches of up to 50 -> removed when the server confirms.
 *
 * Offline: points stay queued (newest [MAX_PENDING] kept) and are uploaded when the connection returns,
 * with exponential backoff while the server cannot be reached. The queue is deliberately kept IN MEMORY
 * only, so precise locations are never written to the phone's storage; if the process dies, queued
 * points are lost, but the emergency itself is recovered from the server or the local record.
 *
 * Threading: [scope] must be single-threaded (Dispatchers.Main in the app, a test dispatcher in tests);
 * every mutable field below is only touched from it.
 */
class EmergencyLocationSharing(
    private val scope: CoroutineScope,
    private val locationProvider: LocationProvider,
    private val repository: EmergencyLocationRepository,
    private val batteryMonitor: BatteryMonitor,
    private val connectivityMonitor: ConnectivityMonitor,
    session: Flow<AuthSession?>,
    private val samplingPolicy: LocationSamplingPolicy = LocationSamplingPolicy(),
    private val now: () -> Instant = Instant::now
) : LocationSharingController, TrackingNoticeSource, EmergencyRebinder {

    private val _state = MutableStateFlow(LocationSharingState())
    override val state: StateFlow<LocationSharingState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<TrackingNotice?>(null)
    val notice: StateFlow<TrackingNotice?> = _notice.asStateFlow()

    override fun currentNotice(): TrackingNotice? = _notice.value
    override val notices: Flow<TrackingNotice?> get() = notice

    private val pending = ArrayDeque<EmergencyLocationPoint>()
    private var lastRecorded: LocationFix? = null
    private val restarts = MutableStateFlow(0)
    private var job: Job? = null

    /**
     * Phase 11: where queued points go. A LOCAL id ([EmergencyIds.isLocal]) means the server does not have the SOS yet:
     * points are collected but not sent until [rebindEmergency] gives the server's id.
     */
    private var uploadTarget: Long? = null
    private var uploadSignal: Channel<Unit>? = null

    /** Phase 11: points of an SOS that ended before the server had it (memory only), sent once it has. */
    private val parked = HashMap<Long, List<EmergencyLocationPoint>>()

    init {
        // Logout or an expired token (ApiCallHandler clears the session on 401) ends the sharing at once.
        scope.launch {
            session.collect { if (it == null) onSignedOut() }
        }
    }

    override fun start(emergencyId: Long, startedAt: Instant) {
        val current = _state.value
        if (current.emergencyId == emergencyId && !current.signedOut && job?.isActive == true) {
            refresh()
            return
        }
        job?.cancel()
        pending.clear()
        lastRecorded = null
        uploadTarget = emergencyId
        val access = locationProvider.access()
        update {
            LocationSharingState(
                emergencyId = emergencyId,
                access = access,
                status = if (access == LocationAccess.NONE) LocationSharingStatus.PERMISSION_NEEDED else LocationSharingStatus.SEARCHING
            )
        }
        job = scope.launch { run(startedAt) }
    }

    override fun stop() {
        val current = _state.value
        val emergencyId = current.emergencyId ?: return
        job?.cancel()
        job = null
        uploadTarget = null
        uploadSignal = null
        val leftovers = pending.toList()
        pending.clear()
        lastRecorded = null
        update { LocationSharingState() } // OFF: the notice becomes null and the service stops itself
        if (leftovers.isNotEmpty() && EmergencyIds.isLocal(emergencyId) && !current.signedOut) {
            // The server does not have this SOS yet: keep its points (in memory) until it does.
            if (parked.size >= MAX_PARKED) parked.remove(parked.keys.first())
            parked[emergencyId] = leftovers
            return
        }
        if (leftovers.isNotEmpty() && !current.signedOut && !current.uploadsStopped) {
            // One best-effort attempt; the server accepts points recorded while active for 15 minutes after the end.
            scope.launch {
                leftovers.chunked(EmergencyLocationRepository.MAX_BATCH_SIZE).forEach { repository.upload(emergencyId, it) }
            }
        }
    }

    override fun refresh() {
        val current = _state.value
        if (current.emergencyId == null || current.signedOut || job?.isActive != true) return
        val access = locationProvider.access()
        when {
            access != current.access || current.status in RESTART_STATUSES -> {
                update {
                    it.copy(
                        access = access,
                        status = if (access == LocationAccess.NONE) LocationSharingStatus.PERMISSION_NEEDED else LocationSharingStatus.SEARCHING
                    )
                }
                restarts.value++
            }
            current.status == LocationSharingStatus.LOCATION_DISABLED && locationProvider.isLocationEnabled() ->
                // The running request resumes by itself once location is back on.
                update { it.copy(status = LocationSharingStatus.SEARCHING) }
        }
    }

    /**
     * Phase 11: the server now has the SOS kept under [localId]. Queued points go to [serverId] from now on; points of
     * an SOS that already ended are sent in one best-effort attempt (the server accepts them for 15 minutes).
     */
    override fun rebindEmergency(localId: Long, serverId: Long) {
        if (_state.value.emergencyId == localId) {
            uploadTarget = serverId
            update { it.copy(emergencyId = serverId) }
            uploadSignal?.trySend(Unit)
        }
        val leftovers = parked.remove(localId) ?: return
        scope.launch {
            leftovers.chunked(EmergencyLocationRepository.MAX_BATCH_SIZE).forEach { repository.upload(serverId, it) }
        }
    }

    private suspend fun run(startedAt: Instant) = coroutineScope {
        val signal = Channel<Unit>(Channel.CONFLATED)
        uploadSignal = signal
        launch {
            connectivityMonitor.isOnline.collect { online ->
                update { it.copy(isOnline = online) }
                if (online) signal.trySend(Unit)
            }
        }
        launch { uploadLoop(signal) }
        restarts.collectLatest { collectLocations(startedAt, signal) }
    }

    private suspend fun collectLocations(startedAt: Instant, uploadSignal: Channel<Unit>) {
        val access = locationProvider.access()
        if (access == LocationAccess.NONE) {
            update { it.copy(access = access, status = LocationSharingStatus.PERMISSION_NEEDED) }
            return
        }
        update { it.copy(access = access) }
        locationProvider.updates(LOCATION_INTERVAL_MILLIS).collect { locationUpdate ->
            when (locationUpdate) {
                is LocationUpdate.Fix -> {
                    val fix = locationUpdate.fix
                    update { it.copy(status = LocationSharingStatus.AVAILABLE, lastFix = fix) }
                    if (samplingPolicy.shouldRecord(lastRecorded, fix, startedAt)) {
                        lastRecorded = fix
                        enqueue(EmergencyLocationPoint(fix, batteryMonitor.batteryPercent()))
                        uploadSignal.trySend(Unit)
                    }
                }
                is LocationUpdate.Unavailable -> update { it.copy(status = statusFor(locationUpdate.reason)) }
            }
        }
    }

    private suspend fun uploadLoop(signal: ReceiveChannel<Unit>) {
        var retryDelay = INITIAL_RETRY_MILLIS
        while (true) {
            val emergencyId = uploadTarget
            if (pending.isEmpty() || _state.value.uploadsStopped || emergencyId == null || EmergencyIds.isLocal(emergencyId)) {
                signal.receive()
                continue
            }
            val batch = pending.take(EmergencyLocationRepository.MAX_BATCH_SIZE)
            when (val result = repository.upload(emergencyId, batch)) {
                is ApiResult.Success -> {
                    removeFromQueue(batch)
                    retryDelay = INITIAL_RETRY_MILLIS
                    update { it.copy(lastSavedAt = now()) }
                }
                is ApiResult.Failure -> when (result.error) {
                    // FORBIDDEN is never sent for SOS locations; if a proxy sends it, an emergency keeps trying.
                    ApiError.NO_CONNECTION, ApiError.SERVER, ApiError.UNKNOWN, ApiError.TOO_MANY_ATTEMPTS,
                    ApiError.FORBIDDEN -> {
                        // Wait for the backoff, or less if the connection comes back first.
                        withTimeoutOrNull(retryDelay) { signal.receive() }
                        retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_MILLIS)
                    }
                    ApiError.VALIDATION -> {
                        // Invalid data will not become valid by retrying; drop it and say so.
                        removeFromQueue(batch)
                        update { it.copy(rejectedCount = it.rejectedCount + batch.size) }
                    }
                    ApiError.CONFLICT, ApiError.NOT_FOUND -> {
                        pending.clear()
                        update {
                            it.copy(pendingCount = 0, uploadsStopped = true,
                                uploadsStoppedMessage = if (result.error == ApiError.CONFLICT) result.message else null)
                        }
                    }
                    ApiError.UNAUTHORIZED -> return // the session observer stops everything
                }
            }
        }
    }

    private fun enqueue(point: EmergencyLocationPoint) {
        if (pending.size >= MAX_PENDING) pending.removeFirst() // keep the newest: they matter most
        pending.addLast(point)
        update { it.copy(pendingCount = pending.size) }
    }

    private fun removeFromQueue(batch: List<EmergencyLocationPoint>) {
        val sent = batch.toSet()
        pending.removeAll { it in sent }
        update { it.copy(pendingCount = pending.size) }
    }

    private fun onSignedOut() {
        parked.clear()
        if (_state.value.emergencyId == null) return
        job?.cancel()
        job = null
        pending.clear()
        update { it.copy(signedOut = true, pendingCount = 0) }
    }

    /** Every change goes through here so [state] and [notice] can never disagree. */
    private fun update(transform: (LocationSharingState) -> LocationSharingState) {
        val next = transform(_state.value)
        _state.value = next
        _notice.value = noticeFor(next)
    }

    companion object {
        const val LOCATION_INTERVAL_MILLIS = 10_000L
        const val MAX_PENDING = 500
        const val MAX_PARKED = 5
        const val INITIAL_RETRY_MILLIS = 5_000L
        const val MAX_RETRY_MILLIS = 60_000L

        private val RESTART_STATUSES = setOf(
            LocationSharingStatus.PERMISSION_NEEDED,
            LocationSharingStatus.SERVICES_UNAVAILABLE
        )

        fun statusFor(reason: LocationUnavailableReason): LocationSharingStatus = when (reason) {
            LocationUnavailableReason.PERMISSION_DENIED -> LocationSharingStatus.PERMISSION_NEEDED
            LocationUnavailableReason.LOCATION_DISABLED -> LocationSharingStatus.LOCATION_DISABLED
            LocationUnavailableReason.NO_FIX -> LocationSharingStatus.UNAVAILABLE
            LocationUnavailableReason.SERVICES_UNAVAILABLE -> LocationSharingStatus.SERVICES_UNAVAILABLE
        }

        /** The notification text. Never contains the location itself (lock-screen privacy). */
        fun noticeFor(state: LocationSharingState): TrackingNotice? {
            if (!state.isTracking) return null
            val text = when {
                state.pendingCount > 0 && !state.isOnline -> R.string.tracking_notification_offline
                state.status == LocationSharingStatus.LOCATION_DISABLED -> R.string.tracking_notification_location_off
                state.status == LocationSharingStatus.AVAILABLE -> R.string.tracking_notification_saving
                else -> R.string.tracking_notification_searching
            }
            return TrackingNotice(R.string.tracking_notification_title, text)
        }
    }
}
