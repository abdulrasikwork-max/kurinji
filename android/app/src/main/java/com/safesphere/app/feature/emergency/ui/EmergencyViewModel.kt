package com.safesphere.app.feature.emergency.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.location.LocationPermissionStatus
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.emergency.domain.ActiveEmergencyRecord
import com.safesphere.app.feature.emergency.domain.EmergencyEvent
import com.safesphere.app.feature.emergency.domain.EmergencyIds
import com.safesphere.app.feature.emergency.domain.EmergencyRepository
import com.safesphere.app.feature.emergency.domain.EmergencyStatus
import com.safesphere.app.feature.emergency.domain.EmergencyTrigger
import com.safesphere.app.feature.emergency.location.LocationSharingController
import com.safesphere.app.feature.emergency.session.SosRecorder
import com.safesphere.app.feature.emergency.session.SosRecorderState
import com.safesphere.app.feature.safetycircle.domain.Relationship
import com.safesphere.app.feature.circlealert.domain.AlertTarget
import com.safesphere.app.feature.circlealert.domain.CircleMessageKind
import com.safesphere.app.feature.circlealert.domain.CircleRecipient
import com.safesphere.app.feature.circlealert.session.CircleAlertController
import com.safesphere.app.feature.circlealert.session.CircleAlertState
import com.safesphere.app.feature.safetycircle.domain.SafetyCircleRepository
import com.safesphere.app.feature.safetycircle.domain.SafetyContact
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Runs the manual SOS: countdown, recording, location saving and ending.
 *
 * Phase 4A adds: while an emergency is ACTIVE, the phone's location is saved to the person's own emergency
 * record ([LocationSharingController]); the active emergency is also remembered on the phone
 * ([ActiveEmergencyStore]) so it can be shown again when the app is reopened offline.
 * Phase 4D adds: the Safety Circle is texted automatically ONLY if the person opted in ([CircleAlertController]);
 * otherwise the person can open the SMS app per contact. "Call 112" opens the dialer (level 4); the app never calls
 * by itself. No microphone or camera.
 */
