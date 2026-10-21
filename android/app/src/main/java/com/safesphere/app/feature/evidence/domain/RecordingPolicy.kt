package com.safesphere.app.feature.evidence.domain

import java.time.Duration

/** Why an audio recording stopped (shown to the person; never silent). */
enum class RecordingStopReason {
    STOPPED_BY_USER,
    EMERGENCY_ENDED,
    SIGNED_OUT,
    MAX_DURATION,
    STORAGE_LOW,
    MICROPHONE_UNAVAILABLE,
    /** The phone could not write the audio file (storage error). */
    SAVE_FAILED
}

/**
 * Pure rules for audio evidence (Phase 5). The recording only exists while the person keeps it on, during an ACTIVE
 * emergency, and with visible signs (notification + Android's microphone indicator): there is no hidden or
 * always-on recording.
 *
 *  - Audio is cut into [SEGMENT] parts; each part is encrypted, hashed and queued for upload as soon as it closes, so
 *    a stolen or smashed phone loses at most the part being recorded.
 *  - After [MAX_DURATION] it stops (battery, storage, and consent: starting again is a conscious tap).
 *  - It stops before the phone's storage runs out ([MIN_FREE_BYTES]), so sealing and the rest of the app keep working.
 */
object RecordingPolicy {

    val SEGMENT: Duration = Duration.ofSeconds(30)
    val MAX_DURATION: Duration = Duration.ofMinutes(60)
    const val MIN_FREE_BYTES: Long = 100L * 1024 * 1024

    /** Speech-friendly AAC: about 6 KB per second, so a 30-second part is about 180 KB. */
    const val SAMPLE_RATE_HZ = 22_050
    const val BIT_RATE = 48_000

    /** Null = keep recording. */
    fun stopReason(
        elapsed: Duration,
        freeBytes: Long,
        emergencyActive: Boolean,
        signedIn: Boolean
    ): RecordingStopReason? = when {
        !signedIn -> RecordingStopReason.SIGNED_OUT
        !emergencyActive -> RecordingStopReason.EMERGENCY_ENDED
        freeBytes < MIN_FREE_BYTES -> RecordingStopReason.STORAGE_LOW
        elapsed >= MAX_DURATION -> RecordingStopReason.MAX_DURATION
        else -> null
    }

    /** May a NEW recording (or photo/video) start? Same rules, before anything is opened. */
    fun canStart(freeBytes: Long, emergencyActive: Boolean, signedIn: Boolean): RecordingStopReason? =
        stopReason(Duration.ZERO, freeBytes, emergencyActive, signedIn)

    /** The next part closes at the next multiple of [SEGMENT] since the start. */
    fun shouldRotate(segmentElapsed: Duration): Boolean = segmentElapsed >= SEGMENT
}
