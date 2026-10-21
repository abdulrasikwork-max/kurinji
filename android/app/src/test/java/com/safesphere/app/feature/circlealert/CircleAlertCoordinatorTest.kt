package com.safesphere.app.feature.circlealert

import com.safesphere.app.core.escalation.EscalationLevel
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.location.LocationFix
import com.safesphere.app.core.location.LocationSource
import com.safesphere.app.core.sms.SmsAvailability
import com.safesphere.app.core.sms.SmsOutcome
import com.safesphere.app.core.sms.SmsResult
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.circlealert.domain.AlertChannel
import com.safesphere.app.feature.circlealert.domain.AlertTarget
import com.safesphere.app.feature.circlealert.domain.CircleMessageKind
import com.safesphere.app.feature.circlealert.domain.CircleRecipient
import com.safesphere.app.feature.circlealert.domain.DeliveryStatus
import com.safesphere.app.feature.circlealert.session.CircleAlertCoordinator
import com.safesphere.app.feature.emergency.location.LocationSharingState
import com.safesphere.app.feature.emergency.location.LocationSharingStatus
import com.safesphere.app.feature.safetycircle.domain.Relationship
import com.safesphere.app.feature.safetycircle.domain.SafetyContact
import com.safesphere.app.feature.safewalk.domain.CheckInPhase
import com.safesphere.app.feature.safewalk.domain.CheckInStatus
import com.safesphere.app.feature.safewalk.domain.ConcernKind
import com.safesphere.app.feature.safewalk.domain.Journey
import com.safesphere.app.feature.safewalk.domain.JourneyStatus
import com.safesphere.app.feature.safewalk.session.SafeWalkState
import com.safesphere.app.testing.FakeCircleAlertRepository
import com.safesphere.app.testing.FakeCircleAlertStore
import com.safesphere.app.testing.FakeSessionStorage
import com.safesphere.app.testing.FakeSmsSender
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
 * Phase 4D: the Safety Circle communication holder with a fake SMS service, a fake server log and virtual time.
 * Covers the required cases "SMS unavailable", "network unavailable", "escalation", "I'm Safe", "recoverable after restart".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CircleAlertCoordinatorTest {

    private val base = Instant.parse("2026-10-20T13:30:00Z")
    private val sms = FakeSmsSender()
    private val store = FakeCircleAlertStore()
    private val server = FakeCircleAlertRepository()
    private val sessionStorage = FakeSessionStorage(AuthSession("token", AuthUser(USER_ID, "Asha", "asha@example.com")))
    private val walk = MutableStateFlow(SafeWalkState())
    private val emergency = MutableStateFlow(LocationSharingState())

    private val journey = Journey(
        21, JourneyStatus.ACTIVE, null, 10, EscalationLevel.NORMAL, base, null, base.plusSeconds(600), base
    )
    private val contacts = listOf(
        contact(1, "Ravi", "9000000001", primary = false, priority = 2),
        contact(2, "Meera", "9000000002", primary = true, priority = 1),
        contact(3, "Old", "9000000003", primary = false, priority = 3, enabled = false)
    )

    private fun contact(id: Long, name: String, phone: String, primary: Boolean, priority: Int, enabled: Boolean = true) =
        SafetyContact(id, name, phone, null, Relationship.FRIEND, priority, primary, enabled)

    private fun TestScope.now(): Instant = base.plusMillis(testScheduler.currentTime)

    private fun TestScope.coordinator(): CircleAlertCoordinator = CircleAlertCoordinator(
        scope = backgroundScope,
        store = store,
        repository = server,
        smsSender = sms,
        walkState = walk,
        emergencyLocation = emergency,
        session = sessionStorage.session,
        now = { now() },
        zone = { ZoneOffset.UTC }
    ).also { runCurrent() }

    private fun TestScope.optedIn(): CircleAlertCoordinator = coordinator().also {
        it.updateRecipients(contacts)
        it.setAutoSms(true)
        runCurrent()
    }

    private fun TestScope.walkAt(phase: CheckInPhase, concern: ConcernKind? = null, ended: JourneyStatus? = null) {
        val fix = LocationFix(12.9716, 77.5946, 8f, LocationSource.FUSED, now())
        walk.value = SafeWalkState(
            journey = journey,
            localDueAt = base.plusSeconds(600),
            checkIn = CheckInStatus(phase, EscalationLevel.NORMAL, base.plusSeconds(600), null, concern),
            endedStatus = ended,
            access = LocationAccess.PRECISE,
            lastFix = fix
        )
        runCurrent()
    }

    private fun TestScope.advance(duration: Duration) {
        advanceTimeBy(duration.toMillis())
        runCurrent()
    }

    // ---- opt-in and SMS availability --------------------------------------------------------------

    @Test
    fun `automatic SMS is off by default - a level 2 walk texts nobody`() = runTest {
        val alerts = coordinator()
        alerts.updateRecipients(contacts)
        walkAt(CheckInPhase.ON_TRACK)
        walkAt(CheckInPhase.NO_RESPONSE)

        assertTrue(sms.sent.isEmpty())
        assertFalse(alerts.state.value.autoActive)
    }

    @Test
    fun `SMS unavailable - no SMS support or a revoked permission means nothing is sent or claimed`() = runTest {
        sms.availability = SmsAvailability.NOT_SUPPORTED
        val alerts = optedIn()
        walkAt(CheckInPhase.ON_TRACK)
        walkAt(CheckInPhase.NO_RESPONSE)
        assertTrue(sms.sent.isEmpty())
        assertFalse(alerts.state.value.autoActive)
        assertTrue(alerts.state.value.deliveries.isEmpty())

        // Permission revoked later: still nothing, and the alert can still go out once it is allowed again.
        sms.availability = SmsAvailability.PERMISSION_NEEDED
        walkAt(CheckInPhase.ON_TRACK)
        walkAt(CheckInPhase.NO_RESPONSE)
        assertTrue(sms.sent.isEmpty())
        assertEquals(SmsAvailability.PERMISSION_NEEDED, alerts.state.value.availability)

        sms.availability = SmsAvailability.AVAILABLE
        walkAt(CheckInPhase.ON_TRACK)
        walkAt(CheckInPhase.NO_RESPONSE)
        assertEquals(2, sms.sent.size)
    }

    @Test
    fun `only enabled contacts are kept for offline SMS, primary first`() = runTest {
        val alerts = coordinator()
        alerts.updateRecipients(contacts)
        runCurrent()

        assertEquals(listOf(2L, 1L), alerts.state.value.recipients.map(CircleRecipient::contactId))
        assertEquals(listOf(2L, 1L), store.snapshot?.recipients?.map(CircleRecipient::contactId))
    }

    // ---- level 2 (Safe Walk) ---------------------------------------------------------------------

    @Test
    fun `escalation communication - level 2 texts each enabled contact, results are reported honestly`() = runTest {
        optedIn()
        walkAt(CheckInPhase.ON_TRACK)
        walkAt(CheckInPhase.NO_RESPONSE, ConcernKind.UNEXPECTED_STOP)

        assertEquals(listOf("9000000002", "9000000001"), sms.sent.map { it.phoneNumber })
        val text = sms.sent.first().text
        assertTrue(text.contains("Asha did not answer a Safe Walk safety check"))
        assertTrue(text.contains("after not moving for a while"))
        assertTrue(text.contains("https://www.google.com/maps/search/?api=1&query=12.97160,77.59460"))
        assertTrue("nothing is reported while still sending", server.reports.isEmpty())

        val first = sms.sent.first().requestId
        sms.emit(SmsResult(first, SmsOutcome.SENT))
        runCurrent()
        assertEquals(DeliveryStatus.SENT, server.reports[first]?.status)
        assertEquals(AlertTarget.Walk(21), server.reports[first]?.target)
        assertEquals(CircleMessageKind.WALK_NO_RESPONSE, server.reports[first]?.kind)

        sms.emit(SmsResult(first, SmsOutcome.DELIVERED))
        runCurrent()
        assertEquals(DeliveryStatus.DELIVERED, server.reports[first]?.status)
        // A late "sent" never hides "delivered".
        sms.emit(SmsResult(first, SmsOutcome.SENT))
        runCurrent()
        assertEquals(DeliveryStatus.DELIVERED, server.reports[first]?.status)
    }

    @Test
    fun `a message the phone could not hand over is FAILED, reported, and the screen can offer the SMS app`() = runTest {
        val alerts = optedIn()
        sms.accept = false
        walkAt(CheckInPhase.ON_TRACK)
        walkAt(CheckInPhase.NO_RESPONSE)

        assertTrue(alerts.state.value.deliveries.all { it.status == DeliveryStatus.FAILED })
        assertEquals(2, server.reports.values.count { it.status == DeliveryStatus.FAILED })
    }

    @Test
    fun `I'm Safe after the alert - the circle is told the person is OK, once`() = runTest {
        optedIn()
        walkAt(CheckInPhase.ON_TRACK)
        walkAt(CheckInPhase.NO_RESPONSE)
        walkAt(CheckInPhase.ON_TRACK)
        walkAt(CheckInPhase.DUE_SOON)

        val allClear = sms.sent.drop(2)
        assertEquals(2, allClear.size)
        assertTrue(allClear.all { it.text.contains("responded at") && it.text.contains("is OK") })
    }

    @Test
    fun `arriving after an alert also sends the all clear`() = runTest {
        optedIn()
        walkAt(CheckInPhase.ON_TRACK)
        walkAt(CheckInPhase.NO_RESPONSE)
        walkAt(CheckInPhase.NO_RESPONSE, ended = JourneyStatus.COMPLETED)

        assertEquals(4, sms.sent.size)
    }

    // ---- level 3 (SOS) ---------------------------------------------------------------------------

    @Test
    fun `SOS - waits for a first location, then sends without, follows up with it, and tells the end`() = runTest {
        optedIn()
        emergency.value = LocationSharingState(emergencyId = 7, status = LocationSharingStatus.SEARCHING)
        val alerts = coordinator() // same store: already opted in
        runCurrent()
        alerts.onEmergencyActive(7, now())
        runCurrent()
        assertTrue(sms.sent.isEmpty())

        advance(Duration.ofSeconds(30))
        assertEquals(2, sms.sent.size)
        assertTrue(sms.sent.all { it.text.contains("started an emergency alert") && it.text.contains("Location is not available.") })

        emergency.value = LocationSharingState(
            emergencyId = 7, status = LocationSharingStatus.AVAILABLE,
            lastFix = LocationFix(12.98, 77.6, 6f, LocationSource.FUSED, now())
        )
        runCurrent()
        assertEquals(4, sms.sent.size)
        assertTrue(sms.sent.last().text.contains("query=12.98000,77.60000"))

        alerts.onEmergencyEnded(7, resolved = true)
        runCurrent()
        assertEquals(6, sms.sent.size)
        assertTrue(sms.sent.last().text.contains("marked themselves safe"))
    }

    @Test
    fun `an SOS with a location goes out at once`() = runTest {
        val alerts = optedIn()
        emergency.value = LocationSharingState(
            emergencyId = 7, status = LocationSharingStatus.AVAILABLE,
            lastFix = LocationFix(12.98, 77.6, 6f, LocationSource.FUSED, now())
        )
        alerts.onEmergencyActive(7, now())
        runCurrent()

        assertEquals(2, sms.sent.size)
        assertTrue(sms.sent.first().text.contains("query=12.98000,77.60000"))
    }

    // ---- restart, offline, logout ------------------------------------------------------------------

    @Test
    fun `alerts are recoverable after restart - the same event is never texted twice`() = runTest {
        optedIn()
        walkAt(CheckInPhase.ON_TRACK)
        walkAt(CheckInPhase.NO_RESPONSE)
        assertEquals(2, sms.sent.size)

        val afterRestart = coordinator() // new holder, same phone storage, walk still at level 2
        runCurrent()

        assertEquals(2, sms.sent.size)
        assertTrue(afterRestart.state.value.autoSms)
        assertEquals(2, afterRestart.state.value.deliveries.size)
    }

    @Test
    fun `network unavailable - SMS still goes out, the server is told later from the saved queue`() = runTest {
        server.offline = true
        val alerts = optedIn()
        walkAt(CheckInPhase.ON_TRACK)
        walkAt(CheckInPhase.NO_RESPONSE)
        sms.sent.forEach { sms.emit(SmsResult(it.requestId, SmsOutcome.SENT)) }
        runCurrent()

        assertEquals(2, sms.sent.size) // no internet needed for SMS
        assertEquals(2, alerts.state.value.pendingReports)
        assertEquals(2, store.snapshot?.pendingReports?.size) // survives a restart

        server.offline = false
        advance(Duration.ofSeconds(10))
        assertEquals(0, alerts.state.value.pendingReports)
        assertEquals(2, server.reports.size)
    }

    @Test
    fun `logout forgets the opt-in, the cached numbers and the history`() = runTest {
        val alerts = optedIn()
        walkAt(CheckInPhase.ON_TRACK)
        walkAt(CheckInPhase.NO_RESPONSE)

        sessionStorage.clear()
        runCurrent()

        assertNull(store.snapshot)
        assertFalse(alerts.state.value.autoSms)
        assertTrue(alerts.state.value.recipients.isEmpty())
    }

    // ---- manual paths --------------------------------------------------------------------------

    @Test
    fun `opening the SMS app is recorded as such, never as sent`() = runTest {
        val alerts = coordinator()
        alerts.updateRecipients(contacts)
        walkAt(CheckInPhase.NO_RESPONSE)

        val text = alerts.composerText(AlertTarget.Walk(21), CircleMessageKind.WALK_NO_RESPONSE)
        alerts.onComposerOpened(AlertTarget.Walk(21), CircleMessageKind.WALK_NO_RESPONSE, CircleRecipient(2, "Meera", "9000000002"))
        runCurrent()

        assertFalse(text.contains("automatically"))
        val report = server.reports.values.single()
        assertEquals(DeliveryStatus.COMPOSER_OPENED, report.status)
        assertEquals(AlertChannel.SMS_COMPOSER, report.channel)
        assertTrue(sms.sent.isEmpty())
    }

    @Test
    fun `Call 112 is recorded for the emergency (the dialer was opened)`() = runTest {
        val alerts = coordinator()
        alerts.onDialPromptOpened(7)
        runCurrent()

        assertEquals(listOf("dial:7"), server.calls.filter { it.startsWith("dial") })
    }

    private companion object {
        const val USER_ID = 7L
    }
}
