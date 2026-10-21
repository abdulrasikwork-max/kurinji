package com.safesphere.app.feature.safewalk

import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.core.location.LocationFix
import com.safesphere.app.core.location.LocationSource
import com.safesphere.app.core.location.RouteGeometry
import com.safesphere.app.feature.safewalk.domain.ConcernKind
import com.safesphere.app.feature.safewalk.domain.GpsQuality
import com.safesphere.app.feature.safewalk.domain.MovementPolicy
import com.safesphere.app.feature.safewalk.domain.MovementState
import java.time.Duration
import java.time.Instant
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4C movement checks (pure): Safety Bubble / route deviation, unexpected stop, arrival hint, GPS quality.
 * Positions are built in metres north/east of a start point, so every number in a test is readable.
 */
class MovementPolicyTest {

    private val policy = MovementPolicy()
    private val t0 = Instant.parse("2026-10-20T18:00:00Z")
    private val start = GeoPoint(12.9716, 77.5946)
    private val destination = at(north = 1_000.0) // 1 km walk: bubble half-width 400 m

    private fun at(north: Double = 0.0, east: Double = 0.0): GeoPoint = GeoPoint(
        start.latitude + north / METERS_PER_DEGREE,
        start.longitude + east / (METERS_PER_DEGREE * cos(Math.toRadians(start.latitude)))
    )

    private fun fix(point: GeoPoint, seconds: Long, accuracy: Float? = 10f) =
        LocationFix(point.latitude, point.longitude, accuracy, LocationSource.FUSED, t0.plusSeconds(seconds))

    /** Feeds fixes in order, starting from [state]. */
    private fun feed(fixes: List<LocationFix>, state: MovementState = MovementState(), to: GeoPoint? = destination): MovementState =
        fixes.fold(state) { s, f -> policy.onFix(s, f, to) }

    private fun walkStart() = feed(listOf(fix(start, 0)))

    // ---- geometry -------------------------------------------------------------------------------

    @Test
    fun `distance to the route segment - beside it, beyond its end, and a zero-length route`() {
        assertEquals(500.0, RouteGeometry.distanceToSegmentMeters(at(north = 500.0, east = 500.0), start, destination), 2.0)
        assertEquals(300.0, RouteGeometry.distanceToSegmentMeters(at(north = 1_300.0), start, destination), 2.0)
        assertEquals(200.0, RouteGeometry.distanceToSegmentMeters(at(north = -200.0), start, destination), 2.0)
        assertEquals(100.0, RouteGeometry.distanceToSegmentMeters(at(east = 100.0), start, start), 1.0)
    }

    @Test
    fun `the bubble is generous - 40 percent of the route, at least 250 m, at most 2 km`() {
        assertEquals(400.0, policy.corridorHalfWidthMeters(start, destination), 2.0)
        assertEquals(250.0, policy.corridorHalfWidthMeters(start, at(north = 300.0)), 0.1)
        assertEquals(2_000.0, policy.corridorHalfWidthMeters(start, at(north = 10_000.0)), 0.1)
    }

    // ---- Safety Bubble / route deviation ---------------------------------------------------------

    @Test
    fun `route deviation - a sustained departure from the bubble raises a concern`() {
        val offRoute = at(north = 500.0, east = 700.0)
        val outside = feed(listOf(fix(offRoute, 30), fix(offRoute, 60), fix(offRoute, 90)), walkStart())
        assertFalse("three fixes in 60 s are not yet sustained", outside.bubble.offRoute)

        val sustained = policy.onFix(outside, fix(offRoute, 120), destination)
        assertTrue(sustained.bubble.offRoute)
        assertEquals(ConcernKind.ROUTE_DEVIATION, sustained.newConcern)
    }

    @Test
    fun `a short excursion outside the bubble is forgotten`() {
        val offRoute = at(north = 500.0, east = 700.0)
        val state = feed(listOf(fix(offRoute, 30), fix(offRoute, 60), fix(at(north = 600.0), 90), fix(offRoute, 120)), walkStart())

        assertFalse(state.bubble.offRoute)
        assertEquals(1, state.bubble.outsideFixes)
        assertNull(state.newConcern)
    }

    @Test
    fun `inaccurate fixes never count as leaving the route, and GPS is reported weak`() {
        val farAway = at(north = 500.0, east = 3_000.0)
        val state = feed((1..10L).map { fix(farAway, it * 30, accuracy = 150f) }, walkStart())

        assertFalse(state.bubble.offRoute)
        assertNull(state.newConcern)
        assertEquals(GpsQuality.WEAK, policy.gpsQuality(state, t0.plusSeconds(300)))
    }

    @Test
    fun `a fix whose accuracy circle still touches the bubble is not evidence of leaving it`() {
        val edge = at(north = 500.0, east = 450.0) // 450 m from the line, 80 m accuracy: may be inside
        val state = feed((1..6L).map { fix(edge, it * 30, accuracy = 80f) }, walkStart())

        assertFalse(state.bubble.offRoute)
        assertEquals(0, state.bubble.outsideFixes)
    }

