package com.safesphere.app.feature.safewalk.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.circlealert.domain.AlertTarget
import com.safesphere.app.feature.circlealert.domain.CircleMessageKind
import com.safesphere.app.feature.circlealert.domain.CircleRecipient
import com.safesphere.app.feature.circlealert.session.CircleAlertController
import com.safesphere.app.feature.circlealert.session.CircleAlertState
import com.safesphere.app.feature.safewalk.domain.GpsQuality
import com.safesphere.app.feature.safewalk.domain.JourneyStatus
import com.safesphere.app.feature.safewalk.domain.MovementPolicy
import java.time.Instant
import com.safesphere.app.feature.safewalk.session.SafeWalkController
import com.safesphere.app.feature.safewalk.session.SafeWalkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SafeWalkUiState {
    /** Checking whether a walk is already active (after a restart). */
    data object Loading : SafeWalkUiState

    data class Setup(val isStarting: Boolean = false, val failure: ApiResult.Failure? = null) : SafeWalkUiState

    data class Active(val walk: SafeWalkState) : SafeWalkUiState

    data class Ended(val status: JourneyStatus) : SafeWalkUiState
}

/** The Safe Walk screen. All walk logic lives in the shared [SafeWalkController]; this only maps it to UI. */
class SafeWalkViewModel(
    private val controller: SafeWalkController,
    val trackingServiceRunning: StateFlow<Boolean>,
    private val movementPolicy: MovementPolicy = MovementPolicy(),
    /** Phase 4D: what the Safety Circle was told about this walk, and the manual SMS fallback. */
    private val circleAlerts: CircleAlertController? = null
) : ViewModel() {

    val circleAlertState: StateFlow<CircleAlertState> = circleAlerts?.state ?: MutableStateFlow(CircleAlertState()).asStateFlow()

    /** Text for the SMS app (sent by the person themselves). Null when no walk is active. */
    fun composerText(): String? {
        val journey = controller.state.value.journey?.takeIf { controller.state.value.isActive } ?: return null
        return circleAlerts?.composerText(AlertTarget.Walk(journey.id), CircleMessageKind.WALK_NO_RESPONSE)
    }

    fun onComposerOpened(recipient: CircleRecipient) {
        val journey = controller.state.value.journey ?: return
        circleAlerts?.onComposerOpened(AlertTarget.Walk(journey.id), CircleMessageKind.WALK_NO_RESPONSE, recipient)
    }

    private val setup = MutableStateFlow(SafeWalkUiState.Setup())
    private val recovered = MutableStateFlow(false)

    val uiState: StateFlow<SafeWalkUiState> =
        combine(controller.state, setup, recovered) { walk, setupState, ready ->
            when {
                walk.endedStatus != null -> SafeWalkUiState.Ended(walk.endedStatus)
                walk.isActive -> SafeWalkUiState.Active(walk)
                !ready -> SafeWalkUiState.Loading
                else -> setupState
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SafeWalkUiState.Loading)

    private val _sessionExpired = MutableStateFlow(false)
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    private var locationPromptUsed = false
    private var notificationPromptUsed = false

    init {
        viewModelScope.launch {
            controller.recover()
            recovered.value = true
        }
        viewModelScope.launch {
            controller.state.collect { if (it.signedOut) _sessionExpired.value = true }
        }
    }

    fun onStartClicked(destinationLabel: String?, intervalMinutes: Int, movementChecks: Boolean = true) {
        if (setup.value.isStarting || controller.state.value.isActive) return
        setup.value = SafeWalkUiState.Setup(isStarting = true) // synchronous: a double tap is ignored
        viewModelScope.launch {
            when (val result = controller.start(destinationLabel, intervalMinutes, movementChecks)) {
                is ApiResult.Success -> setup.value = SafeWalkUiState.Setup()
                is ApiResult.Failure -> {
                    if (result.error == ApiError.UNAUTHORIZED) _sessionExpired.value = true
                    setup.value = SafeWalkUiState.Setup(failure = result)
                }
            }
        }
    }

    fun onCheckInClicked() = controller.checkIn()

    fun onArrivedConfirmed() = controller.complete()

    fun onCancelConfirmed() = controller.cancel()

    fun onDoneClicked() = controller.acknowledgeEnded()

    /** The person switched stop / route checks on or off during the walk (e.g. the wrong place was found). */
    fun onMovementChecksToggled() = controller.setMovementChecks(!controller.state.value.movementChecks)

    /** For the status lines: whether the GPS is good enough for the movement checks right now. */
    fun gpsQuality(state: SafeWalkState, now: Instant): GpsQuality = movementPolicy.gpsQuality(state.movement, now)

    /** Back from a permission dialog or Settings. */
    fun onScreenVisible() = controller.refreshLocationAccess()

    fun onSessionExpiredHandled() {
        _sessionExpired.value = false
    }

    /** True once: location is asked when the person starts a walk (the moment of need). */
    fun consumeLocationPrompt(): Boolean = (!locationPromptUsed).also { locationPromptUsed = true }

    /** True once: notifications are asked before the walk starts, because check-in alerts depend on them. */
    fun consumeNotificationPrompt(): Boolean = (!notificationPromptUsed).also { notificationPromptUsed = true }

    companion object {
        val INTERVAL_OPTIONS_MINUTES = listOf(2, 5, 10, 15, 30)
        const val DEFAULT_INTERVAL_MINUTES = 10

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                SafeWalkViewModel(container.safeWalk, container.trackingServiceRunning, circleAlerts = container.circleAlerts)
            }
        }
    }
}
