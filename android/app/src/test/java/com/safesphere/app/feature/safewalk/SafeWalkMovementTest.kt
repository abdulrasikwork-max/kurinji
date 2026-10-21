package com.safesphere.app.feature.safewalk

import com.safesphere.app.R
import com.safesphere.app.core.device.BatteryLevel
import com.safesphere.app.core.escalation.EscalationLevel
import com.safesphere.app.core.location.DestinationLookup
import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.location.LocationFix
import com.safesphere.app.core.location.LocationSource
import com.safesphere.app.core.location.LocationUnavailableReason
import com.safesphere.app.core.location.LocationUpdate
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.safewalk.domain.CheckInPhase
import com.safesphere.app.feature.safewalk.domain.ConcernKind
import com.safesphere.app.feature.safewalk.session.DestinationSearch
import com.safesphere.app.feature.safewalk.session.SafeWalkSession
import com.safesphere.app.testing.FakeActiveJourneyStore
import com.safesphere.app.testing.FakeBatteryMonitor
import com.safesphere.app.testing.FakeConnectivityMonitor
import com.safesphere.app.testing.FakeDestinationResolver
import com.safesphere.app.testing.FakeJourneyRepository
import com.safesphere.app.testing.FakeLocationProvider
import com.safesphere.app.testing.FakeSafeWalkAlerts
import com.safesphere.app.testing.FakeSessionStorage
import java.time.Duration
import java.time.Instant
import kotlin.math.cos
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4C inside the Safe Walk holder: the movement checks open the "Are you OK?" prompt early (level 1 with a
 * reason), "I'm OK" answers it, weak GPS never causes an alarm, and the battery policy. Virtual time only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SafeWalkMovementTest {

    private val base = Instant.parse("2026-10-20T18:00:00Z")
    private val start = GeoPoint(12.9716, 77.5946)
    private val destination = at(north = 1_000.0)
    private val provider = FakeLocationProvider(access = LocationAccess.PRECISE)
    private val alerts = FakeSafeWalkAlerts()
    private val store = FakeActiveJourneyStore()
    private val battery = FakeBatteryMonitor(percent = 80)
    private val resolver = FakeDestinationResolver(DestinationLookup.Found(destination, "Station Road"))
    private val sessionStorage = FakeSessionStorage(AuthSession("token", AuthUser(USER_ID, "Asha", "asha@example.com")))
    private lateinit var server: FakeJourneyRepository

    private fun at(north: Double = 0.0, east: Double = 0.0): GeoPoint = GeoPoint(
        start.latitude + north / METERS_PER_DEGREE,
        start.longitude + east / (METERS_PER_DEGREE * cos(Math.toRadians(start.latitude)))
    )

    private fun TestScope.phoneNow(): Instant = base.plusMillis(testScheduler.currentTime)

    private fun TestScope.newSession(): SafeWalkSession {
        if (!::server.isInitialized) server = FakeJourneyRepository { phoneNow() }
        return SafeWalkSession(
            scope = backgroundScope,
            repository = server,
            store = store,
            locationProvider = provider,
            connectivityMonitor = FakeConnectivityMonitor(online = true),
            alerts = alerts,
            session = sessionStorage.session,
            currentUserId = { USER_ID },
            batteryMonitor = battery,
            destinationResolver = resolver,
            now = { phoneNow() }
        ).also { runCurrent() }
    }

    /** Starts a walk (no start fix arrives: the session waits briefly, then starts anyway) and sends a first fix. */
    private suspend fun TestScope.walking(label: String? = "Station", intervalMinutes: Int = 30, movementChecks: Boolean = true): SafeWalkSession {
        val session = newSession()
        assertTrue(session.start(label, intervalMinutes, movementChecks) is ApiResult.Success)
        runCurrent()
        emitAt(start)
        return session
    }

    private fun TestScope.emitAt(point: GeoPoint, accuracy: Float? = 10f) {
        provider.emitFix(LocationFix(point.latitude, point.longitude, accuracy, LocationSource.FUSED, phoneNow()))
        runCurrent()
    }

    /** One fix every 30 s along [points]. */
    private fun TestScope.walkThrough(points: List<GeoPoint>, accuracy: Float? = 10f) = points.forEach {
        advance(Duration.ofSeconds(30))
        emitAt(it, accuracy)
    }

    /** [count] fixes 30 s apart, moving north 100 m each, 700 m east of the direct route (outside the bubble). */
    private fun offRoute(count: Int, fromNorth: Double = 0.0) = (1..count).map { at(north = fromNorth + it * 100.0, east = 700.0) }

    private fun TestScope.advance(duration: Duration) {
        advanceTimeBy(duration.toMillis())
        runCurrent()
    }

    private fun escalations() = server.calls.filter { it.startsWith("escalate") }

    // ---- start ---------------------------------------------------------------------------------

    @Test
    fun `start - the destination is looked up near the person and sent to the server`() = runTest {
        val session = newSession()
        val started = async { session.start("Station", 30) }
        runCurrent() // the session is now waiting for one fix
        emitAt(start)

        assertTrue(started.await() is ApiResult.Success)
        assertEquals("Station", resolver.queries.single().first)
        assertEquals(start, resolver.queries.single().second)
        assertEquals(destination, server.journey?.destination)
        val state = session.state.value
        assertEquals(DestinationSearch.FOUND, state.destinationSearch)
        assertEquals("Station Road", state.destinationDescription)
        assertEquals(start, state.movement.routeStart)
    }

    @Test
    fun `destination not found - the walk runs without a route check, stops are still watched`() = runTest {
        resolver.result = DestinationLookup.NotFound
        val session = walking()

        assertTrue(session.state.value.isActive)
        assertEquals(DestinationSearch.NOT_FOUND, session.state.value.destinationSearch)
        assertNull(server.journey?.destination)
        walkThrough(List(20) { start })
        assertEquals(listOf("escalate:1:UNEXPECTED_STOP"), escalations())
    }

    // ---- Safety Bubble -------------------------------------------------------------------------

    @Test
    fun `route deviation - level 1 with its reason, then level 2 without an answer, never higher`() = runTest {
        val session = walking()

        walkThrough(offRoute(4))

        val state = session.state.value
        assertEquals(CheckInPhase.MISSED, state.phase)
        assertEquals(ConcernKind.ROUTE_DEVIATION, state.checkIn?.concern)
        assertEquals(ConcernKind.ROUTE_DEVIATION, alerts.showingConcern)
        assertEquals(R.string.safe_walk_notification_concern, session.currentNotice()?.text)
        assertEquals(listOf("escalate:1:ROUTE_DEVIATION"), escalations())
        assertEquals(ConcernKind.ROUTE_DEVIATION, store.record?.concern?.kind) // survives a restart

        advance(Duration.ofMinutes(2))
        assertEquals(CheckInPhase.NO_RESPONSE, session.state.value.phase)
        assertEquals(listOf("escalate:1:ROUTE_DEVIATION", "escalate:2"), escalations())

        advance(Duration.ofHours(1))
        assertEquals(EscalationLevel.NO_RESPONSE, server.journey?.escalationLevel)
        assertEquals(2, escalations().size)
    }

    @Test
    fun `I'm OK answers the prompt - the same detour is not asked about again until the person is back`() = runTest {
        val session = walking()
        walkThrough(offRoute(4))

        session.checkIn()
        runCurrent()
        assertEquals(CheckInPhase.ON_TRACK, session.state.value.phase)
        assertNull(session.state.value.concern)
        assertNull(alerts.showing)
        assertEquals(EscalationLevel.NORMAL, server.journey?.escalationLevel)

        walkThrough(offRoute(4, fromNorth = 400.0)) // still on the detour
        assertEquals(1, escalations().size)

        walkThrough(listOf(at(north = 900.0), at(north = 950.0, east = 50.0))) // back near the route
        walkThrough(offRoute(4, fromNorth = -600.0)) // a new departure
        assertEquals(listOf("escalate:1:ROUTE_DEVIATION", "escalate:1:ROUTE_DEVIATION"), escalations())
    }

    @Test
    fun `a departure while a missed check-in is already open does not add a second prompt`() = runTest {
        val session = walking(intervalMinutes = 2)
        advance(Duration.ofMinutes(2)) // check-in missed: level 1

        walkThrough(offRoute(4))

        assertNull(session.state.value.concern)
        assertEquals(listOf("escalate:1", "escalate:2"), escalations())
    }

    // ---- unexpected stop -----------------------------------------------------------------------

    @Test
    fun `unexpected stop - ten minutes in one place prompts, even without a destination`() = runTest {
        val session = walking(label = null)
        assertTrue(resolver.queries.isEmpty())

        walkThrough(List(19) { at(north = 5.0 * (it % 3)) }) // 9.5 minutes
        assertEquals(CheckInPhase.ON_TRACK, session.state.value.phase)
        walkThrough(listOf(start))

        assertEquals(ConcernKind.UNEXPECTED_STOP, session.state.value.checkIn?.concern)
        assertEquals(listOf("escalate:1:UNEXPECTED_STOP"), escalations())
    }

    @Test
    fun `location unavailable or weak GPS never raises a concern`() = runTest {
        val session = walking()

        walkThrough(List(30) { at(north = 500.0, east = 3_000.0) }, accuracy = 250f) // 15 minutes of rough fixes
        provider.emit(LocationUpdate.Unavailable(LocationUnavailableReason.NO_FIX))
        advance(Duration.ofMinutes(5))

        assertEquals(CheckInPhase.ON_TRACK, session.state.value.phase)
        assertFalse(session.state.value.movement.lastFixUsable)
        assertTrue(escalations().isEmpty())
    }

    @Test
    fun `movement checks off - nothing is looked up and no concern is raised`() = runTest {
        val session = walking(movementChecks = false)

        walkThrough(List(25) { start })

        assertTrue(resolver.queries.isEmpty())
        assertFalse(session.state.value.movementChecks)
        assertTrue(escalations().isEmpty())
    }

    @Test
    fun `switching the checks off during the walk stops them`() = runTest {
        val session = walking()
        walkThrough(List(10) { start })

        session.setMovementChecks(false)
        walkThrough(List(15) { start })

        assertTrue(escalations().isEmpty())
        assertFalse(store.record!!.movementChecks)
    }

    // ---- arrival -------------------------------------------------------------------------------

    @Test
    fun `arrival - a hint only, the walk is not ended automatically`() = runTest {
        val session = walking()

        walkThrough(listOf(at(north = 500.0), at(north = 960.0), at(north = 990.0)))

        assertTrue(session.state.value.looksArrived)
        assertEquals(R.string.safe_walk_notification_arrived, session.currentNotice()?.text)
        assertTrue(session.state.value.isActive)
        assertFalse("complete" in server.calls)
    }

    // ---- restart -------------------------------------------------------------------------------

    @Test
    fun `an open concern and the destination are recovered after a restart`() = runTest {
        walking(label = null)
        walkThrough(List(21) { start }) // unexpected stop raised

        alerts.clear()
        val afterRestart = newSession()
        afterRestart.recover()
        runCurrent()

        val state = afterRestart.state.value
        assertEquals(CheckInPhase.MISSED, state.phase)
        assertEquals(ConcernKind.UNEXPECTED_STOP, state.checkIn?.concern)
        assertEquals(ConcernKind.UNEXPECTED_STOP, alerts.showingConcern)
        assertTrue(state.movementChecks)
    }

    @Test
    fun `the destination comes back from the server after a restart, so the route check resumes`() = runTest {
        walking()
        val afterRestart = newSession()
        afterRestart.recover()
        runCurrent()

        assertEquals(destination, afterRestart.state.value.journey?.destination)
        emitAt(start)
        walkThrough(offRoute(4))
        assertEquals(ConcernKind.ROUTE_DEVIATION, afterRestart.state.value.checkIn?.concern)
    }

    // ---- battery -------------------------------------------------------------------------------

    @Test
    fun `low battery handled - location less often when low, one warning and a last location when critical`() = runTest {
        val session = walking()
        assertEquals(30_000L, provider.lastIntervalMillis)

        battery.percent = 18
        advance(Duration.ofMinutes(1))
        assertEquals(BatteryLevel.LOW, session.state.value.battery.level)
        assertEquals(60_000L, provider.lastIntervalMillis)

        advance(Duration.ofSeconds(10))
        emitAt(at(north = 5.0)) // not saved by the sampling policy (moved 5 m, 70 s after the last save)
        val unsaved = session.state.value.lastFix
        battery.percent = 9
        advance(Duration.ofMinutes(1))

        assertEquals(BatteryLevel.CRITICAL, session.state.value.battery.level)
        assertEquals(1, alerts.batteryWarnings)
        assertEquals(unsaved, server.locations.last())
        advance(Duration.ofMinutes(5))
        assertEquals(1, alerts.batteryWarnings) // once, not every minute

        battery.charging = true
        advance(Duration.ofMinutes(1))
        assertEquals(BatteryLevel.NORMAL, session.state.value.battery.level)
        assertFalse(alerts.batteryWarningShowing)
        assertEquals(30_000L, provider.lastIntervalMillis)
    }

    @Test
    fun `ending the walk clears the battery warning`() = runTest {
        battery.percent = 5
        val session = walking()
        assertTrue(alerts.batteryWarningShowing)

        session.complete()
        runCurrent()

        assertFalse(alerts.batteryWarningShowing)
    }

    private companion object {
        const val USER_ID = 7L
        const val METERS_PER_DEGREE = 6_371_000.0 * Math.PI / 180.0
    }
}
