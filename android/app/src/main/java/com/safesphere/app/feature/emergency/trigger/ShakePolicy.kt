package com.safesphere.app.feature.emergency.trigger

import kotlin.math.sqrt

/**
 * Phase 17F (D-4): "three strong shakes within two seconds" as a PURE policy (no Android types, unit-tested).
 *
 * A shake is a firm back-and-forth movement. Each accelerometer sample goes in; the policy removes gravity with a slow
 * filter, counts a PEAK when what is left is strong, and counts it as a shake only when it pushes the opposite way of
 * the previous peak. That reversal is what separates a shake from walking or running (their impacts all push the same
 * way) and from a single knock or a drop (one peak only).
 *
 * It only DETECTS. What a shake does is decided elsewhere: it opens the normal cancellable 5-second countdown, it never
 * starts an SOS by itself, and it only runs when the person switched it on.
 */
class ShakePolicy(
    /** How strong the movement left after removing gravity must be (m/s²) for a peak. 1 g is about 9.8. */
    private val peakThreshold: Float = 17f,
    /**
     * At least one sample inside the window must reach this (m/s²) as well. Found on the Galaxy A23 (real-device sprint,
     * Track C, 2026-11-06): jogging with the phone in a pocket makes a steady 17-29 m/s² stride that the gravity filter
     * turns into "alternating" peaks, which opened a countdown; every deliberate shake had a jerk of 35 or more (then
     * weaker follow-ups). So the count stays at [peakThreshold], but a stride alone can no longer be a shake.
     */
    private val strongPeakThreshold: Float = 32f,
    /**
     * The first of the counted peaks must be preceded by this much quiet (no other counted peak). A shake is a burst that
     * starts from rest; a run or a jog is a train of impacts about 270 ms apart that never goes quiet (A23 trace, same
     * session: the hard jerks of a run reached 36-58 m/s², as strong as a light shake, so strength alone cannot tell them
     * apart, but no run left 800 ms of quiet before a burst and no deliberate shake had a counted peak just before it).
     */
    private val leadInQuietMillis: Long = 800L,
    private val shakesRequired: Int = 3,
    private val windowMillis: Long = 2_000L,
    /** Peaks closer together than this are one movement (sensor ringing). */
    private val minGapMillis: Long = 90L,
    /** After a trigger, nothing counts for this long (one shake gesture must not fire twice). */
    private val cooldownMillis: Long = 10_000L,
    /** Gravity filter weight (higher = slower). */
    private val gravityAlpha: Float = 0.9f
) {

    /** What the policy remembers between samples. Memory only; nothing is stored. */
    data class State(
        val gravityX: Float = 0f,
        val gravityY: Float = 0f,
        val gravityZ: Float = 0f,
        val primed: Boolean = false,
        /** Times of the counted, alternating peaks inside the window (oldest first). */
        val peaks: List<Long> = emptyList(),
        val lastPeakAt: Long? = null,
        val lastDirX: Float = 0f,
        val lastDirY: Float = 0f,
        val lastDirZ: Float = 0f,
        val quietUntil: Long = Long.MIN_VALUE,
        /** When a sample last reached [strongPeakThreshold] (any sample, also one inside the ringing gap). */
        val lastStrongAt: Long? = null,
        /** Times of every counted peak of the last few seconds (the lead-in check looks before the burst). */
        val recentPeaks: List<Long> = emptyList()
    )

    data class Result(val state: State, val triggered: Boolean)

    companion object {
        /**
         * A sample older than this when it reaches the policy is dropped. A phone whose accelerometer does not wake the CPU
         * (the Galaxy A23's does not, checked with dumpsys sensorservice) keeps its samples in a FIFO while the screen is off
         * and hands them over in a batch when something wakes the CPU: a shake from minutes ago must never open a countdown now.
         */
        const val MAX_SAMPLE_AGE_MILLIS = 1_500L

        fun isFresh(sampleMillis: Long, nowMillis: Long, maxAgeMillis: Long = MAX_SAMPLE_AGE_MILLIS): Boolean =
            nowMillis - sampleMillis in -1_000L..maxAgeMillis
    }

    /** One accelerometer sample ([x], [y], [z] in m/s², gravity included) taken at [atMillis] (a monotonic clock). */
    fun onSample(state: State, atMillis: Long, x: Float, y: Float, z: Float): Result {
        // The first sample seeds the gravity estimate, so a phone lying still never looks like a shake.
        if (!state.primed) return Result(state.copy(primed = true, gravityX = x, gravityY = y, gravityZ = z), false)
        val gx = gravityAlpha * state.gravityX + (1 - gravityAlpha) * x
        val gy = gravityAlpha * state.gravityY + (1 - gravityAlpha) * y
        val gz = gravityAlpha * state.gravityZ + (1 - gravityAlpha) * z
        val lx = x - gx
        val ly = y - gy
        val lz = z - gz
        val withGravity = state.copy(gravityX = gx, gravityY = gy, gravityZ = gz)
        if (atMillis < state.quietUntil) return Result(withGravity, false)

        val strength = sqrt(lx * lx + ly * ly + lz * lz)
        val withStrong = if (strength >= strongPeakThreshold) withGravity.copy(lastStrongAt = atMillis) else withGravity
        val last = state.lastPeakAt
        if (strength < peakThreshold || (last != null && atMillis - last < minGapMillis)) {
            return Result(withStrong.copy(peaks = state.peaks.filter { atMillis - it <= windowMillis }), false)
        }

        val inWindow = state.peaks.filter { atMillis - it <= windowMillis }
        val reverses = last == null || inWindow.isEmpty() ||
            (lx * state.lastDirX + ly * state.lastDirY + lz * state.lastDirZ) < 0f
        // A peak that pushes the same way as the previous one is not a back-and-forth shake: start counting again from it.
        val peaks = if (reverses) inWindow + atMillis else listOf(atMillis)
        val recent = state.recentPeaks.filter { atMillis - it <= windowMillis + leadInQuietMillis } + atMillis
        val next = withStrong.copy(
            peaks = peaks, lastPeakAt = atMillis, lastDirX = lx, lastDirY = ly, lastDirZ = lz, recentPeaks = recent
        )
        val strongInWindow = next.lastStrongAt?.let { atMillis - it <= windowMillis } == true
        val firstPeak = peaks.first()
        val startedFromRest = recent.none { it < firstPeak && firstPeak - it <= leadInQuietMillis }
        if (peaks.size >= shakesRequired && strongInWindow && startedFromRest) {
            return Result(
                next.copy(
                    peaks = emptyList(), lastPeakAt = null, lastStrongAt = null, recentPeaks = emptyList(),
                    quietUntil = atMillis + cooldownMillis
                ),
                true
            )
        }
        return Result(next, false)
    }
}
