package com.safesphere.app.feature.emergency.ui

import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.emergency.domain.ActiveEmergencyRecord
import com.safesphere.app.feature.emergency.domain.EmergencyEvent
import com.safesphere.app.feature.emergency.domain.EmergencyIds
import com.safesphere.app.feature.safetycircle.domain.SafetyContact

/** How the person wants to end the SOS: "I'm safe" (RESOLVE) or "False alarm" (CANCEL). */
enum class EndAction { RESOLVE, CANCEL }

/**
 * The emergency state model shown on the SOS screen.
 *
 *   Idle --tap SOS--> Countdown(5..1) --reaches 0--> Starting --started on the phone--> Active
 *   Countdown --CANCEL--> Idle
 *   Active --"I'm safe"--> Ended(RESOLVED)      Active --"False alarm"--> Ended(CANCELLED)
 *
 * The countdown exists only on the phone; cancelling it records nothing. Phase 11: at zero the SOS starts ON THE
 * PHONE at once (Safety Circle SMS and location need no internet) and is recorded on the server as soon as it can be
 * reached; until then its event carries a local id ([Active.notOnServerYet]).
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
        val restoredOffline: Boolean = false,
        /**
         * Phase 10A.4: this SOS was started on another phone, so ending it here needs the account password; the screen
         * asks for it and then calls [EmergencyViewModel.onEndWithPassword]. Null = nothing to ask.
         */
        val passwordNeededFor: EndAction? = null,
        /** Phase 11: the phone's reference for an SOS it started (matches the server's answer when it comes). */
        val clientRef: String? = null,
        /** Phase 11: the server refused to record this SOS (it keeps running on this phone). */
        val recordRefused: Boolean = false
    ) : EmergencyUiState {
        /** Phase 11: started on this phone; the server has not recorded it yet (no connection so far). */
        val notOnServerYet: Boolean get() = EmergencyIds.isLocal(event.id)
    }

    /**
     * The emergency finished. event.status says whether it was RESOLVED or CANCELLED. Phase 11: [unsent] = it ended
     * on this phone while the server could not be told; the phone tells it later (null once it has).
     */
    data class Ended(val event: EmergencyEvent, val unsent: ActiveEmergencyRecord? = null) : EmergencyUiState

    /** The emergency could not be recorded (offline, server problem...). */
    data class StartFailed(val failure: ApiResult.Failure) : EmergencyUiState
}
