package com.safesphere.app.feature.emergency.domain

import kotlinx.coroutines.flow.Flow

/**
 * Phase 17F: the person's own choice about audio during an SOS. Asked ONCE (first visit to You → Safety); "not asked"
 * is the default and records nothing. [ON] still needs the microphone permission and an SOS the server has recorded.
 */
enum class AutoAudioChoice { NOT_ASKED, ON, OFF }

/** What this person chose for SOS (per account, on this phone only; never sent to the server). Defaults: all off. */
data class SosOptions(
    /** Shake gesture (D-4). Off until the person switches it on. */
    val shakeOn: Boolean = false,
    val autoAudio: AutoAudioChoice = AutoAudioChoice.NOT_ASKED
)

/** Per-account SOS options on the phone. A different account on the same phone never sees these. */
interface SosOptionsStore {
    fun options(userId: Long): Flow<SosOptions>
    suspend fun setShake(userId: Long, on: Boolean)
    suspend fun setAutoAudio(userId: Long, choice: AutoAudioChoice)

    /** The emergency (server id) for which a recording was last started on this phone, by the person or automatically. */
    suspend fun lastRecordedEmergency(userId: Long): Long?

    suspend fun markRecordingStarted(userId: Long, emergencyId: Long)

    /** The account was deleted: forget its choices. A normal sign-out keeps them (they are preferences, not data). */
    suspend fun forget(userId: Long)
}
