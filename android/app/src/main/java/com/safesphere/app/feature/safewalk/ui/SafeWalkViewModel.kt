package com.safesphere.app.feature.safewalk.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.safewalk.domain.CheckInPolicy
import com.safesphere.app.feature.safewalk.domain.JourneyStatus
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
    /** Checking whether a walk is already running (after a restart). */
    data object Loading : SafeWalkUiState

    data class Setup(val isStarting: Boolean = false, val failure: ApiResult.Failure? = null) : SafeWalkUiState

    data class Active(val walk: SafeWalkState) : SafeWalkUiState

    data class Ended(val status: JourneyStatus) : SafeWalkUiState
}

/** "On my way" (N6, D22). All walk logic lives in the shared [SafeWalkController]; this only maps it to the screen. */
class SafeWalkViewModel(private val controller: SafeWalkController) : ViewModel() {

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

    /** The label of the walk that just ended ("I'm there" note); memory only. */
    var lastLabel: String? = null
        private set

    init {
        viewModelScope.launch {
            controller.recover()
            recovered.value = true
        }
        viewModelScope.launch {
            controller.state.collect { walk ->
                if (walk.signedOut) _sessionExpired.value = true
                if (walk.isActive) lastLabel = walk.journey?.destinationLabel
            }
        }
    }

    fun onStartClicked(label: String?, minutes: Int) {
        if (setup.value.isStarting || controller.state.value.isActive) return
        if (minutes !in CheckInPolicy.CHOICES) return
        setup.value = SafeWalkUiState.Setup(isStarting = true) // synchronous: a double tap is ignored
        viewModelScope.launch {
            when (val result = controller.start(label, minutes)) {
                is ApiResult.Success -> setup.value = SafeWalkUiState.Setup()
                is ApiResult.Failure -> {
                    if (result.error == ApiError.UNAUTHORIZED) _sessionExpired.value = true
                    setup.value = SafeWalkUiState.Setup(failure = result)
                }
            }
        }
    }

    fun onMoreTimeClicked() = controller.checkIn()

    fun onThereClicked() = controller.complete()

    fun onCancelConfirmed() = controller.cancel()

    fun onDoneClicked() = controller.acknowledgeEnded()

    fun onSessionExpiredHandled() {
        _sessionExpired.value = false
    }

    companion object {
        const val DEFAULT_MINUTES = 20

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SafeWalkViewModel(container.safeWalk) }
        }
    }
}
