package com.safesphere.app.feature.circlealert

import com.safesphere.app.core.location.LocationFix
import com.safesphere.app.core.location.LocationSource
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.circlealert.domain.AlertChannel
import com.safesphere.app.feature.circlealert.domain.AlertTarget
import com.safesphere.app.feature.circlealert.domain.CircleAlertSnapshot
import com.safesphere.app.feature.circlealert.domain.CircleMessageKind
import com.safesphere.app.feature.circlealert.domain.CircleRecipient
import com.safesphere.app.feature.circlealert.domain.DeliveryStatus
import com.safesphere.app.feature.circlealert.session.CircleAlertCoordinator
import com.safesphere.app.feature.emergency.location.LocationSharingState
import com.safesphere.app.feature.safetycircle.domain.Relationship
import com.safesphere.app.feature.safetycircle.domain.SafetyContact
import com.safesphere.app.testing.FakeCircleAlertRepository
import com.safesphere.app.testing.FakeCircleAlertStore
import com.safesphere.app.testing.FakeSessionStorage
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Phase 4D, trimmed in N6: what the phone itself does for the Safety Circle. It never texts anyone by itself (the
 * server does, N-104); it keeps the Circle copy for texting by hand offline, reports each opened SMS app (status only)
 * through a saved queue, and records Call 112.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CircleAlertCoordinatorTest {

    private val base = Instant.parse("2026-10-20T13:30:00Z")
    private val store = FakeCircleAlertStore()
    private val server = FakeCircleAlertRepository()
    private val sessionStorage = FakeSessionStorage(AuthSession("token", AuthUser(USER_ID, "Asha", "••••••3210")))
    private val emergency = MutableStateFlow(LocationSharingState())

    private val contacts = listOf(
        contact(1, "Ravi", "9000000001", primary = false, priority = 2),
        contact(2, "Meera", "9000000002", primary = true, priority = 1),
        contact(3, "Old", "9000000003", primary = false, priority = 3, enabled = false)
    )
    private val meera = CircleRecipient(2, "Meera", "9000000002")

    private fun contact(id: Long, name: String, phone: String, primary: Boolean, priority: Int, enabled: Boolean = true) =
        SafetyContact(id, name, phone, null, Relationship.FRIEND, priority, primary, enabled)

    private fun TestScope.now(): Instant = base.plusMillis(testScheduler.currentTime)

    private fun TestScope.coordinator(): CircleAlertCoordinator = CircleAlertCoordinator(
        scope = backgroundScope,
        store = store,
        repository = server,
        emergencyLocation = emergency,
        session = sessionStorage.session,
        now = { now() },
        zone = { ZoneOffset.UTC }
    ).also { runCurrent() }

    private fun TestScope.withCircle(): CircleAlertCoordinator = coordinator().also {
        it.updateRecipients(contacts)
        runCurrent()
    }

    private fun TestScope.advance(duration: Duration) {
        advanceTimeBy(duration.toMillis())
        runCurrent()
    }

    @Test
    fun `only enabled contacts are kept for texting by hand offline, primary first`() = runTest {
        val alerts = withCircle()
        assertEquals(listOf(2L, 1L), alerts.state.value.recipients.map(CircleRecipient::contactId))
        assertEquals(listOf(2L, 1L), store.snapshot?.recipients?.map(CircleRecipient::contactId))
    }

    @Test
    fun `the text for the SMS app carries the SOS's own location, and opening it is recorded as such, never as sent`() =
        runTest {
            val alerts = withCircle()
            emergency.value = LocationSharingState(emergencyId = 7,
                lastFix = LocationFix(12.9716, 77.5946, 8f, LocationSource.FUSED, now()))
            alerts.onEmergencyActive(7, now())
            runCurrent()

            val text = alerts.composerText(AlertTarget.Emergency(7), CircleMessageKind.SOS_STARTED)
            alerts.onComposerOpened(AlertTarget.Emergency(7), CircleMessageKind.SOS_STARTED, meera)
            runCurrent()

            assertTrue(text.contains("12.97160,77.59460"))
            assertFalse(text.contains("automatically"))
            val report = server.reports.values.single()
            assertEquals(DeliveryStatus.COMPOSER_OPENED, report.status)
            assertEquals(AlertChannel.SMS_COMPOSER, report.channel)
        }

    @Test
    fun `a walk is never reported and its text never carries a location (N6)`() = runTest {
        val alerts = withCircle()
        emergency.value = LocationSharingState(emergencyId = 7,
            lastFix = LocationFix(12.9716, 77.5946, 8f, LocationSource.FUSED, now()))
        val text = alerts.composerText(AlertTarget.Walk(21), CircleMessageKind.WALK_NO_RESPONSE)
        alerts.onComposerOpened(AlertTarget.Walk(21), CircleMessageKind.WALK_NO_RESPONSE, meera)
        advance(Duration.ofSeconds(10))

        assertFalse(text.contains("maps"))
        assertTrue(server.reports.isEmpty())
        assertEquals(0, alerts.state.value.pendingReports)
    }

    @Test
    fun `no internet - the report waits in the saved queue and goes out later`() = runTest {
        server.offline = true
        val alerts = withCircle()
        alerts.onComposerOpened(AlertTarget.Emergency(7), CircleMessageKind.SOS_STARTED, meera)
        runCurrent()
        assertEquals(1, alerts.state.value.pendingReports)
        assertEquals(1, store.snapshot?.pendingReports?.size) // survives a restart

        server.offline = false
        advance(Duration.ofSeconds(10))
        assertEquals(0, alerts.state.value.pendingReports)
        assertEquals(1, server.reports.size)
    }

    @Test
    fun `C1 - sign in to sync keeps the Circle copy, and reports once a new token arrives`() = runTest {
        server.unauthorized = true
        val alerts = withCircle()
        sessionStorage.save(sessionStorage.savedSession!!.copy(needsSignIn = true))
        runCurrent()
        alerts.onComposerOpened(AlertTarget.Emergency(7), CircleMessageKind.SOS_STARTED, meera)
        advance(Duration.ofMinutes(10))

        assertEquals(1, alerts.state.value.pendingReports) // waiting, not dropped
        assertEquals(2, store.snapshot?.recipients?.size)
        assertTrue(server.reports.isEmpty())

        server.unauthorized = false
        sessionStorage.save(AuthSession("new-token", AuthUser(USER_ID, "Asha", "••••••3210")))
        runCurrent()
        assertEquals(1, server.reports.size)
    }

    @Test
    fun `C1 - another account signing in on this phone gets none of the previous account's Circle copy`() = runTest {
        val alerts = withCircle()
        sessionStorage.save(AuthSession("other", AuthUser(USER_ID + 1, "Bina", "••••••3210")))
        runCurrent()
        assertTrue(alerts.state.value.recipients.isEmpty())
        assertNull(store.snapshot?.takeIf { it.userId == USER_ID })
    }

    @Test
    fun `logout forgets the cached numbers and the history`() = runTest {
        val alerts = withCircle()
        sessionStorage.clear()
        runCurrent()
        assertNull(store.snapshot)
        assertTrue(alerts.state.value.recipients.isEmpty())
    }

    @Test
    fun `a record kept by a 4D build loads, and is saved again in the N6 shape`() = runTest {
        store.snapshot = CircleAlertSnapshot(USER_ID, recipients = listOf(meera))
        val saves = store.saves
        val alerts = coordinator()
        assertEquals(listOf(2L), alerts.state.value.recipients.map(CircleRecipient::contactId))
        assertTrue(store.saves > saves)
    }

    @Test
    fun `Call 112 is recorded for the emergency (the dialer was opened)`() = runTest {
        val alerts = coordinator()
        alerts.onDialPromptOpened(7)
        runCurrent()
        assertEquals(listOf("dial:7"), server.calls.filter { it.startsWith("dial") })
    }

    @Test
    fun `reports about an SOS the server does not have yet wait for the server's id`() = runTest {
        val alerts = withCircle()
        alerts.onEmergencyActive(LOCAL_ID, now())
        alerts.onComposerOpened(AlertTarget.Emergency(LOCAL_ID), CircleMessageKind.SOS_STARTED, meera)
        runCurrent()
        assertTrue(server.reports.isEmpty()) // nothing reported under a local id

        alerts.rebindEmergency(LOCAL_ID, 70)
        runCurrent()
        assertEquals(1, server.reports.size)
        assertTrue(server.reports.values.all { it.target == AlertTarget.Emergency(70) })
        assertTrue(alerts.state.value.deliveries.all { it.target == AlertTarget.Emergency(70) })
    }

    @Test
    fun `Call 112 for a local id is left to the SOS recorder`() = runTest {
        val alerts = coordinator()
        alerts.onDialPromptOpened(LOCAL_ID)
        runCurrent()
        assertTrue(server.calls.none { it.startsWith("dial") })
    }

    private companion object {
        const val LOCAL_ID = -500L
        const val USER_ID = 7L
    }
}