class EmergencyViewModel(
    private val emergencyRepository: EmergencyRepository,
    private val safetyCircleRepository: SafetyCircleRepository,
    private val locationSharing: LocationSharingController,
    /** Phase 11: starts every SOS on the phone and records it on the server when it can (the one holder). */
    private val sos: SosRecorder,
    private val currentUserId: suspend () -> Long?,
    trackingServiceRunning: StateFlow<Boolean>,
    /** Phase 4B: the server ends an active Safe Walk when an SOS starts; this tells the phone's walk to stop. */
    private val onEmergencyStarted: () -> Unit = {},
    /** Phase 4D: Safety Circle communication (automatic SMS if opted in, SMS app, "Call 112"). */
    private val circleAlerts: CircleAlertController? = null,
    private val countdownSeconds: Int = DEFAULT_COUNTDOWN_SECONDS
) : ViewModel() {

    /** What the Safety Circle has been told about this emergency (per contact), and whether automatic SMS is on. */
    val circleAlertState: StateFlow<CircleAlertState> = circleAlerts?.state ?: MutableStateFlow(CircleAlertState()).asStateFlow()

    private val _uiState = MutableStateFlow<EmergencyUiState>(EmergencyUiState.Loading)
    val uiState: StateFlow<EmergencyUiState> = _uiState.asStateFlow()

    private val _sessionExpired = MutableStateFlow(false)
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    private val permissionBlocked = MutableStateFlow(false)

    val locationUi: StateFlow<EmergencyLocationUi> =
        combine(locationSharing.state, permissionBlocked, trackingServiceRunning) { sharing, blocked, running ->
            EmergencyLocationUi(sharing, blocked, running)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, EmergencyLocationUi())

    private var countdownJob: Job? = null
    private var autoLocationRequestUsed = false
    private var notificationPromptUsed = false

    init {
        restoreActiveEmergency()
        viewModelScope.launch {
            locationSharing.state.collect { sharing ->
                // The location upload hit a 401 (or the person logged out elsewhere): same handling as other calls.
                if (sharing.signedOut && _uiState.value is EmergencyUiState.Active) _sessionExpired.value = true
            }
        }
        viewModelScope.launch { sos.state.collect(::onRecorderChanged) }
    }

    /**
     * Phase 11: the server recorded (or refused) the SOS this screen shows with a local id, or was told how an SOS
     * that ended offline ended. Only the screen's words change: the holders were already moved to the server's id.
     */
    private fun onRecorderChanged(recorder: SosRecorderState) {
        when (val state = _uiState.value) {
            is EmergencyUiState.Active -> {
                if (!state.notOnServerYet) return
                val bound = recorder.current?.takeIf { it.onServer && it.clientRef != null && it.clientRef == state.clientRef }
                when {
                    bound != null -> _uiState.value = state.copy(
                        event = recorder.lastServerAnswer?.takeIf { it.id == bound.emergencyId }
                            ?: state.event.copy(id = bound.emergencyId),
                        recordRefused = false
                    )
                    recorder.refused && recorder.current?.clientRef == state.clientRef && !state.recordRefused ->
                        _uiState.value = state.copy(recordRefused = true)
                }
            }
            is EmergencyUiState.Ended -> {
                val unsent = state.unsent ?: return
                val stillWaiting = recorder.unsent.any { it.sameSos(unsent) }
                if (!stillWaiting) _uiState.value = state.copy(unsent = null)
            }
            else -> Unit
        }
    }

    private fun ActiveEmergencyRecord.sameSos(other: ActiveEmergencyRecord): Boolean =
        if (clientRef != null) clientRef == other.clientRef else emergencyId == other.emergencyId

    /**
     * If an emergency is still ACTIVE (app was closed meanwhile), show it again. Phase 11: an SOS this phone started
     * that the server has not recorded yet is shown first. Otherwise the server is asked; if it cannot be reached, the
     * record kept on the phone is used, so an offline restart does not silently show "no emergency".
     */
    private fun restoreActiveEmergency() {
        viewModelScope.launch {
            val local = currentUserId()?.let { sos.current(it) }
            if (local != null && !local.onServer) {
                showActive(local.toEvent(), clientRef = local.clientRef)
                return@launch
            }
            when (val result = emergencyRepository.getHistory()) {
                is ApiResult.Success -> {
                    val active = result.data.firstOrNull { it.status == EmergencyStatus.ACTIVE }
                    if (active != null) {
                        showActive(active)
                    } else {
                        forgetActive()
                        _uiState.value = EmergencyUiState.Idle
                    }
                }
                is ApiResult.Failure -> {
                    reportIfSessionExpired(result)
                    // Phase 11 C1: "sign in to sync" keeps the session, so the SOS kept on this phone is still shown.
                    val record = if (result.isSessionExpired() && currentUserId() == null) null else local
                    if (record != null) {
                        showActive(record.toEvent(), restoredOffline = true)
                    } else {
                        _uiState.value = EmergencyUiState.Idle
                    }
                }
            }
        }
    }

    fun onStartSosClicked() {
        if (_uiState.value != EmergencyUiState.Idle) return
        // Set synchronously so a second tap (before the coroutine starts) is ignored.
        _uiState.value = EmergencyUiState.Countdown(countdownSeconds)
        countdownJob = viewModelScope.launch {
            for (seconds in countdownSeconds downTo 1) {
                _uiState.value = EmergencyUiState.Countdown(seconds)
                delay(TICK_MILLIS)
            }
            startEmergency()
        }
    }

    /** CANCEL during the countdown: nothing was recorded, so we simply return to Idle. */
    fun onCancelCountdownClicked() {
        if (_uiState.value !is EmergencyUiState.Countdown) return
        countdownJob?.cancel()
        _uiState.value = EmergencyUiState.Idle
    }

    fun onRetryStartClicked() {
        if (_uiState.value !is EmergencyUiState.StartFailed) return
        _uiState.value = EmergencyUiState.Starting // synchronous: a second tap is ignored
        viewModelScope.launch { startEmergency() }
    }

    fun onDismissStartFailureClicked() {
        if (_uiState.value is EmergencyUiState.StartFailed) _uiState.value = EmergencyUiState.Idle
    }

    /** "I'm safe": ends the emergency as RESOLVED. */
    fun onImSafeClicked() = endActiveEmergency(resolve = true)

    /** "False alarm": ends the emergency as CANCELLED. */
    fun onFalseAlarmClicked() = endActiveEmergency(resolve = false)

    fun onSessionExpiredHandled() {
        _sessionExpired.value = false
    }

    // ---- Phase 4D: Safety Circle and level 4 ------------------------------------------------------

    /** Level 4: the screen is opening the dialer with 112. Recorded for the active emergency (nothing is claimed). */
    fun onCallEmergencyNumberClicked() {
        val active = _uiState.value as? EmergencyUiState.Active ?: return
        // Phase 11: before the server has the SOS, the tap is remembered and recorded once it has.
        if (active.notOnServerYet) sos.onDialPromptOpened() else circleAlerts?.onDialPromptOpened(active.event.id)
    }

    /** Text for the SMS app (sent by the person). Null when no emergency is active. */
    fun composerTextFor(): String? {
        val active = _uiState.value as? EmergencyUiState.Active ?: return null
        return circleAlerts?.composerText(AlertTarget.Emergency(active.event.id), CircleMessageKind.SOS_STARTED)
    }

    fun onComposerOpened(contact: SafetyContact) {
        val active = _uiState.value as? EmergencyUiState.Active ?: return
        circleAlerts?.onComposerOpened(
            AlertTarget.Emergency(active.event.id), CircleMessageKind.SOS_STARTED,
            CircleRecipient(contact.id, contact.name, contact.phoneNumber)
        )
    }

    // ---- location permission (asked in context: when an emergency is active) -------------------

    /** True exactly once per screen session, so the system dialog opens automatically only the first time. */
    fun consumeAutoLocationRequest(): Boolean {
        if (autoLocationRequestUsed) return false
        autoLocationRequestUsed = true
        return true
    }

    /** True once: Android 13+ notification permission is asked right before the tracking notification. */
    fun consumeNotificationPrompt(): Boolean {
        if (notificationPromptUsed) return false
        notificationPromptUsed = true
        return true
    }

    fun onLocationPermissionResult(status: LocationPermissionStatus) {
        permissionBlocked.value = status == LocationPermissionStatus.PERMANENTLY_DENIED
        if (status == LocationPermissionStatus.GRANTED_PRECISE || status == LocationPermissionStatus.GRANTED_APPROXIMATE) {
            locationSharing.refresh()
        }
    }

    /** The screen became visible again (for example back from Settings): permission or GPS may have changed. */
    fun onScreenVisible() {
        locationSharing.refresh()
        recheckActiveEmergency()
    }

    // ---- internals ------------------------------------------------------------------------------

    /**
     * Phase 11: the SOS starts ON THE PHONE at once; [SosRecorder] records it on the server now or as soon as the
     * server can be reached (the same SOS, never a second one). Without a connection the Safety Circle is still texted
     * (automatic SMS if switched on, otherwise the SMS app per contact) and location is still collected. If the server
     * already has a running SOS (another phone), the recorder joins it and this screen then shows that one.
     */
    private suspend fun startEmergency() {
        _uiState.value = EmergencyUiState.Starting
        val userId = currentUserId()
        if (userId == null) {
            // Signed out meanwhile: no account to keep the SOS under. Call 112 stays one tap away on this panel.
            _sessionExpired.value = true
            _uiState.value = EmergencyUiState.StartFailed(ApiResult.Failure(ApiError.UNAUTHORIZED))
            return
        }
        val record = sos.startLocal(userId)
        onEmergencyStarted()
        showActive(record.toEvent(), clientRef = record.clientRef)
    }

    /** Phase 10A.4: the person typed the account password after "started on another phone". */
    fun onEndWithPassword(password: String) {
        val current = _uiState.value as? EmergencyUiState.Active ?: return
        val action = current.passwordNeededFor ?: return
        if (password.isEmpty()) return
        endActiveEmergency(resolve = action == EndAction.RESOLVE, password = password)
    }

    /** The password prompt was closed without a password: the SOS stays active. */
    fun onEndPasswordDismissed() {
        val current = _uiState.value as? EmergencyUiState.Active ?: return
        _uiState.value = current.copy(passwordNeededFor = null)
    }

    private fun endActiveEmergency(resolve: Boolean, password: String? = null) {
        val current = _uiState.value as? EmergencyUiState.Active ?: return
        if (current.isEnding) return
        _uiState.value = current.copy(isEnding = true, failure = null, passwordNeededFor = null)

        viewModelScope.launch {
            // Phase 11: the server does not have this SOS yet: it ends here and is recorded later as ended.
            if (current.notOnServerYet) {
                endOnThisPhone(current, resolve)
                return@launch
            }
            val result = if (resolve) {
                emergencyRepository.resolve(current.event.id, password)
            } else {
                emergencyRepository.cancel(current.event.id, password)
            }
            when {
                result is ApiResult.Success -> {
                    forgetActive()
                    circleAlerts?.onEmergencyEnded(current.event.id, resolved = resolve)
                    _uiState.value = EmergencyUiState.Ended(result.data)
                }
                // Phase 10A.4: started on another phone: ask for the account password (once per tap).
                result is ApiResult.Failure && result.error == ApiError.FORBIDDEN && password == null -> {
                    _uiState.value = current.copy(isEnding = false, passwordNeededFor =
                        if (resolve) EndAction.RESOLVE else EndAction.CANCEL)
                }
                // Phase 11: no connection: the person's "I'm safe" still counts on this phone (and for the Safety
                // Circle); the server is told later with the real time. Not with a password (another phone's SOS).
                result is ApiResult.Failure && result.error in OFFLINE_ERRORS && password == null ->
                    endOnThisPhone(current, resolve)
                // Phase 11 C1: "sign in to sync" (still this person's session): the "I'm safe" counts here too and is
                // recorded once they sign in again (that sign-in takes over this phone's SOS, so no password is asked).
                result is ApiResult.Failure && result.error == ApiError.UNAUTHORIZED && password == null &&
                    currentUserId() != null -> endOnThisPhone(current, resolve)
                result is ApiResult.Failure -> {
                    reportIfSessionExpired(result)
                    // Never ask again by itself after a wrong password: the person taps "I'm safe" again.
                    _uiState.value = current.copy(isEnding = false, failure = result, passwordNeededFor = null)
                }
            }
        }
    }

    /**
     * Phase 10A.4: the SOS may have been ended from another sign-in (with the account password) while this screen was
     * away. If so, this phone stops sharing its location and shows that it was ended elsewhere. It does NOT send the
     * Safety Circle the "resolved" message: nobody on this phone said they are OK.
     */
    /** Phase 11: ends the SOS on this phone; [SosRecorder] tells the server later (it keeps the end time). */
    private suspend fun endOnThisPhone(current: EmergencyUiState.Active, resolve: Boolean) {
        val ended = sos.endLocally(resolve)
        locationSharing.stop() // points of an SOS the server does not have yet are kept (in memory) until it has
        circleAlerts?.onEmergencyEnded(current.event.id, resolved = resolve)
        _uiState.value = EmergencyUiState.Ended(
            current.event.copy(
                status = if (resolve) EmergencyStatus.RESOLVED else EmergencyStatus.CANCELLED,
                endedAt = ended?.ended?.at ?: java.time.Instant.now()
            ),
            unsent = ended
        )
    }

    private fun recheckActiveEmergency() {
        val current = _uiState.value as? EmergencyUiState.Active ?: return
        if (current.isEnding || current.restoredOffline || current.notOnServerYet) return
        viewModelScope.launch {
            val result = emergencyRepository.getHistory()
            if (result !is ApiResult.Success) return@launch
            val now = result.data.firstOrNull { it.id == current.event.id } ?: return@launch
            val still = _uiState.value as? EmergencyUiState.Active ?: return@launch
            if (now.status != EmergencyStatus.ACTIVE && still.event.id == now.id && !still.isEnding) {
                forgetActive()
                _uiState.value = EmergencyUiState.Ended(now)
            }
        }
    }

    /** Location saving starts first (it must not wait for the contact list), then the screen is shown. */
    private suspend fun showActive(event: EmergencyEvent, restoredOffline: Boolean = false, clientRef: String? = null) {
        // Phase 11: if the server recorded this phone's SOS already, use its id. Checked right before the holders
        // start (no suspension in between), so they can never be started with an id that was already moved.
        val shown = sos.state.value.current
            ?.takeIf { EmergencyIds.isLocal(event.id) && clientRef != null && it.clientRef == clientRef && it.onServer }
            ?.let { bound -> sos.state.value.lastServerAnswer?.takeIf { it.id == bound.emergencyId } ?: event.copy(id = bound.emergencyId) }
            ?: event
        locationSharing.start(shown.id, shown.startedAt)
        circleAlerts?.onEmergencyActive(shown.id, shown.startedAt)
        if (!EmergencyIds.isLocal(shown.id)) currentUserId()?.let { userId -> sos.adopt(userId, shown) }
        _uiState.value = activeStateFor(shown, restoredOffline).copy(clientRef = clientRef)
        // The server may have recorded the SOS while the contact list was loading (found on the emulator): the
        // recorder's change was seen before this screen state existed, so look at it again now.
        onRecorderChanged(sos.state.value)
    }

    /** No emergency is active any more: stop location saving and drop the record kept on the phone. */
    private suspend fun forgetActive() {
        locationSharing.stop()
        sos.forget()
    }

    /**
     * Builds the Active state, including the enabled contacts to display (display only: nobody is contacted by this).
     * Phase 11: without a connection the copy kept on the phone for SMS is shown, so "Text" works offline too.
     */
    private suspend fun activeStateFor(event: EmergencyEvent, restoredOffline: Boolean): EmergencyUiState.Active {
        val circle = when (val result = safetyCircleRepository.getContacts()) {
            is ApiResult.Success -> result.data.filter { it.isEnabled }
            is ApiResult.Failure -> {
                reportIfSessionExpired(result)
                // Phase 11 C1: with "sign in to sync" the Safety Circle copy kept for offline SMS is still this person's.
                if (result.isSessionExpired() && currentUserId() == null) emptyList() else offlineCircle()
            }
        }
        return EmergencyUiState.Active(event, circle, restoredOffline = restoredOffline)
    }

    /** The enabled contacts cached on the phone for SMS (name + number; the relationship is not kept there). */
    private fun offlineCircle(): List<SafetyContact> = circleAlerts?.state?.value?.recipients.orEmpty().map {
        SafetyContact(
            id = it.contactId, name = it.name, phoneNumber = it.phoneNumber, email = null,
            relationship = Relationship.OTHER, priority = 1, isPrimary = false, isEnabled = true
        )
    }

    /**
     * Only a real sign-out (no session left) interrupts the SOS screen with a dialog. Phase 11 C1: "sign in to sync"
     * never does; the SOS keeps working on the phone and Home shows a calm banner.
     */
    private suspend fun reportIfSessionExpired(result: ApiResult<*>) {
        if (result.isSessionExpired() && currentUserId() == null) _sessionExpired.value = true
    }

    private fun ActiveEmergencyRecord.toEvent() = EmergencyEvent(
        id = emergencyId,
        status = EmergencyStatus.ACTIVE,
        trigger = EmergencyTrigger.MANUAL, // the only trigger that can be created so far
        startedAt = startedAt,
        endedAt = null
    )

    companion object {
        const val DEFAULT_COUNTDOWN_SECONDS = 5
        const val TICK_MILLIS = 1_000L

        /** Ending the SOS while these come back is kept on the phone and told to the server later (Phase 11). */
        private val OFFLINE_ERRORS = setOf(ApiError.NO_CONNECTION, ApiError.SERVER, ApiError.UNKNOWN)

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                EmergencyViewModel(
                    emergencyRepository = container.emergencyRepository,
                    safetyCircleRepository = container.safetyCircleRepository,
                    locationSharing = container.locationSharing,
                    sos = container.sosRecorder,
                    currentUserId = { container.authRepository.currentUser.first()?.id },
                    trackingServiceRunning = container.trackingServiceRunning,
                    onEmergencyStarted = container.safeWalk::onEmergencyStarted,
                    circleAlerts = container.circleAlerts
                )
            }
        }
    }
}