    @Test
    fun `after the person is asked, the same departure is not asked about again until they are back inside`() {
        val offRoute = at(north = 500.0, east = 700.0)
        var state = feed((1..4L).map { fix(offRoute, it * 30) }, walkStart())
        state = policy.markReported(state, ConcernKind.ROUTE_DEVIATION)

        state = feed((5..20L).map { fix(offRoute, it * 30) }, state)
        assertNull("still outside, already asked", state.newConcern)

        state = feed(listOf(fix(at(north = 600.0), 630), fix(at(north = 650.0), 660)), state)
        assertFalse("two fixes back inside re-arm the bubble", state.bubble.reported)

        state = feed((23..26L).map { fix(offRoute, it * 30) }, state)
        assertEquals(ConcernKind.ROUTE_DEVIATION, state.newConcern)
    }

    @Test
    fun `I'm OK answers whatever is true right now`() {
        val offRoute = at(north = 500.0, east = 700.0)
        val state = policy.acknowledge(feed((1..4L).map { fix(offRoute, it * 30) }, walkStart()))

        assertTrue(state.bubble.reported)
        assertNull(state.newConcern)
    }

    @Test
    fun `without a destination there is no route check, only the stop check`() {
        val state = feed(listOf(fix(start, 0)) + (1..4L).map { fix(at(east = 5_000.0 + it * 100), it * 30) }, to = null)

        assertFalse(state.bubble.offRoute)
        assertNull(state.metersToDestination)
    }

    // ---- unexpected stop ----------------------------------------------------------------------

    @Test
    fun `unexpected stop - ten minutes in the same place raises a concern, nine and a half do not`() {
        val spot = at(north = 400.0)
        val jitter = listOf(0.0, 15.0, -10.0, 20.0)
        val fixes = (0..20L).map { i -> fix(at(north = 400.0 + jitter[(i % 4).toInt()]), i * 30) }

        val almost = feed(fixes.take(20), to = destination) // 0 .. 570 s
        assertFalse(almost.stop.stopped)
        val stopped = policy.onFix(almost, fixes[20], destination) // 600 s
        assertTrue(stopped.stop.stopped)
        assertEquals(ConcernKind.UNEXPECTED_STOP, stopped.newConcern)
        assertEquals(spot, stopped.stop.anchor)
    }

    @Test
    fun `moving on resets the stop timer and re-arms the check`() {
        var state = feed((0..20L).map { fix(at(north = 400.0), it * 30) })
        state = policy.markReported(state, ConcernKind.UNEXPECTED_STOP)

        state = policy.onFix(state, fix(at(north = 700.0), 630), destination)

        assertFalse(state.stop.stopped)
        assertFalse(state.stop.reported)
        assertEquals(t0.plusSeconds(630), state.stop.anchorSince)
    }

    @Test
    fun `a long gap without fixes restarts the stop timer instead of guessing`() {
        var state = feed((0..10L).map { fix(at(north = 400.0), it * 30) }) // 5 minutes still
        state = policy.onFix(state, fix(at(north = 400.0), 300 + 4 * 60), destination) // 4 minutes without fixes
        state = feed((1..9L).map { fix(at(north = 400.0), 540 + it * 30) }, state)

        assertFalse("only 4.5 minutes since the gap", state.stop.stopped)
    }

    // ---- arrival -------------------------------------------------------------------------------

    @Test
    fun `arrival - two fixes near the destination, stopping there is not a concern, leaving resets it`() {
        var state = feed(listOf(fix(start, 0), fix(at(north = 960.0), 600), fix(at(north = 990.0), 630)))
        assertTrue(state.arrival.arrived)
        assertEquals(10.0, state.metersToDestination!!, 2.0)

        state = feed((22..45L).map { fix(at(north = 990.0), it * 30) }, state) // 10+ minutes at the destination
        assertTrue(state.stop.stopped)
        assertNull("stopping at the destination is expected", state.newConcern)

        state = policy.onFix(state, fix(at(north = 1_300.0), 1_400), destination)
        assertFalse(state.arrival.arrived)
    }

    // ---- GPS quality -----------------------------------------------------------------------------

    @Test
    fun `GPS quality - waiting before the first fix, good while fresh, weak when old`() {
        assertEquals(GpsQuality.WAITING, policy.gpsQuality(MovementState(), t0))
        val state = walkStart()
        assertEquals(GpsQuality.GOOD, policy.gpsQuality(state, t0.plusSeconds(60)))
        assertEquals(GpsQuality.WEAK, policy.gpsQuality(state, t0.plus(Duration.ofMinutes(3))))
    }

    private companion object {
        const val METERS_PER_DEGREE = 6_371_000.0 * Math.PI / 180.0
    }
}
