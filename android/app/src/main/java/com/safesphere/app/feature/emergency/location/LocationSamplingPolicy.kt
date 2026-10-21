package com.safesphere.app.feature.emergency.location

import com.safesphere.app.core.location.GeoDistance
import com.safesphere.app.core.location.LocationFix
import java.time.Duration
import java.time.Instant
import kotlin.math.max

/**
 * Decides which fixes are worth saving. Pure Kotlin, unit-tested.
 *
 * The phone receives a fix about every 10 s, but saving all of them would waste battery, data and the
 * server's per-emergency limit (1000 points). A fix is saved when it is the first one, when the person has
 * moved further than the fix's own uncertainty (at least [minDistanceMeters]), or when [maxSilence] has
 * passed. Cached fixes from long before the emergency began are ignored: the server would reject them.
 */
class LocationSamplingPolicy(
    private val minDistanceMeters: Double = 15.0,
    private val maxSilence: Duration = Duration.ofSeconds(60),
    private val clockTolerance: Duration = Duration.ofMinutes(5)
) {

    fun shouldRecord(previous: LocationFix?, candidate: LocationFix, emergencyStartedAt: Instant): Boolean {
        if (candidate.recordedAt.isBefore(emergencyStartedAt.minus(clockTolerance))) return false
        if (previous == null) return true
        if (!candidate.recordedAt.isAfter(previous.recordedAt)) return false
        if (Duration.between(previous.recordedAt, candidate.recordedAt) >= maxSilence) return true
        val threshold = max(minDistanceMeters, (candidate.accuracyMeters ?: 0f).toDouble())
        return GeoDistance.meters(previous, candidate) >= threshold
    }
}
