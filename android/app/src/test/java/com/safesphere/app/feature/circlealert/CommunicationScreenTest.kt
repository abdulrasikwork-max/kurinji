package com.safesphere.app.feature.circlealert

import com.safesphere.app.R
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.circlealert.domain.AlertChannel
import com.safesphere.app.feature.circlealert.domain.AlertTarget
import com.safesphere.app.feature.circlealert.domain.CircleDelivery
import com.safesphere.app.feature.circlealert.domain.CircleMessageKind
import com.safesphere.app.feature.circlealert.domain.CircleRecipient
import com.safesphere.app.feature.circlealert.domain.ContextAlert
import com.safesphere.app.feature.circlealert.domain.ContextEscalation
import com.safesphere.app.feature.circlealert.domain.DeliveryStatus
import com.safesphere.app.feature.circlealert.domain.EmergencyContext
import com.safesphere.app.feature.circlealert.session.CircleAlertState
import com.safesphere.app.feature.circlealert.ui.DeliveryLabel
import com.safesphere.app.feature.circlealert.ui.SummaryText
import com.safesphere.app.feature.circlealert.ui.deliveryLabelFor
import com.safesphere.app.feature.circlealert.ui.walkCircleLineFor
import com.safesphere.app.feature.emergency.ui.DetailEntry
import com.safesphere.app.feature.emergency.ui.EmergencyDetailUiState
import com.safesphere.app.feature.emergency.ui.EmergencyDetailViewModel
import com.safesphere.app.feature.emergency.ui.EmergencyUiState
import com.safesphere.app.feature.emergency.ui.EmergencyViewModel
import com.safesphere.app.feature.emergency.ui.alertStatusLabel
import com.safesphere.app.feature.emergency.ui.detailEntriesFor
import com.safesphere.app.feature.emergency.ui.escalationReasonLabel
import com.safesphere.app.feature.safetycircle.data.RecipientSyncingSafetyCircleRepository
import com.safesphere.app.feature.safetycircle.ui.SafetyAlertsViewModel
import com.safesphere.app.feature.circlealert.data.RemoteSafetyAlertRepository
import com.safesphere.app.feature.circlealert.domain.AlertChoice
import com.safesphere.app.feature.circlealert.domain.ContactAlertLine
import com.safesphere.app.feature.circlealert.domain.EmergencyAlerts
import com.safesphere.app.feature.circlealert.domain.ServerAlert
import com.safesphere.app.feature.circlealert.domain.ServerAlertKind
import com.safesphere.app.feature.circlealert.domain.ServerAlertLines
import com.safesphere.app.feature.circlealert.domain.ServerAlertStatus
import com.safesphere.app.feature.circlealert.domain.SkipCause
import com.safesphere.app.testing.FakeSafetyAlertRepository
import com.safesphere.app.testing.FakeActiveEmergencyStore
import com.safesphere.app.testing.FakeCircleAlertController
import com.safesphere.app.testing.FakeCircleAlertRepository
import com.safesphere.app.testing.FakeEmergencyRepository
import com.safesphere.app.testing.FakeEmergencyRepository.Companion.event
import com.safesphere.app.testing.FakeLocationSharingController
import com.safesphere.app.testing.FakeSafetyCircleRepository
import com.safesphere.app.testing.FakeSafetyCircleRepository.Companion.contact
import com.safesphere.app.testing.MainDispatcherRule
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.safesphere.app.testing.settle
import com.safesphere.app.testing.sosRecorderForTest

