package com.safesphere.app.feature.safewalk

import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.safewalk.domain.CheckInPhase
import com.safesphere.app.feature.safewalk.domain.JourneyStatus
import com.safesphere.app.feature.safewalk.session.SafeWalkSession
import com.safesphere.app.testing.FakeActiveJourneyStore
import com.safesphere.app.testing.FakeConnectivityMonitor
import com.safesphere.app.testing.FakeJourneyRepository
import com.safesphere.app.testing.FakeReminderScheduler
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
 * N6 (D22): "On my way", the reminder holder: the alarms it schedules, the reminder and the one nudge, More time,
 * I'm there, the notification actions after the process died, offline sync and recovery. Virtual time only; the fake
 * server computes the reminder time with its own clock like the real backend. Nothing here reads a location.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SafeWalkSessionTest {

    private val base = Instant.parse("2026-10-20T18:00:00Z")
    private val connectivity = FakeConnectivityMonitor(online = true)
    private val alerts = FakeSafeWalkAlerts()
    private val reminders = FakeReminderScheduler()
    private val store = FakeActiveJourneyStore()
    private val sessionStorage = FakeSessionStorage(AuthSession("token", AuthUser(USER_ID, "Asha", "••••••3210")))
    private lateinit var server: FakeJourneyRepository

    private fun TestScope.phoneNow(): Instant = base.plusMillis(testScheduler.currentTime)

    /** A new holder, as after an app restart (same server, same phone storage). */
    private fun TestScope.newSession(userId: Long? = USER_ID): SafeWalkSession {
        if (!::server.isInitialized) server = FakeJourneyRepository { phoneNow() }
        return SafeWalkSession(
            scope = backgroundScope,
            repository = server,
            store = store,
            connectivityMonitor = connectivity,
            alerts = alerts,
            reminders = reminders,
            session = sessionStorage.session,
            currentUserId = { userId },
            now = { phoneNow() }
        ).also { runCurrent() }
    }

    private suspend fun TestScope.walking(minutes: Int = 20, label: String? = "Library"): SafeWalkSession {
        val session = newSession()
        assertTrue(session.start(label, minutes) is ApiResult.Success)
        runCurrent()
        return session
    }

    private fun TestScope.advance(duration: Duration) {
        advanceTimeBy(duration.toMillis())
        runCurrent()
    }

    @Test
    fun `starting schedules the reminder and the one nudge 5 minutes later, and keeps only ids and times`() = runTest {
        val session = walking(20, "  Library  ")
        val due = base.plus(Duration.ofMinutes(20))
        assertEquals(Triple(1L, due, due.plus(Duration.ofMinutes(5))), reminders.scheduled)
        assertEquals("Library", server.journey?.destinationLabel)
        assertEquals(CheckInPhase.ON_TRACK, session.state.value.phase)
        assertEquals(due, store.record?.localDueAt)
        assertNull("nothing shown yet", alerts.showing)
    }

    @Test
    fun `at the time - There yet, 5 minutes later one nudge, then nothing more, and nobody is told`() = runTest {
        val session = walking(10)
        advance(Duration.ofMinutes(10))
        assertEquals(CheckInPhase.MISSED, session.state.value.phase)
        assertEquals(CheckInPhase.MISSED, alerts.showing)
        advance(Duration.ofMinutes(5))
        assertEquals(CheckInPhase.NO_RESPONSE, alerts.showing)
        advance(Duration.ofHours(2))
        assertEquals(listOf(CheckInPhase.MISSED, CheckInPhase.NO_RESPONSE), alerts.shown)
        assertEquals("only the start reached the server", listOf("start"), server.calls)
    }

    @Test
    fun `More time moves the reminder one interval from now, on the phone at once and on the server`() = runTest {
        val session = walking(10)
        advance(Duration.ofMinutes(11))
        session.checkIn()
        runCurrent()
        val due = base.plus(Duration.ofMinutes(21))
        assertEquals(CheckInPhase.ON_TRACK, session.state.value.phase)
        assertNull(alerts.showing)
        assertEquals(due, reminders.scheduled?.second)
        assertTrue("checkIn" in server.calls)
    }

    @Test
    fun `I'm there ends it, cancels the alarms and the server is told`() = runTest {
        val session = walking()
        session.complete()
        runCurrent()
        assertEquals(JourneyStatus.COMPLETED, session.state.value.endedStatus)
        assertNull(reminders.scheduled)
        assertEquals(JourneyStatus.COMPLETED, server.journey?.status)
        assertNull("the record goes once the server confirmed", store.record)
    }

    @Test
    fun `offline - the end is kept and sent later, and the alarms stop at once`() = runTest {
        val session = walking()
        server.offline = true
        session.cancel()
        runCurrent()
        assertNull(reminders.scheduled)
        assertEquals(JourneyStatus.CANCELLED, store.record?.pendingEnd)
        server.offline = false
        advance(Duration.ofSeconds(10))
        assertEquals(JourneyStatus.CANCELLED, server.journey?.status)
        assertNull(store.record)
    }

    @Test
    fun `an alarm after the process died recovers the walk and shows the reminder once`() = runTest {
        walking(10)
        advance(Duration.ofMinutes(10))
        val fresh = newSession() // the process was started just for the alarm
        var done = false
        fresh.onAlarm(1) { done = true }
        runCurrent()
        assertTrue(done)
        assertEquals(CheckInPhase.MISSED, fresh.state.value.phase)
        assertEquals(CheckInPhase.MISSED, alerts.showing)
    }

    @Test
    fun `a stale alarm (walk ended, or another walk) shows nothing`() = runTest {
        val session = walking(10)
        session.complete()
        advance(Duration.ofMinutes(15))
        val before = alerts.shown.size
        var done = false
        session.onAlarm(1) { done = true }
        runCurrent()
        assertTrue(done)
        assertEquals(before, alerts.shown.size)
        session.onAlarm(99) {}
        runCurrent()
        assertEquals(before, alerts.shown.size)
    }

    @Test
    fun `More time and I'm there from the notification work after the process died`() = runTest {
        walking(10)
        advance(Duration.ofMinutes(12))
        val fresh = newSession()
        var done = false
        fresh.checkInFromNotification { done = true }
        advance(Duration.ofSeconds(1))
        assertTrue(done)
        assertEquals(base.plus(Duration.ofMinutes(22)), fresh.state.value.localDueAt)
        fresh.completeFromNotification {}
        advance(Duration.ofSeconds(1))
        assertEquals(JourneyStatus.COMPLETED, server.journey?.status)
    }

    @Test
    fun `an SOS ends it here without telling the server again, and stops the alarms`() = runTest {
        val session = walking()
        session.onEmergencyStarted()
        runCurrent()
        assertEquals(JourneyStatus.EMERGENCY, session.state.value.endedStatus)
        assertNull(reminders.scheduled)
        assertFalse("complete" in server.calls || "cancel" in server.calls)
    }

    @Test
    fun `recovery offline uses the phone's record, and More time tapped offline is sent later`() = runTest {
        walking(10)
        server.offline = true
        val restarted = newSession()
        restarted.recover()
        runCurrent()
        assertTrue(restarted.state.value.restoredOffline)
        restarted.checkIn()
        server.offline = false
        advance(Duration.ofSeconds(10))
        assertNotNull(restarted.state.value.journey?.destinationLabel)
        assertFalse(restarted.state.value.restoredOffline)
    }

    @Test
    fun `a sign-out forgets the walk on this phone`() = runTest {
        val session = walking()
        sessionStorage.clear()
        runCurrent()
        assertTrue(session.state.value.signedOut)
        assertNull(reminders.scheduled)
    }

    private companion object {
        const val USER_ID = 7L
    }
}
