package com.safesphere.app.feature.safewalk.domain

import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.core.location.LocationFix
import com.safesphere.app.core.location.RouteGeometry
import java.time.Duration
import java.time.Instant
import kotlin.math.max

/** Something the phone noticed that is worth asking "Are you OK?" about (escalation level 1, Phase 4C). */
enum class ConcernKind {
    /** A sustained departure from the Safety Bubble around the direct route. Same name as the server's reason. */
    ROUTE_DEVIATION,

    /** No movement for a long time, away from the destination. Same name as the server's reason. */
    UNEXPECTED_STOP
}

/** An open "Are you OK?" prompt raised by [MovementPolicy]. [raisedAt] is PHONE time. */
data class Concern(val kind: ConcernKind, val raisedAt: Instant)

/** Safety Bubble detector. [reported] = already asked about; re-armed only after coming back inside. */
data class BubbleState(
    val outsideSince: Instant? = null,
    val outsideFixes: Int = 0,
    val insideFixes: Int = 0,
    val offRoute: Boolean = false,
    val reported: Boolean = false
)

/** Unexpected-stop detector: the place the person has stayed close to, and since when. */
data class StopState(
    val anchor: GeoPoint? = null,
    val anchorAccuracyMeters: Double = 0.0,
    val anchorSince: Instant? = null,
    val lastFixAt: Instant? = null,
    val stopped: Boolean = false,
    val reported: Boolean = false
)

/** "Looks like you have arrived": a hint only, the walk is never ended automatically. */
data class ArrivalState(val nearFixes: Int = 0, val arrived: Boolean = false)

enum class GpsQuality {
    /** No fix yet. */
    WAITING,

    /** Fixes are too rough or too old to judge movement: the checks pause instead of guessing. */
    WEAK,

    GOOD
}

/**
 * Everything the movement checks know. Memory only: never written to the phone's storage (no track on disk).
 * [routeStart] is where the walk was first seen with a usable fix (after a restart: where it resumed).
 */
data class MovementState(
    val routeStart: GeoPoint? = null,
    val bubble: BubbleState = BubbleState(),
    val stop: StopState = StopState(),
    val arrival: ArrivalState = ArrivalState(),
    val lastFixAt: Instant? = null,
    val lastFixUsable: Boolean = false,
    val lastUsableFixAt: Instant? = null,
    val metersToDestination: Double? = null
) {
    /** A condition that has not been asked about yet. Stopping at the destination is not a concern. */
    val newConcern: ConcernKind?
        get() = when {
            bubble.offRoute && !bubble.reported -> ConcernKind.ROUTE_DEVIATION
            stop.stopped && !stop.reported && !arrival.arrived -> ConcernKind.UNEXPECTED_STOP
            else -> null
        }

    override fun toString(): String =
        "MovementState(offRoute=${bubble.offRoute}, stopped=${stop.stopped}, arrived=${arrival.arrived}, location=<redacted>)"
}

/**
 * Phase 4C movement checks: Safety Bubble (route deviation), unexpected stop and arrival hint.
 * Pure Kotlin and side-effect free: `onFix(state, fix)` returns the next state, so every rule is unit-tested
 * with plain values and it works offline. It only ever WARNS: the session turns a [MovementState.newConcern]
 * into an "Are you OK?" prompt (level 1); level 2 still needs no answer; nothing here reaches level 3.
 *
 * False-alarm safeguards (all deliberate):
 * - fixes worse than [maxUsableAccuracyMeters] (or without accuracy) are ignored, and the checks report WEAK GPS;
 * - "outside" means even the most favourable point of the fix's accuracy circle is outside the bubble;
 * - a departure must be SUSTAINED ([sustainedFixes] fixes and [sustainedDuration]);
 * - the bubble is generous: a corridor around the straight start -> destination line, half-width
 *   [corridorWidthRatio] of the route length, at least [minCorridorHalfWidthMeters] (streets are not straight;
 *   real route geometry is Phase 8);
 * - after "I'm OK", the same condition is not asked about again until it has cleared (back inside / moved on).
 */
