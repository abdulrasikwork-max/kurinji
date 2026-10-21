package com.safesphere.app.feature.circlealert

import com.safesphere.app.R
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.sms.SmsAvailability
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
import com.safesphere.app.feature.safetycircle.ui.AutoSmsEnableAction
import com.safesphere.app.feature.safetycircle.ui.AutoSmsViewModel
import com.safesphere.app.feature.safetycircle.ui.autoSmsStatusFor
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Phase 4D screens: honest status texts, the opt-in card, the SOS hooks, the details screen, contact sync. */
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
        val off = CircleAlertState(recipients = recipients)
        assertEquals(DeliveryLabel(R.string.circle_status_not_notified), deliveryLabelFor(off, walk, 2))
        val on = off.copy(autoSms = true, availability = SmsAvailability.AVAILABLE)
        assertEquals(DeliveryLabel(R.string.circle_status_will_text), deliveryLabelFor(on, walk, 2))

        assertEquals(DeliveryLabel(R.string.circle_status_sent, t0),
            deliveryLabelFor(on.copy(deliveries = listOf(delivery(DeliveryStatus.SENT))), walk, 2))
        assertEquals(DeliveryLabel(R.string.circle_status_composer, t0),
            deliveryLabelFor(on.copy(deliveries = listOf(delivery(DeliveryStatus.COMPOSER_OPENED))), walk, 2))
        // The latest attempt wins.
        val later = on.copy(deliveries = listOf(delivery(DeliveryStatus.FAILED), delivery(DeliveryStatus.DELIVERED, at = t0.plusSeconds(5))))
        assertEquals(R.string.circle_status_delivered, deliveryLabelFor(later, walk, 2).text)
    }

    @Test
    fun `the Safe Walk line says whether the circle will be, was, or was not told`() {
        val off = CircleAlertState(recipients = recipients)
        assertNull(walkCircleLineFor(off, walk, alerting = false))
        assertEquals(SummaryText(R.string.circle_walk_not_notified), walkCircleLineFor(off, walk, alerting = true))
        val on = off.copy(autoSms = true, availability = SmsAvailability.AVAILABLE)
        assertEquals(SummaryText(R.string.circle_walk_will_text), walkCircleLineFor(on, walk, alerting = true))
        assertEquals(SummaryText(R.string.circle_walk_sent, 1),
            walkCircleLineFor(on.copy(deliveries = listOf(delivery(DeliveryStatus.SENT))), walk, alerting = true))
        assertEquals(SummaryText(R.string.circle_walk_delivered, 1),
            walkCircleLineFor(on.copy(deliveries = listOf(delivery(DeliveryStatus.DELIVERED))), walk, alerting = true))
        assertEquals(SummaryText(R.string.circle_walk_all_clear), walkCircleLineFor(
            on.copy(deliveries = listOf(delivery(DeliveryStatus.SENT), delivery(DeliveryStatus.SENT, CircleMessageKind.ALL_CLEAR, t0.plusSeconds(60)))),
            walk, alerting = false
        ))
    }

    // ---- opt-in card ------------------------------------------------------------------------------

    @Test
    fun `the opt-in card status is honest about support, permission and contacts`() {
        assertEquals(R.string.auto_sms_status_not_supported, autoSmsStatusFor(CircleAlertState(availability = SmsAvailability.NOT_SUPPORTED)))
        assertEquals(R.string.auto_sms_status_off, autoSmsStatusFor(CircleAlertState(availability = SmsAvailability.AVAILABLE)))
        assertEquals(R.string.auto_sms_status_permission,
            autoSmsStatusFor(CircleAlertState(autoSms = true, availability = SmsAvailability.PERMISSION_NEEDED)))
        assertEquals(R.string.auto_sms_status_no_contacts,
            autoSmsStatusFor(CircleAlertState(autoSms = true, availability = SmsAvailability.AVAILABLE)))
        assertEquals(R.string.auto_sms_status_on,
            autoSmsStatusFor(CircleAlertState(autoSms = true, availability = SmsAvailability.AVAILABLE, recipients = recipients)))
    }

    @Test
    fun `turning the switch on asks for SEND_SMS first, and only a grant enables it`() = runTest {
        val controller = FakeCircleAlertController()
        val viewModel = AutoSmsViewModel(controller, flowOf("Asha"))
        controller.state.value = controller.state.value.copy(availability = SmsAvailability.PERMISSION_NEEDED)

        assertEquals(AutoSmsEnableAction.ASK_PERMISSION, viewModel.onSwitchOn())
        assertTrue("auto:true" !in controller.calls)
        viewModel.onPermissionResult(granted = false)
        assertTrue("auto:true" !in controller.calls)
        viewModel.onPermissionResult(granted = true)
        assertTrue("auto:true" in controller.calls)

        controller.state.value = controller.state.value.copy(availability = SmsAvailability.NOT_SUPPORTED, autoSms = false)
        assertEquals(AutoSmsEnableAction.NOT_SUPPORTED, viewModel.onSwitchOn())
        advanceUntilIdle()
        assertTrue(viewModel.ui.value.preview.contains("Asha started an emergency alert"))
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
            activeEmergencyStore = FakeActiveEmergencyStore(),
            currentUserId = { 7L },
            trackingServiceRunning = MutableStateFlow(false),
            circleAlerts = controller
        )
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is EmergencyUiState.Active)
        assertTrue("active:9" in controller.calls)

        viewModel.onCallEmergencyNumberClicked()
        assertTrue("dial:9" in controller.calls)
        assertEquals("text:SOS_STARTED", viewModel.composerTextFor())

        viewModel.onImSafeClicked()
        advanceUntilIdle()
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
