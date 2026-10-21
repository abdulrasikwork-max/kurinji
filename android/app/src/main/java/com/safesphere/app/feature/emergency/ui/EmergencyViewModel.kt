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
import com.safesphere.app.feature.emergency.domain.ActiveEmergencyStore
import com.safesphere.app.feature.emergency.domain.EmergencyEvent
import com.safesphere.app.feature.emergency.domain.EmergencyRepository
import com.safesphere.app.feature.emergency.domain.EmergencyStatus
import com.safesphere.app.feature.emergency.domain.EmergencyTrigger
import com.safesphere.app.feature.emergency.location.LocationSharingController
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
    private val activeEmergencyStore: ActiveEmergencyStore,
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
    }

    /**
     * If an emergency is still ACTIVE (app was closed meanwhile), show it again. The server is asked first;
     * if it cannot be reached, the record kept on the phone is used, so an offline restart does not
     * silently show "no emergency".
     */
    private fun restoreActiveEmergency() {
        viewModelScope.launch {
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
                    val record = if (result.isSessionExpired()) null else ownStoredRecord()
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
        circleAlerts?.onDialPromptOpened(active.event.id)
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
    }

    // ---- internals ------------------------------------------------------------------------------

    private suspend fun startEmergency() {
        _uiState.value = EmergencyUiState.Starting
        when (val result = emergencyRepository.start()) {
            is ApiResult.Success -> {
                onEmergencyStarted()
                showActive(result.data)
            }
            is ApiResult.Failure -> {
                if (result.error == ApiError.CONFLICT) {
                    recoverExistingEmergency(result)
                } else {
                    reportIfSessionExpired(result)
                    _uiState.value = EmergencyUiState.StartFailed(result)
                }
            }
        }
    }

    /** The server says an emergency is already active (for example started on another phone): show that one. */
    private suspend fun recoverExistingEmergency(conflict: ApiResult.Failure) {
        val history = emergencyRepository.getHistory()
        val active = (history as? ApiResult.Success)?.data?.firstOrNull { it.status == EmergencyStatus.ACTIVE }
        if (active != null) {
            onEmergencyStarted()
            showActive(active)
        } else {
            _uiState.value = EmergencyUiState.StartFailed(conflict)
        }
    }

    private fun endActiveEmergency(resolve: Boolean) {
        val current = _uiState.value as? EmergencyUiState.Active ?: return
        if (current.isEnding) return
        _uiState.value = current.copy(isEnding = true, failure = null)

        viewModelScope.launch {
            val result = if (resolve) {
                emergencyRepository.resolve(current.event.id)
            } else {
                emergencyRepository.cancel(current.event.id)
            }
            when (result) {
                is ApiResult.Success -> {
                    forgetActive()
                    circleAlerts?.onEmergencyEnded(current.event.id, resolved = resolve)
                    _uiState.value = EmergencyUiState.Ended(result.data)
                }
                is ApiResult.Failure -> {
                    reportIfSessionExpired(result)
                    _uiState.value = current.copy(isEnding = false, failure = result)
                }
            }
        }
    }

    /** Location saving starts first (it must not wait for the contact list), then the screen is shown. */
    private suspend fun showActive(event: EmergencyEvent, restoredOffline: Boolean = false) {
        locationSharing.start(event.id, event.startedAt)
        circleAlerts?.onEmergencyActive(event.id, event.startedAt)
        currentUserId()?.let { userId ->
            activeEmergencyStore.save(ActiveEmergencyRecord(userId, event.id, event.startedAt))
        }
        _uiState.value = activeStateFor(event, restoredOffline)
    }

    /** No emergency is active any more: stop location saving and drop the record kept on the phone. */
    private suspend fun forgetActive() {
        locationSharing.stop()
        activeEmergencyStore.clear()
    }

    /** The stored record, but only if it belongs to the person signed in now. */
    private suspend fun ownStoredRecord(): ActiveEmergencyRecord? {
        val record = activeEmergencyStore.load() ?: return null
        return record.takeIf { it.userId == currentUserId() }
    }

    /** Builds the Active state, including the enabled contacts to display (display only: nobody is contacted). */
    private suspend fun activeStateFor(event: EmergencyEvent, restoredOffline: Boolean): EmergencyUiState.Active {
        val circle = when (val result = safetyCircleRepository.getContacts()) {
            is ApiResult.Success -> result.data.filter { it.isEnabled }
            is ApiResult.Failure -> {
                reportIfSessionExpired(result)
                emptyList()
            }
        }
        return EmergencyUiState.Active(event, circle, restoredOffline = restoredOffline)
    }

    private fun reportIfSessionExpired(result: ApiResult<*>) {
        if (result.isSessionExpired()) _sessionExpired.value = true
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

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                EmergencyViewModel(
                    emergencyRepository = container.emergencyRepository,
                    safetyCircleRepository = container.safetyCircleRepository,
                    locationSharing = container.locationSharing,
                    activeEmergencyStore = container.activeEmergencyStore,
                    currentUserId = { container.authRepository.currentUser.first()?.id },
                    trackingServiceRunning = container.trackingServiceRunning,
                    onEmergencyStarted = container.safeWalk::onEmergencyStarted,
                    circleAlerts = container.circleAlerts
                )
            }
        }
    }
}
