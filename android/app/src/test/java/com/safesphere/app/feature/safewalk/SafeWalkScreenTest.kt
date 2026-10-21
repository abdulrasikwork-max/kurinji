package com.safesphere.app.feature.safewalk

import com.safesphere.app.core.device.BatteryLevel
import com.safesphere.app.core.device.BatteryStatus
import com.safesphere.app.core.escalation.EscalationLevel
import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.feature.safewalk.domain.ArrivalState
import com.safesphere.app.feature.safewalk.domain.CheckInPhase
import com.safesphere.app.feature.safewalk.domain.CheckInStatus
import com.safesphere.app.feature.safewalk.domain.GpsQuality
import com.safesphere.app.feature.safewalk.domain.MovementState
import com.safesphere.app.feature.safewalk.session.DestinationSearch
import com.safesphere.app.feature.safewalk.ui.DistanceText
import com.safesphere.app.feature.safewalk.ui.MovementLine
import com.safesphere.app.feature.safewalk.ui.distanceText
import com.safesphere.app.feature.safewalk.ui.movementLinesFor
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.tracking.CompositeTrackingNoticeSource
import com.safesphere.app.core.tracking.TrackingNotice
import com.safesphere.app.core.tracking.TrackingNoticeSource
import com.safesphere.app.feature.safewalk.domain.Journey
import com.safesphere.app.feature.safewalk.domain.JourneyStatus
import com.safesphere.app.feature.safewalk.session.SafeWalkState
import com.safesphere.app.feature.safewalk.ui.SafeWalkUiState
import com.safesphere.app.feature.safewalk.ui.SafeWalkViewModel
import com.safesphere.app.feature.safewalk.ui.WalkHint
import com.safesphere.app.feature.safewalk.ui.formatCountdown
import com.safesphere.app.feature.safewalk.ui.walkHintsFor
import com.safesphere.app.testing.FakeSafeWalkController
import com.safesphere.app.testing.MainDispatcherRule
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The Safe Walk screen's ViewModel, its pure text helpers, and the shared tracking notice. */
@OptIn(ExperimentalCoroutinesApi::class)
class SafeWalkScreenTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val controller = FakeSafeWalkController()
    private fun viewModel(serviceRunning: Boolean = true) = SafeWalkViewModel(controller, MutableStateFlow(serviceRunning))

    private val journey = Journey(
        1, JourneyStatus.ACTIVE, "Home", 10, EscalationLevel.NORMAL,
        Instant.parse("2026-10-20T18:00:00Z"), null, Instant.parse("2026-10-20T18:10:00Z"), Instant.parse("2026-10-20T18:00:00Z")
    )
    private val active = SafeWalkState(journey = journey, localDueAt = journey.nextCheckInDueAt, access = LocationAccess.PRECISE)

    @Test
    fun `the screen recovers first, then offers the setup`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals("recover", controller.calls.first())
        assertEquals(SafeWalkUiState.Setup(), viewModel.uiState.value)
    }

    @Test
    fun `an active walk is shown, and an ended one shows its result once`() = runTest {
        controller.state.value = active
        val viewModel = viewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is SafeWalkUiState.Active)

        controller.state.value = active.copy(endedStatus = JourneyStatus.COMPLETED)
        advanceUntilIdle()
        assertEquals(SafeWalkUiState.Ended(JourneyStatus.COMPLETED), viewModel.uiState.value)

        viewModel.onDoneClicked()
        assertTrue("acknowledge" in controller.calls)
    }

    @Test
    fun `a refused start shows the server's reason and can be retried`() = runTest {
        controller.startResult = ApiResult.Failure(ApiError.CONFLICT, "An emergency is active. End it before starting a Safe Walk")
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onStartClicked("Home", 10)
        advanceUntilIdle()

        val setup = viewModel.uiState.value as SafeWalkUiState.Setup
        assertFalse(setup.isStarting)
        assertEquals("An emergency is active. End it before starting a Safe Walk", setup.failure?.message)
    }

    @Test
    fun `starting twice quickly starts one walk`() = runTest {
        controller.startResult = ApiResult.Success(journey)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onStartClicked(null, 5)
        viewModel.onStartClicked(null, 5)
        advanceUntilIdle()

        assertEquals(1, controller.calls.count { it.startsWith("start") })
    }

    @Test
    fun `the permission prompts are offered once each`() = runTest {
        val viewModel = viewModel()
        assertTrue(viewModel.consumeLocationPrompt())
        assertFalse(viewModel.consumeLocationPrompt())
        assertTrue(viewModel.consumeNotificationPrompt())
        assertFalse(viewModel.consumeNotificationPrompt())
    }

    @Test
    fun `an expired session is reported`() = runTest {
        controller.state.value = SafeWalkState(signedOut = true)
        val viewModel = viewModel()
        advanceUntilIdle()

        assertTrue(viewModel.sessionExpired.value)
    }

    // ---- hints and countdown ---------------------------------------------------------------------

    @Test
    fun `hints explain missing location, notifications, the service and offline sync honestly`() {
        assertEquals(emptyList<WalkHint>(), walkHintsFor(active, serviceRunning = true, notificationsGranted = true))

        val hints = walkHintsFor(
            active.copy(access = LocationAccess.NONE, pendingSync = 2, isOnline = false, restoredOffline = true),
            serviceRunning = false, notificationsGranted = false
        )
        assertEquals(
            listOf(WalkHint.LOCATION_NOT_SHARED, WalkHint.KEEP_APP_OPEN, WalkHint.NOTIFICATIONS_OFF,
                WalkHint.OFFLINE_PENDING, WalkHint.RESTORED_OFFLINE),
            hints
        )
        assertTrue(walkHintsFor(SafeWalkState(), serviceRunning = false, notificationsGranted = false).isEmpty())
    }

    // ---- Phase 4C: movement lines -----------------------------------------------------------------

    @Test
    fun `movement lines say honestly what the stop and route checks are doing`() {
        val withRoute = active.copy(
            movementChecks = true,
            journey = journey.copy(destination = GeoPoint(12.98, 77.59)),
            destinationDescription = "Station Road",
            movement = MovementState(metersToDestination = 1_437.0)
        )
        assertEquals(
            listOf(MovementLine.Destination("Station Road"), MovementLine.RouteOn(DistanceText(true, 1.4))),
            movementLinesFor(withRoute, GpsQuality.GOOD)
        )
        assertEquals(
            listOf(MovementLine.StopsOnly, MovementLine.WeakGps),
            movementLinesFor(active.copy(movementChecks = true), GpsQuality.WEAK)
        )
        assertEquals(
            listOf(MovementLine.DestinationNotFound, MovementLine.WaitingForGps),
            movementLinesFor(active.copy(movementChecks = true, destinationSearch = DestinationSearch.NOT_FOUND), GpsQuality.WAITING)
        )
        assertEquals(listOf(MovementLine.Off), movementLinesFor(active, GpsQuality.GOOD))
        assertEquals(
            listOf(MovementLine.NeedsLocation),
            movementLinesFor(active.copy(movementChecks = true, access = LocationAccess.NONE), GpsQuality.WAITING)
        )
        assertTrue(movementLinesFor(SafeWalkState(), GpsQuality.GOOD).isEmpty())
    }

    @Test
    fun `battery lines appear when low or critical`() {
        assertEquals(
            listOf(MovementLine.Off, MovementLine.BatteryLow(18)),
            movementLinesFor(active.copy(battery = BatteryStatus(18, false, BatteryLevel.LOW)), GpsQuality.GOOD)
        )
        assertEquals(
            MovementLine.BatteryCritical(7),
            movementLinesFor(active.copy(battery = BatteryStatus(7, false, BatteryLevel.CRITICAL)), GpsQuality.GOOD).last()
        )
    }

    @Test
    fun `distances - metres rounded to 10 below 1 km, then kilometres with one decimal`() {
        assertEquals(DistanceText(false, 350.0), distanceText(347.0))
        assertEquals(DistanceText(false, 990.0), distanceText(994.0))
        assertEquals(DistanceText(true, 1.0), distanceText(1_000.0))
        assertEquals(DistanceText(true, 12.3), distanceText(12_345.0))
    }

    @Test
    fun `the arrival hint shows only while nothing else needs an answer`() {
        val arrived = active.copy(
            movementChecks = true,
            movement = MovementState(arrival = ArrivalState(2, arrived = true)),
            checkIn = CheckInStatus(CheckInPhase.ON_TRACK, EscalationLevel.NORMAL, journey.nextCheckInDueAt!!, null)
        )
        assertTrue(arrived.looksArrived)
        assertFalse(arrived.copy(checkIn = arrived.checkIn!!.copy(phase = CheckInPhase.MISSED)).looksArrived)
        assertFalse(arrived.copy(movementChecks = false).looksArrived)
    }

    @Test
    fun `start passes the movement checks choice, and the toggle flips it`() = runTest {
        controller.startResult = ApiResult.Success(journey)
        controller.state.value = active.copy(movementChecks = true)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onMovementChecksToggled()
        assertTrue("movementChecks:false" in controller.calls)

        controller.state.value = SafeWalkState()
        advanceUntilIdle()
        viewModel.onStartClicked("Station", 10, movementChecks = false)
        advanceUntilIdle()
        assertTrue("start:10:false" in controller.calls)
    }

    @Test
    fun `countdown text, including overdue`() {
        assertEquals("07:42", formatCountdown(462))
        assertEquals("00:00", formatCountdown(0))
        assertEquals("+01:05", formatCountdown(-65))
    }

    // ---- one service, two sources -----------------------------------------------------------------

    @Test
    fun `the tracking service shows the emergency first, then the walk, and stops when both are gone`() = runTest {
        val emergency = FakeNoticeSource()
        val walk = FakeNoticeSource()
        val composite = CompositeTrackingNoticeSource(listOf(emergency, walk))
        val walkNotice = TrackingNotice(1, 2)
        val emergencyNotice = TrackingNotice(3, 4)

        assertNull(composite.currentNotice())
        walk.flow.value = walkNotice
        assertEquals(walkNotice, composite.currentNotice())
        assertEquals(walkNotice, composite.notices.first())
        emergency.flow.value = emergencyNotice
        assertEquals(emergencyNotice, composite.currentNotice())
        assertEquals(emergencyNotice, composite.notices.first())
        emergency.flow.value = null
        walk.flow.value = null
        assertNull(composite.notices.first())
    }

    private class FakeNoticeSource : TrackingNoticeSource {
        val flow = MutableStateFlow<TrackingNotice?>(null)
        override fun currentNotice() = flow.value
        override val notices: Flow<TrackingNotice?> = flow
    }
}
