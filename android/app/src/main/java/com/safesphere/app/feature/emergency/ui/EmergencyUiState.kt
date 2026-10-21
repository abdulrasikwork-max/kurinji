package com.safesphere.app.feature.emergency.ui

import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.emergency.domain.EmergencyEvent
import com.safesphere.app.feature.safetycircle.domain.SafetyContact

/**
 * The emergency state model shown on the SOS screen.
 *
 *   Idle --tap SOS--> Countdown(5..1) --reaches 0--> Starting --recorded--> Active
 *   Countdown --CANCEL--> Idle
 *   Active --"I'm safe"--> Ended(RESOLVED)      Active --"False alarm"--> Ended(CANCELLED)
 *
 * The countdown exists only on the phone. A server record (the emergency event) is created only when
 * it reaches zero, so cancelling during the countdown records nothing.
 */
sealed interface EmergencyUiState {

    /** Checking whether an emergency is already active (for example after the app was closed). */
    data object Loading : EmergencyUiState

    data object Idle : EmergencyUiState

    data class Countdown(val secondsRemaining: Int) : EmergencyUiState

    /** Countdown finished; the emergency is being recorded on the server. */
    data object Starting : EmergencyUiState

    /**
     * [circle] is the person's enabled contacts, shown on screen only. Nobody has been told anything.
     * [restoredOffline] is true when the server could not be reached and the emergency was restored from
     * the record kept on the phone (it may already have been ended on another device).
     */
    data class Active(
        val event: EmergencyEvent,
        val circle: List<SafetyContact>,
        val isEnding: Boolean = false,
        val failure: ApiResult.Failure? = null,
        val restoredOffline: Boolean = false
    ) : EmergencyUiState

    /** The emergency finished. event.status says whether it was RESOLVED or CANCELLED. */
    data class Ended(val event: EmergencyEvent) : EmergencyUiState

    /** The emergency could not be recorded (offline, server problem...). */
    data class StartFailed(val failure: ApiResult.Failure) : EmergencyUiState
}
