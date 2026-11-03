package com.safesphere.app.feature.emergency.trigger

import com.safesphere.app.feature.emergency.domain.AutoAudioChoice
import com.safesphere.app.feature.evidence.domain.AutoAudioPolicy
import com.safesphere.app.feature.evidence.domain.AutoAudioPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** Phase 17F: audio starts by itself only after the person agreed, and never twice for one SOS. */
class AutoAudioPolicyTest {

    private fun decide(
        choice: AutoAudioChoice = AutoAudioChoice.ON,
        emergencyId: Long? = 10,
        last: Long? = null,
        recording: Boolean = false,
        mic: Boolean = true
    ) = AutoAudioPolicy.decide(choice, emergencyId, last, recording, mic)

    @Test
    fun `agreed, microphone allowed, server has the SOS - it starts`() = assertEquals(Decision.START, decide())

    @Test
    fun `not asked and refused both mean off`() {
        assertEquals(Decision.OFF, decide(choice = AutoAudioChoice.NOT_ASKED))
        assertEquals(Decision.OFF, decide(choice = AutoAudioChoice.OFF))
    }

    @Test
    fun `waits until the server has recorded the SOS`() = assertEquals(Decision.WAITING_FOR_SERVER, decide(emergencyId = null))

    @Test
    fun `a Stop tap is final - it is not started again for the same SOS`() {
        assertEquals(Decision.ALREADY_STARTED_FOR_THIS_SOS, decide(last = 10))
        // ... but a later SOS is a new one.
        assertEquals(Decision.START, decide(emergencyId = 11, last = 10))
    }

    @Test
    fun `never while something is already recording`() = assertEquals(Decision.ALREADY_RECORDING, decide(recording = true))

    @Test
    fun `without the microphone permission it does not start and never asks`() = assertEquals(Decision.NEEDS_MICROPHONE, decide(mic = false))
}