class MovementPolicy(
    val maxUsableAccuracyMeters: Double = 100.0,
    val minCorridorHalfWidthMeters: Double = 250.0,
    val corridorWidthRatio: Double = 0.4,
    val maxCorridorHalfWidthMeters: Double = 2_000.0,
    val sustainedFixes: Int = 3,
    val sustainedDuration: Duration = Duration.ofSeconds(90),
    val rearmInsideFixes: Int = 2,
    val minStopRadiusMeters: Double = 60.0,
    val maxStopRadiusMeters: Double = 200.0,
    val stopLimit: Duration = Duration.ofMinutes(10),
    val maxFixGap: Duration = Duration.ofMinutes(3),
    val arrivalRadiusMeters: Double = 80.0,
    val arrivalFixes: Int = 2,
    val leaveArrivalMeters: Double = 200.0,
    val weakGpsAfter: Duration = Duration.ofMinutes(2)
) {

    fun onFix(state: MovementState, fix: LocationFix, destination: GeoPoint?): MovementState {
        val accuracy = fix.accuracyMeters?.toDouble()
        val usable = accuracy != null && !accuracy.isNaN() && accuracy >= 0 && accuracy <= maxUsableAccuracyMeters
        val withFix = state.copy(lastFixAt = fix.recordedAt, lastFixUsable = usable)
        if (!usable) return withFix

        val point = GeoPoint.of(fix)
        val start = state.routeStart ?: point
        var next = withFix.copy(
            routeStart = start,
            lastUsableFixAt = fix.recordedAt,
            stop = nextStop(state.stop, point, accuracy!!, fix.recordedAt)
        )
        if (destination != null) {
            val toDestination = RouteGeometry.distanceMeters(point, destination)
            next = next.copy(
                metersToDestination = toDestination,
                arrival = nextArrival(state.arrival, toDestination, accuracy),
                bubble = nextBubble(
                    state.bubble,
                    distanceFromRoute = RouteGeometry.distanceToSegmentMeters(point, start, destination),
                    halfWidth = corridorHalfWidthMeters(start, destination),
                    accuracy = accuracy,
                    at = fix.recordedAt
                )
            )
        }
        return next
    }

    /** "I'm OK": whatever is true right now counts as answered (no repeat prompt until it clears). */
    fun acknowledge(state: MovementState): MovementState = state.copy(
        bubble = if (state.bubble.offRoute) state.bubble.copy(reported = true) else state.bubble,
        stop = if (state.stop.stopped) state.stop.copy(reported = true) else state.stop
    )

    /** The session asked (or the person was already being asked) about [kind]. */
    fun markReported(state: MovementState, kind: ConcernKind): MovementState = when (kind) {
        ConcernKind.ROUTE_DEVIATION -> state.copy(bubble = state.bubble.copy(reported = true))
        ConcernKind.UNEXPECTED_STOP -> state.copy(stop = state.stop.copy(reported = true))
    }

    fun gpsQuality(state: MovementState, now: Instant): GpsQuality {
        if (state.lastFixAt == null) return GpsQuality.WAITING
        val lastUsable = state.lastUsableFixAt ?: return GpsQuality.WEAK
        if (!state.lastFixUsable) return GpsQuality.WEAK
        return if (Duration.between(lastUsable, now) > weakGpsAfter) GpsQuality.WEAK else GpsQuality.GOOD
    }

    fun corridorHalfWidthMeters(start: GeoPoint, destination: GeoPoint): Double {
        val length = RouteGeometry.distanceMeters(start, destination)
        return (length * corridorWidthRatio).coerceIn(minCorridorHalfWidthMeters, maxCorridorHalfWidthMeters)
    }

    private fun nextBubble(prev: BubbleState, distanceFromRoute: Double, halfWidth: Double, accuracy: Double, at: Instant): BubbleState {
        val clearlyOutside = distanceFromRoute - accuracy > halfWidth
        val inside = distanceFromRoute <= halfWidth
        return when {
            clearlyOutside -> {
                val since = prev.outsideSince ?: at
                val count = prev.outsideFixes + 1
                val sustained = count >= sustainedFixes && Duration.between(since, at) >= sustainedDuration
                prev.copy(outsideSince = since, outsideFixes = count, insideFixes = 0, offRoute = prev.offRoute || sustained)
            }
            inside -> when {
                !prev.offRoute && !prev.reported -> BubbleState() // a short excursion is forgotten
                prev.insideFixes + 1 >= rearmInsideFixes -> BubbleState() // clearly back: re-armed
                else -> prev.copy(insideFixes = prev.insideFixes + 1)
            }
            else -> prev // on the edge (within the fix's uncertainty): not enough evidence either way
        }
    }

    private fun nextStop(prev: StopState, point: GeoPoint, accuracy: Double, at: Instant): StopState {
        val anchor = prev.anchor
        val lastFixAt = prev.lastFixAt
        val anchorSince = prev.anchorSince
        if (anchor == null || lastFixAt == null || anchorSince == null || at.isBefore(lastFixAt)) {
            return StopState(anchor = point, anchorAccuracyMeters = accuracy, anchorSince = at, lastFixAt = at)
        }
        val radius = (prev.anchorAccuracyMeters + accuracy).coerceIn(minStopRadiusMeters, maxStopRadiusMeters)
        if (RouteGeometry.distanceMeters(anchor, point) > radius) {
            return StopState(anchor = point, anchorAccuracyMeters = accuracy, anchorSince = at, lastFixAt = at) // moved: re-armed
        }
        if (Duration.between(lastFixAt, at) > maxFixGap) {
            // No fixes for a while: nobody knows what happened in between, so the stop timer starts again.
            return prev.copy(anchorSince = at, lastFixAt = at, stopped = false)
        }
        return prev.copy(lastFixAt = at, stopped = Duration.between(anchorSince, at) >= stopLimit)
    }

    private fun nextArrival(prev: ArrivalState, toDestination: Double, accuracy: Double): ArrivalState = when {
        toDestination <= max(arrivalRadiusMeters, accuracy) -> {
            val near = prev.nearFixes + 1
            ArrivalState(near, prev.arrived || near >= arrivalFixes)
        }
        toDestination > leaveArrivalMeters -> ArrivalState()
        else -> prev
    }
}
