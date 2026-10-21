package com.safesphere.app.feature.safewalk

import com.safesphere.app.R
import com.safesphere.app.core.escalation.EscalationLevel
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.safewalk.domain.ActiveJourneyRecord
import com.safesphere.app.feature.safewalk.domain.CheckInPhase
import com.safesphere.app.feature.safewalk.domain.JourneyStatus
import com.safesphere.app.feature.safewalk.session.SafeWalkSession
import com.safesphere.app.testing.FakeActiveJourneyStore
import com.safesphere.app.testing.FakeBatteryMonitor
import com.safesphere.app.testing.FakeConnectivityMonitor
import com.safesphere.app.testing.FakeDestinationResolver
import com.safesphere.app.testing.FakeJourneyRepository
import com.safesphere.app.testing.FakeLocationProvider
import com.safesphere.app.testing.FakeLocationProvider.Companion.fix
import com.safesphere.app.testing.FakeSafeWalkAlerts
import com.safesphere.app.testing.FakeSessionStorage
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4B: the Safe Walk holder (timer, escalation, offline sync, recovery). Virtual time only; the fake
 * server computes deadlines with its own clock like the real backend.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SafeWalkSessionTest {

    private val base = Instant.parse("2026-10-20T18:00:00Z")
    private val provider = FakeLocationProvider(access = LocationAccess.PRECISE)
    private val connectivity = FakeConnectivityMonitor(online = true)
    private val alerts = FakeSafeWalkAlerts()
    private val store = FakeActiveJourneyStore()
    private val sessionStorage = FakeSessionStorage(AuthSession("token", AuthUser(USER_ID, "Asha", "asha@example.com")))
    private lateinit var server: FakeJourneyRepository

    private fun TestScope.phoneNow(): Instant = base.plusMillis(testScheduler.currentTime)

    /** A new holder, as after an app restart (same server, same phone storage). */
    private fun TestScope.newSession(userId: Long? = USER_ID): SafeWalkSession {
        if (!::server.isInitialized) server = FakeJourneyRepository { phoneNow() }
        return SafeWalkSession(
            scope = backgroundScope,
            repository = server,
            store = store,
            locationProvider = provider,
            connectivityMonitor = connectivity,
            alerts = alerts,
            session = sessionStorage.session,
            currentUserId = { userId },
            batteryMonitor = FakeBatteryMonitor(),
            destinationResolver = FakeDestinationResolver(),
            now = { phoneNow() }
        ).also { runCurrent() }
    }

    /** Phase 4B behaviour: movement checks (4C, see SafeWalkMovementTest) are off here. */
    private suspend fun TestScope.walking(intervalMinutes: Int = 10): SafeWalkSession {
        val session = newSession()
        assertTrue(session.start("Home", intervalMinutes, movementChecks = false) is ApiResult.Success)
        runCurrent()
        return session
    }

    private fun TestScope.advance(duration: Duration) {
        advanceTimeBy(duration.toMillis())
        runCurrent()
    }

    // ---- start / complete / cancel ---------------------------------------------------------------

    @Test
    fun `Safe Walk start - the server deadline becomes the phone's countdown and is remembered`() = runTest {
        val session = walking(intervalMinutes = 10)

        val state = session.state.value
        assertTrue(state.isActive)
        assertEquals(phoneNow().plus(Duration.ofMinutes(10)), state.localDueAt)
        assertEquals(CheckInPhase.ON_TRACK, state.phase)
        assertEquals(1L, store.record?.journeyId)
        assertNull(alerts.showing)
    }

    @Test
    fun `phone clock off by ten minutes still counts down the server's interval`() = runTest {
        server = FakeJourneyRepository { phoneNow().plus(Duration.ofMinutes(10)) } // server ahead of the phone
        val session = walking(intervalMinutes = 5)

        assertEquals(phoneNow().plus(Duration.ofMinutes(5)), session.state.value.localDueAt)
    }

    @Test
    fun `Safe Walk complete - ends at once, tells the server and forgets the walk`() = runTest {
        val session = walking()

        session.complete()
        runCurrent()

        assertEquals(JourneyStatus.COMPLETED, session.state.value.endedStatus)
        assertFalse(session.state.value.isActive)
        assertEquals(JourneyStatus.COMPLETED, server.journey?.status)
        assertNull(store.record)
        assertNull(session.notice())
        // No more alerts after the end.
        advance(Duration.ofHours(1))
        assertNull(alerts.showing)
        assertFalse(server.calls.any { it.startsWith("escalate") })
    }

    @Test
    fun `Safe Walk cancel - ends as cancelled`() = runTest {
        val session = walking()

        session.cancel()
        runCurrent()

        assertEquals(JourneyStatus.CANCELLED, session.state.value.endedStatus)
        assertEquals(JourneyStatus.CANCELLED, server.journey?.status)
        session.acknowledgeEnded()
        assertNull(session.state.value.journey)
    }

    // ---- check-ins and the ladder ---------------------------------------------------------------

    @Test
    fun `check-in success - the timer restarts and the server's deadline is adopted`() = runTest {
        val session = walking(intervalMinutes = 10)
        advance(Duration.ofMinutes(7))

        session.checkIn()
        runCurrent()

        assertTrue("checkIn" in server.calls)
        assertEquals(phoneNow().plus(Duration.ofMinutes(10)), session.state.value.localDueAt)
        assertEquals(CheckInPhase.ON_TRACK, session.state.value.phase)
    }

    @Test
    fun `warning - a reminder shows one minute before the deadline, without escalating`() = runTest {
        val session = walking(intervalMinutes = 10)

        advance(Duration.ofMinutes(9))

        assertEquals(CheckInPhase.DUE_SOON, session.state.value.phase)
        assertEquals(CheckInPhase.DUE_SOON, alerts.showing)
        assertFalse(server.calls.any { it.startsWith("escalate") })
    }

    @Test
    fun `check-in missed - level 1, the person is prompted and the server is told`() = runTest {
        val session = walking(intervalMinutes = 10)

        advance(Duration.ofMinutes(10))

        assertEquals(CheckInPhase.MISSED, session.state.value.phase)
        assertEquals(CheckInPhase.MISSED, alerts.showing)
        assertEquals(EscalationLevel.CHECK_IN_MISSED, server.journey?.escalationLevel)
        assertEquals(R.string.safe_walk_notification_missed, session.notice()?.text)
    }

    @Test
    fun `escalation - no answer reaches level 2 one step at a time and never goes higher by itself`() = runTest {
        val session = walking(intervalMinutes = 10)

        advance(Duration.ofMinutes(12))
        assertEquals(CheckInPhase.NO_RESPONSE, session.state.value.phase)
        assertEquals(CheckInPhase.NO_RESPONSE, alerts.showing)
        assertEquals(listOf("escalate:1", "escalate:2"), server.calls.filter { it.startsWith("escalate") })

        advance(Duration.ofHours(2))
        assertEquals(listOf("escalate:1", "escalate:2"), server.calls.filter { it.startsWith("escalate") })
        assertEquals(EscalationLevel.NO_RESPONSE, server.journey?.escalationLevel)
    }

    @Test
    fun `I'm Safe prevents escalation - checking in before every deadline means no alert and no report`() = runTest {
        val session = walking(intervalMinutes = 10)

        repeat(3) {
            advance(Duration.ofMinutes(8))
            session.checkIn()
            runCurrent()
        }

        assertEquals(CheckInPhase.ON_TRACK, session.state.value.phase)
        assertTrue(alerts.shown.isEmpty())
        assertFalse(server.calls.any { it.startsWith("escalate") })
    }

    @Test
    fun `I'm Safe after a missed check-in returns to normal and clears the alert`() = runTest {
        val session = walking(intervalMinutes = 10)
        advance(Duration.ofMinutes(13))
        assertEquals(CheckInPhase.NO_RESPONSE, alerts.showing)

        session.checkIn()
        runCurrent()

        assertNull(alerts.showing)
        assertEquals(CheckInPhase.ON_TRACK, session.state.value.phase)
        assertEquals(EscalationLevel.NORMAL, server.journey?.escalationLevel)
        // The ladder starts again from 0 for the next deadline.
        advance(Duration.ofMinutes(10))
        assertEquals(listOf("escalate:1", "escalate:2", "escalate:1"), server.calls.filter { it.startsWith("escalate") })
    }

    // ---- offline -------------------------------------------------------------------------------

    @Test
    fun `network unavailable - check-ins count on the phone and are sent later`() = runTest {
        val session = walking(intervalMinutes = 10)
        server.offline = true
        connectivity.isOnline.value = false
        advance(Duration.ofMinutes(9) + Duration.ofSeconds(30))

        session.checkIn()
        runCurrent()
        advance(Duration.ofMinutes(5))

        assertEquals(CheckInPhase.ON_TRACK, session.state.value.phase) // no escalation although the server was not told
        assertTrue(session.state.value.pendingSync > 0)
        assertEquals(EscalationLevel.NORMAL, server.journey?.escalationLevel)

        server.offline = false
        connectivity.isOnline.value = true
        runCurrent()

        assertEquals(0, session.state.value.pendingSync)
        assertTrue(server.calls.count { it == "checkIn" } >= 1)
        assertEquals(EscalationLevel.NORMAL, server.journey?.escalationLevel)
    }

    @Test
    fun `ended while offline - the end is remembered and sent after a restart`() = runTest {
        val session = walking()
        server.offline = true

        session.complete()
        runCurrent()
        assertEquals(JourneyStatus.COMPLETED, store.record?.pendingEnd)

        server.offline = false
        val afterRestart = newSession()
        afterRestart.recover()
        runCurrent()

        assertEquals(JourneyStatus.COMPLETED, server.journey?.status)
        assertNull(store.record)
        assertFalse(afterRestart.state.value.isActive)
    }

    // ---- recovery --------------------------------------------------------------------------------

    @Test
    fun `walk recoverable after restart - resumed from the server, overdue walks alert at once`() = runTest {
        walking(intervalMinutes = 10)
        advance(Duration.ofMinutes(11)) // the app was killed and the deadline passed meanwhile
        alerts.clear()

        val afterRestart = newSession()
        afterRestart.recover()
        runCurrent()

        assertTrue(afterRestart.state.value.isActive)
        assertEquals(CheckInPhase.MISSED, afterRestart.state.value.phase)
        assertEquals(CheckInPhase.MISSED, alerts.showing)
    }

    @Test
    fun `walk recoverable after restart while offline - from the phone's record`() = runTest {
        walking(intervalMinutes = 10)
        server.offline = true

        val afterRestart = newSession()
        afterRestart.recover()
        runCurrent()

        val state = afterRestart.state.value
        assertTrue(state.isActive)
        assertTrue(state.restoredOffline)
        assertEquals(base.plus(Duration.ofMinutes(10)), state.localDueAt)
    }

    @Test
    fun `an offline check-in that the server never heard is kept and resent after a restart`() = runTest {
        val session = walking(intervalMinutes = 10)
        server.offline = true
        advance(Duration.ofMinutes(8))
        session.checkIn()
        runCurrent()
        server.offline = false

        val afterRestart = newSession()
        afterRestart.recover()
        runCurrent()

        assertTrue(server.calls.count { it == "checkIn" } >= 1)
        assertEquals(CheckInPhase.ON_TRACK, afterRestart.state.value.phase)
    }

    @Test
    fun `another user's walk record on this phone is ignored`() = runTest {
        store.record = ActiveJourneyRecord(999, 5, 10, base, base.plus(Duration.ofMinutes(10)))
        server = FakeJourneyRepository { phoneNow() }
        server.offline = true

        val session = newSession()
        session.recover()
        runCurrent()

        assertFalse(session.state.value.isActive)
    }

    @Test
    fun `the notification button checks in even when the process was restarted for it`() = runTest {
        walking(intervalMinutes = 10)
        advance(Duration.ofMinutes(10))
        val afterRestart = newSession() // not recovered yet: nothing in memory
        var done = false

        afterRestart.checkInFromNotification { done = true }
        advance(Duration.ofSeconds(1))

        assertTrue(done)
        assertTrue(afterRestart.state.value.isActive)
        assertEquals(CheckInPhase.ON_TRACK, afterRestart.state.value.phase)
        assertEquals(EscalationLevel.NORMAL, server.journey?.escalationLevel)
    }

    // ---- location ------------------------------------------------------------------------------

    @Test
    fun `location permission denied - the walk still runs, without location or the location service`() = runTest {
        provider.access = LocationAccess.NONE
        val session = walking()

        assertTrue(session.state.value.isActive)
        assertFalse(session.state.value.isTracking)
        assertNull(session.notice())
        assertEquals(0, provider.subscriptions)
        advance(Duration.ofMinutes(10))
        assertEquals(CheckInPhase.MISSED, session.state.value.phase) // check-ins work regardless
    }

    @Test
    fun `granting location during the walk starts sharing the last known location`() = runTest {
        provider.access = LocationAccess.NONE
        val session = walking()

        provider.access = LocationAccess.APPROXIMATE
        session.refreshLocationAccess()
        runCurrent()
        provider.emitFix(fix(at = phoneNow()))
        runCurrent()

        assertTrue(session.state.value.isTracking)
        assertEquals(1, server.locations.size)
    }

    @Test
    fun `only the latest location is sent - a queue of old positions is never uploaded`() = runTest {
        val session = walking()
        server.offline = true
        connectivity.isOnline.value = false
        repeat(3) { i ->
            advance(Duration.ofMinutes(3))
            provider.emitFix(fix(latitude = 12.9 + i, at = phoneNow()))
            runCurrent()
        }
        server.offline = false
        connectivity.isOnline.value = true
        runCurrent()

        assertEquals(1, server.locations.size)
        assertEquals(14.9, server.locations.single().latitude, 0.0001)
        assertNotNull(session.state.value.lastLocationSavedAt)
    }

    // ---- other endings ---------------------------------------------------------------------------

    @Test
    fun `signed out - the walk stops at once and alerts are cleared`() = runTest {
        val session = walking()
        advance(Duration.ofMinutes(10))

        sessionStorage.clear()
        runCurrent()

        assertTrue(session.state.value.signedOut)
        assertFalse(session.state.value.isActive)
        assertNull(alerts.showing)
    }

    @Test
    fun `an SOS ends the walk on the phone (the server already linked it)`() = runTest {
        val session = walking()

        session.onEmergencyStarted()
        runCurrent()

        assertEquals(JourneyStatus.EMERGENCY, session.state.value.endedStatus)
        assertNull(store.record)
        advance(Duration.ofHours(1))
        assertTrue(alerts.shown.isEmpty())
    }

    @Test
    fun `a walk ended on another device is ended here too`() = runTest {
        val session = walking()
        server.endOnServer(JourneyStatus.COMPLETED)

        session.checkIn()
        runCurrent()

        assertEquals(JourneyStatus.COMPLETED, session.state.value.endedStatus)
        assertNull(store.record)
    }

    private fun SafeWalkSession.notice() = currentNotice()

    private companion object {
        const val USER_ID = 7L
    }
}