/** Phase 4D + N6 screens: honest status texts, the alerts choice card, the SOS hooks and polling, the details screen, contact sync. */
@OptIn(ExperimentalCoroutinesApi::class)
class CommunicationScreenTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val t0 = Instant.parse("2026-10-20T13:30:00Z")
    private val walk = AlertTarget.Walk(21)
    private val recipients = listOf(CircleRecipient(2, "Meera", "9000000002"))

    private fun delivery(status: DeliveryStatus, kind: CircleMessageKind = CircleMessageKind.WALK_NO_RESPONSE, at: Instant = t0) =
        CircleDelivery("ref-$status-$at", walk, kind, 2, "Meera", AlertChannel.SMS_AUTO, status, at)

    // ---- status texts ----------------------------------------------------------------------------

    @Test
    fun `per-contact status never claims more than is known`() {
        val sos = AlertTarget.Emergency(9)
        val none = CircleAlertState(recipients = recipients)
        assertEquals(DeliveryLabel(R.string.circle_status_not_notified), deliveryLabelFor(none, sos, 2))

        // N6: what IRL's server did comes first; SENT is always "delivery not confirmed".
        assertEquals(DeliveryLabel(R.string.circle_status_irl_waiting), deliveryLabelFor(none, sos, 2, ContactAlertLine.Waiting))
        assertEquals(DeliveryLabel(R.string.circle_status_irl_texted, t0),
            deliveryLabelFor(none, sos, 2, ContactAlertLine.Texted(t0, late = false)))
        assertEquals(DeliveryLabel(R.string.circle_status_irl_texted_late, t0),
            deliveryLabelFor(none, sos, 2, ContactAlertLine.Texted(t0, late = true)))
        assertEquals(DeliveryLabel(R.string.circle_status_irl_cant),
            deliveryLabelFor(none, sos, 2, ContactAlertLine.TextYourself(SkipCause.NOT_INDIAN_MOBILE)))
        assertEquals(DeliveryLabel(R.string.circle_status_irl_unknown), deliveryLabelFor(none, sos, 2, ContactAlertLine.NotConfirmed))

        // What the person sent by hand: "SMS app opened", never "sent"; it replaces "text them yourself" once done.
        val handSent = none.copy(deliveries = listOf(CircleDelivery("r1", sos, CircleMessageKind.SOS_STARTED, 2, "Meera",
            AlertChannel.SMS_COMPOSER, DeliveryStatus.COMPOSER_OPENED, t0)))
        assertEquals(DeliveryLabel(R.string.circle_status_composer, t0), deliveryLabelFor(handSent, sos, 2))
        assertEquals(DeliveryLabel(R.string.circle_status_composer, t0),
            deliveryLabelFor(handSent, sos, 2, ContactAlertLine.TextYourself(null)))
    }

    @Test
    fun `the server's answer maps fail-closed - unknown statuses are not confirmed, only the first text decides`() {
        val alerts = listOf(
            ServerAlert(2, "Meera", ServerAlertKind.SOS_STARTED, ServerAlertStatus.SENT, null, t0, false),
            ServerAlert(2, "Meera", ServerAlertKind.SOS_RESOLVED, ServerAlertStatus.SENT, null, t0.plusSeconds(60), false),
            ServerAlert(3, "Ravi", ServerAlertKind.SOS_STARTED, ServerAlertStatus.of("BOUNCED_SOMEHOW"), null, t0, false),
            ServerAlert(4, "Uncle", ServerAlertKind.SOS_STARTED, ServerAlertStatus.SKIPPED, SkipCause.of("NEW_REASON"), t0, false)
        )
        assertEquals(ContactAlertLine.Texted(t0, false), ServerAlertLines.lineFor(2, alerts))
        assertEquals(ContactAlertLine.NotConfirmed, ServerAlertLines.lineFor(3, alerts))
        assertEquals(ContactAlertLine.TextYourself(SkipCause.OTHER), ServerAlertLines.lineFor(4, alerts))
        assertNull(ServerAlertLines.lineFor(5, alerts))
        assertTrue(ServerAlertLines.closingTexted(2, alerts))
        assertFalse(ServerAlertLines.closingTexted(3, alerts))
        assertEquals(ServerAlertKind.OTHER, ServerAlertKind.of(null))
        assertEquals(AlertChoice.UNSET, RemoteSafetyAlertRepository.choiceOf("MAYBE"))
        // 15 s without an answer from IRL: "text them yourself".
        assertFalse(ServerAlertLines.unreachable(since = 0, lastAnswer = null, now = 14_999))
        assertTrue(ServerAlertLines.unreachable(since = 0, lastAnswer = null, now = 15_000))
        assertFalse(ServerAlertLines.unreachable(since = 0, lastAnswer = 10_000, now = 20_000))
    }

    @Test
    fun `the Safe Walk line says whether the circle was or was not told`() {
        val off = CircleAlertState(recipients = recipients)
        assertNull(walkCircleLineFor(off, walk, alerting = false))
        assertEquals(SummaryText(R.string.circle_walk_not_notified), walkCircleLineFor(off, walk, alerting = true))
        assertEquals(SummaryText(R.string.circle_walk_sent, 1),
            walkCircleLineFor(off.copy(deliveries = listOf(delivery(DeliveryStatus.SENT))), walk, alerting = true))
        assertEquals(SummaryText(R.string.circle_walk_composer),
            walkCircleLineFor(off.copy(deliveries = listOf(delivery(DeliveryStatus.COMPOSER_OPENED))), walk, alerting = true))
    }

    // ---- N6: the choice card ------------------------------------------------------------------------

    @Test
    fun `the alerts card - nothing is chosen for the person, a change goes to the server, the test once a day`() = runTest {
        val repository = FakeSafetyAlertRepository()
        val viewModel = SafetyAlertsViewModel(repository)
        viewModel.load()
        advanceUntilIdle()
        assertEquals(AlertChoice.UNSET, viewModel.ui.value.settings?.choice)
        assertTrue(viewModel.ui.value.settings?.preview!!.startsWith("IRL SOS: Asha"))

        viewModel.choose(true)
        advanceUntilIdle()
        assertEquals(AlertChoice.ON, viewModel.ui.value.settings?.choice)
        assertEquals(listOf("settings", "choose:true"), repository.calls)

        viewModel.test()
        advanceUntilIdle()
        assertEquals(ServerAlertStatus.QUEUED, viewModel.ui.value.settings?.lastTest?.status)
        viewModel.test()
        advanceUntilIdle()
        assertTrue(viewModel.ui.value.testLimit)

        repository.offline = true
        viewModel.load()
        advanceUntilIdle()
        assertTrue(viewModel.ui.value.failed)
        assertEquals(AlertChoice.ON, viewModel.ui.value.settings?.choice) // the last answer stays on screen
    }

    @Test
    fun `the SOS screen reads what IRL texted, and says so when it cannot reach IRL for 15 s`() = runTest {
        val emergencies = FakeEmergencyRepository().apply { historyResult = ApiResult.Success(listOf(event(id = 9))) }
        val alerts = FakeSafetyAlertRepository()
        var clock = 0L
        val viewModel = EmergencyViewModel(
            emergencyRepository = emergencies,
            safetyCircleRepository = FakeSafetyCircleRepository(listOf(contact(1, "Mum", primary = true))),
            locationSharing = FakeLocationSharingController(),
            sos = sosRecorderForTest(emergencies, FakeActiveEmergencyStore()),
            currentUserId = { 7L },
            trackingServiceRunning = MutableStateFlow(false),
            alertRepository = alerts,
            nowMillis = { clock }
        )
        settle()
        alerts.emergency = EmergencyAlerts(AlertChoice.ON, listOf(
            ServerAlert(1, "Mum", ServerAlertKind.SOS_STARTED, ServerAlertStatus.SENT, null, t0, false)))
        viewModel.pollAlertsOnce()
        assertEquals(ContactAlertLine.Texted(t0, false), ServerAlertLines.lineFor(1, viewModel.serverAlerts.value.alerts))
        assertFalse(viewModel.serverAlerts.value.unreachable)

        alerts.offline = true
        clock = 10_000
        viewModel.pollAlertsOnce()
        assertFalse(viewModel.serverAlerts.value.unreachable)
        clock = 15_000
        viewModel.pollAlertsOnce()
        assertTrue(viewModel.serverAlerts.value.unreachable)
        assertEquals(listOf(9L, 9L, 9L), alerts.emergencyCalls)
    }

    // ---- SOS screen hooks ------------------------------------------------------------------------

    @Test
    fun `the SOS screen tells the circle holder when an emergency starts and how it ends, and records Call 112`() = runTest {
        val controller = FakeCircleAlertController()
        val emergencies = FakeEmergencyRepository().apply { historyResult = ApiResult.Success(listOf(event(id = 9))) }
        val viewModel = EmergencyViewModel(
            emergencyRepository = emergencies,
            safetyCircleRepository = FakeSafetyCircleRepository(listOf(contact(1, "Mum", primary = true))),
            locationSharing = FakeLocationSharingController(),
            sos = sosRecorderForTest(emergencies, FakeActiveEmergencyStore()),
            currentUserId = { 7L },
            trackingServiceRunning = MutableStateFlow(false),
            circleAlerts = controller
        )
        settle()
        assertTrue(viewModel.uiState.value is EmergencyUiState.Active)
        assertTrue("active:9" in controller.calls)

        viewModel.onCallEmergencyNumberClicked()
        assertTrue("dial:9" in controller.calls)
        assertEquals("text:SOS_STARTED", viewModel.composerTextFor())

        viewModel.onImSafeClicked()
        settle()
        assertTrue("ended:9:true" in controller.calls)
    }

    // ---- Emergency details (context packet) ---------------------------------------------------------

    @Test
    fun `the details screen loads the owner's context packet and orders what happened`() = runTest {
        val repository = FakeCircleAlertRepository()
        repository.context = ApiResult.Success(
            EmergencyContext(
                emergencyId = 9, status = "RESOLVED", startedAt = t0, endedAt = t0.plusSeconds(900),
                walkDestinationLabel = "Station", latestLocationLink = "https://www.google.com/maps/search/?api=1&query=1,2",
                latestLocationAt = t0.plusSeconds(30), locationCount = 4, highestLevel = 4,
                escalations = listOf(
                    ContextEscalation(4, "DIAL_PROMPT_OPENED", t0.plusSeconds(120), fromWalk = false),
                    ContextEscalation(3, "SOS_CONFIRMED", t0, fromWalk = false)
                ),
                alerts = listOf(ContextAlert(CircleMessageKind.SOS_STARTED, AlertChannel.SMS_AUTO, DeliveryStatus.SENT, "Meera", t0.plusSeconds(31), false))
            )
        )
        val viewModel = EmergencyDetailViewModel(repository, 9)
        advanceUntilIdle()

        val loaded = viewModel.uiState.value as EmergencyDetailUiState.Loaded
        val entries = detailEntriesFor(loaded.context)
        assertEquals(listOf(t0, t0.plusSeconds(31), t0.plusSeconds(120)), entries.map(DetailEntry::at))
        assertEquals(R.string.reason_dial_prompt, escalationReasonLabel("DIAL_PROMPT_OPENED"))
        assertEquals(R.string.alert_status_sent_auto, alertStatusLabel(DeliveryStatus.SENT, AlertChannel.SMS_AUTO))
        assertEquals(R.string.alert_status_composer, alertStatusLabel(DeliveryStatus.COMPOSER_OPENED, AlertChannel.SMS_COMPOSER))
        assertEquals(listOf("context:9"), repository.calls)
    }

    // ---- offline copy of the Safety Circle --------------------------------------------------------

    @Test
    fun `every successful contact load and change refreshes the offline copy`() = runTest {
        val seen = mutableListOf<Int>()
        val repository = RecipientSyncingSafetyCircleRepository(
            FakeSafetyCircleRepository(listOf(contact(1, "Mum", primary = true)))
        ) { seen += it.size }

        repository.getContacts()
        repository.deleteContact(1)

        assertEquals(listOf(1, 0), seen)
    }
}
