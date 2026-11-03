package com.safesphere.app.feature.evidence.domain

import com.safesphere.app.feature.emergency.domain.AutoAudioChoice

/**
 * Phase 17F: when an SOS may start recording audio by itself. Pure and unit-tested.
 *
 * Audio starts automatically ONLY when all of this holds, so the old rule (a visible, person-controlled recording) is
 * kept in spirit: the person agreed beforehand ([AutoAudioChoice.ON], asked once in You → Safety, off by default);
 * the microphone permission is already granted (it is asked at the moment of that agreement, never during an SOS); the
 * SOS screen is in front (the caller only asks from there; Android 14 refuses a microphone service from the
 * background); the server has recorded the SOS (the existing evidence rule); nothing is recording; and it has not
 * already been started once for this SOS - so a "Stop" tap is final, also after leaving and returning or after a restart.
 */
object AutoAudioPolicy {

    enum class Decision {
        START,
        OFF,
        WAITING_FOR_SERVER,
        ALREADY_STARTED_FOR_THIS_SOS,
        ALREADY_RECORDING,
        NEEDS_MICROPHONE
    }

    fun decide(
        choice: AutoAudioChoice,
        emergencyId: Long?,
        lastRecordedEmergency: Long?,
        isRecording: Boolean,
        microphoneGranted: Boolean
    ): Decision = when {
        choice != AutoAudioChoice.ON -> Decision.OFF
        emergencyId == null -> Decision.WAITING_FOR_SERVER
        isRecording -> Decision.ALREADY_RECORDING
        lastRecordedEmergency == emergencyId -> Decision.ALREADY_STARTED_FOR_THIS_SOS
        !microphoneGranted -> Decision.NEEDS_MICROPHONE
        else -> Decision.START
    }
}
