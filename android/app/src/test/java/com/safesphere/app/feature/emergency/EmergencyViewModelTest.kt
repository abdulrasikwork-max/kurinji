package com.safesphere.app.feature.emergency

import com.safesphere.app.core.location.LocationPermissionStatus
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.emergency.domain.ActiveEmergencyRecord
import com.safesphere.app.feature.emergency.domain.EmergencyStatus
import com.safesphere.app.feature.emergency.ui.EmergencyUiState
import com.safesphere.app.feature.emergency.ui.EmergencyViewModel
import com.safesphere.app.testing.FakeActiveEmergencyStore
import com.safesphere.app.testing.FakeEmergencyRepository
import com.safesphere.app.testing.FakeLocationSharingController
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import com.safesphere.app.testing.FakeEmergencyRepository.Companion.event
import com.safesphere.app.testing.FakeSafetyCircleRepository
import com.safesphere.app.testing.FakeSafetyCircleRepository.Companion.contact
import com.safesphere.app.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The emergency state machine, driven with virtual time (no real waiting). */
@OptIn(ExperimentalCoroutinesApi::class)
class EmergencyViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val emergencies = FakeEmergencyRepository()
    private val circle = FakeSafetyCircleRepository(
        listOf(contact(1, "Mum", primary = true), contact(2, "Off", phone = "+911111111111", enabled = false))
    )

    private val sharing = FakeLocationSharingController()
    private val store = FakeActiveEmergencyStore()
    private var signedInUserId: Long? = USER_ID

    private fun viewModel() = EmergencyViewModel(
        emergencyRepository = emergencies,
        safetyCircleRepository = circle,
        locationSharing = sharing,
        activeEmergencyStore = store,
        currentUserId = { signedInUserId },
        trackingServiceRunning = MutableStateFlow(false),
        onEmergencyStarted = { emergencyStartedCalls++ }
    )

    private var emergencyStartedCalls = 0

    @Test
    fun `starting an SOS tells the Safe Walk to stop, restoring one does not`() = runTest {
        emergencies.historyResult = ApiResult.Success(listOf(event(id = 9)))
        viewModel()
        advanceUntilIdle()
        assertEquals(0, emergencyStartedCalls) // only restored

        emergencies.historyResult = ApiResult.Success(emptyList())
        val fresh = idleViewModel()
        fresh.onStartSosClicked()
        advanceUntilIdle()
        assertEquals(1, emergencyStartedCalls)
    }

    private suspend fun TestScope.idleViewModel(): EmergencyViewModel {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(EmergencyUiState.Idle, viewModel.uiState.value)
        return viewModel
    }

    /** Moves virtual time forward one second and lets the countdown run. */
    private fun TestScope.tickOneSecond() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    @Test
    fun `starts Idle when no emergency is active`() = runTest {
        idleViewModel()
    }

    @Test
    fun `an emergency that is still active on the server is shown again, with enabled contacts only`() = runTest {
        emergencies.historyResult = ApiResult.Success(listOf(event(status = EmergencyStatus.ACTIVE)))

        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value as EmergencyUiState.Active
        assertEquals(listOf("Mum"), state.circle.map { it.name })
    }

    @Test
    fun `finished emergencies in the history do not reopen the active screen`() = runTest {
        emergencies.historyResult = ApiResult.Success(listOf(event(status = EmergencyStatus.RESOLVED)))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(EmergencyUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `the countdown shows 5 4 3 2 1 and then the emergency becomes ACTIVE`() = runTest {
        val viewModel = idleViewModel()

        viewModel.onStartSosClicked()
        runCurrent()
        assertEquals(EmergencyUiState.Countdown(5), viewModel.uiState.value)
        for (expected in listOf(4, 3, 2, 1)) {
            tickOneSecond()
            assertEquals(EmergencyUiState.Countdown(expected), viewModel.uiState.value)
        }
        assertEquals(0, emergencies.startCalls)           // nothing is recorded during the countdown

        tickOneSecond()
        advanceUntilIdle()

        val active = viewModel.uiState.value as EmergencyUiState.Active
        assertEquals(EmergencyStatus.ACTIVE, active.event.status)
        assertEquals(1, emergencies.startCalls)
    }

    @Test
    fun `cancelling during the countdown returns to Idle and records nothing`() = runTest {
        val viewModel = idleViewModel()
        viewModel.onStartSosClicked()
        runCurrent()
        tickOneSecond()
        assertEquals(EmergencyUiState.Countdown(4), viewModel.uiState.value)

        viewModel.onCancelCountdownClicked()
        advanceTimeBy(10_000)
        advanceUntilIdle()

        assertEquals(EmergencyUiState.Idle, viewModel.uiState.value)
        assertEquals(0, emergencies.startCalls)
    }

    @Test
    fun `tapping Start twice does not run two countdowns`() = runTest {
        val viewModel = idleViewModel()

        viewModel.onStartSosClicked()
        viewModel.onStartSosClicked()
        advanceUntilIdle()

        assertEquals(1, emergencies.startCalls)
    }

    @Test
    fun `if recording fails the person can retry`() = runTest {
        emergencies.startResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        val viewModel = idleViewModel()
        viewModel.onStartSosClicked()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is EmergencyUiState.StartFailed)

        emergencies.startResult = ApiResult.Success(event())
        viewModel.onRetryStartClicked()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is EmergencyUiState.Active)
        assertEquals(2, emergencies.startCalls)
    }

    @Test
    fun `a failed start can be dismissed back to Idle`() = runTest {
        emergencies.startResult = ApiResult.Failure(ApiError.SERVER)
        val viewModel = idleViewModel()
        viewModel.onStartSosClicked()
        advanceUntilIdle()

        viewModel.onDismissStartFailureClicked()

        assertEquals(EmergencyUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `if the server says one is already active, that one is shown`() = runTest {
        val viewModel = idleViewModel()
        emergencies.startResult = ApiResult.Failure(ApiError.CONFLICT, "An emergency is already active")
        emergencies.historyResult = ApiResult.Success(listOf(event(id = 55, status = EmergencyStatus.ACTIVE)))

        viewModel.onStartSosClicked()
        advanceUntilIdle()

        val active = viewModel.uiState.value as EmergencyUiState.Active
        assertEquals(55L, active.event.id)
    }

    @Test
    fun `I'm safe ends the emergency as RESOLVED`() = runTest {
        emergencies.historyResult = ApiResult.Success(listOf(event()))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onImSafeClicked()
        advanceUntilIdle()

        val ended = viewModel.uiState.value as EmergencyUiState.Ended
        assertEquals(EmergencyStatus.RESOLVED, ended.event.status)
        assertEquals(1, emergencies.resolveCalls)
        assertEquals(0, emergencies.cancelCalls)
    }

    @Test
    fun `False alarm ends the emergency as CANCELLED`() = runTest {
        emergencies.historyResult = ApiResult.Success(listOf(event()))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onFalseAlarmClicked()
        advanceUntilIdle()

        val ended = viewModel.uiState.value as EmergencyUiState.Ended
        assertEquals(EmergencyStatus.CANCELLED, ended.event.status)
        assertEquals(1, emergencies.cancelCalls)
    }

    @Test
    fun `if ending fails the emergency stays active and the problem is shown`() = runTest {
        emergencies.historyResult = ApiResult.Success(listOf(event()))
        emergencies.resolveResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onImSafeClicked()
        advanceUntilIdle()

        val state = viewModel.uiState.value as EmergencyUiState.Active
        assertFalse(state.isEnding)
        assertNotNull(state.failure)
        assertEquals(ApiError.NO_CONNECTION, state.failure?.error)
    }

    @Test
    fun `an expired session is reported`() = runTest {
        emergencies.historyResult = ApiResult.Failure(ApiError.UNAUTHORIZED)

        val viewModel = viewModel()
        advanceUntilIdle()

        assertTrue(viewModel.sessionExpired.value)
        viewModel.onSessionExpiredHandled()
        assertFalse(viewModel.sessionExpired.value)
    }

    // ---- Phase 4A: location association and recovery ------------------------------------------

    @Test
    fun `when an emergency becomes active, location saving starts for it and it is remembered on the phone`() = runTest {
        val viewModel = idleViewModel()
        emergencies.startResult = ApiResult.Success(event(id = 21))

        viewModel.onStartSosClicked()
        advanceUntilIdle()

        assertEquals(listOf(21L), sharing.started)
        assertEquals(ActiveEmergencyRecord(USER_ID, 21, event().startedAt), store.record)
    }

    @Test
    fun `cancelling the countdown starts no location saving`() = runTest {
        val viewModel = idleViewModel()
        viewModel.onStartSosClicked()
        runCurrent()
        viewModel.onCancelCountdownClicked()
        advanceUntilIdle()

        assertTrue(sharing.started.isEmpty())
        assertNull(store.record)
    }

    @Test
    fun `ending the emergency stops location saving and forgets the record on the phone`() = runTest {
        emergencies.historyResult = ApiResult.Success(listOf(event()))
        val viewModel = viewModel()
        advanceUntilIdle()
        assertNotNull(store.record)

        viewModel.onImSafeClicked()
        advanceUntilIdle()

        assertEquals(1, sharing.stopCalls)
        assertNull(store.record)
    }

    @Test
    fun `if ending fails, location saving continues`() = runTest {
        emergencies.historyResult = ApiResult.Success(listOf(event()))
        emergencies.cancelResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onFalseAlarmClicked()
        advanceUntilIdle()

        assertEquals(0, sharing.stopCalls)
        assertNotNull(store.record)
    }

    @Test
    fun `emergency recoverable after restart - shown again from the server and location saving resumes`() = runTest {
        emergencies.historyResult = ApiResult.Success(listOf(event(id = 33)))

        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value as EmergencyUiState.Active
        assertFalse(state.restoredOffline)
        assertEquals(listOf(33L), sharing.started)
    }

    @Test
    fun `emergency recoverable after restart while offline - shown from the record on the phone`() = runTest {
        emergencies.historyResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        store.record = ActiveEmergencyRecord(USER_ID, 44, Instant.parse("2026-10-15T09:00:00Z"))

        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value as EmergencyUiState.Active
        assertTrue(state.restoredOffline)
        assertEquals(44L, state.event.id)
        assertEquals(listOf(44L), sharing.started)
    }

    @Test
    fun `another user's record on the same phone is never shown`() = runTest {
        emergencies.historyResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        store.record = ActiveEmergencyRecord(userId = 999, emergencyId = 44, startedAt = Instant.parse("2026-10-15T09:00:00Z"))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(EmergencyUiState.Idle, viewModel.uiState.value)
        assertTrue(sharing.started.isEmpty())
    }

    @Test
    fun `when the server says nothing is active, a stale record is dropped and sharing stops`() = runTest {
        store.record = ActiveEmergencyRecord(USER_ID, 44, Instant.parse("2026-10-15T09:00:00Z"))
        emergencies.historyResult = ApiResult.Success(listOf(event(id = 44, status = EmergencyStatus.RESOLVED)))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(EmergencyUiState.Idle, viewModel.uiState.value)
        assertNull(store.record)
        assertEquals(1, sharing.stopCalls)
    }

    @Test
    fun `the location permission is asked automatically only once`() = runTest {
        val viewModel = idleViewModel()

        assertTrue(viewModel.consumeAutoLocationRequest())
        assertFalse(viewModel.consumeAutoLocationRequest())
        assertTrue(viewModel.consumeNotificationPrompt())
        assertFalse(viewModel.consumeNotificationPrompt())
    }

    @Test
    fun `location permission denied permanently is shown, and granting it refreshes the sharing`() = runTest {
        emergencies.historyResult = ApiResult.Success(listOf(event()))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onLocationPermissionResult(LocationPermissionStatus.PERMANENTLY_DENIED)
        advanceUntilIdle()
        assertTrue(viewModel.locationUi.value.permissionBlocked)
        assertEquals(0, sharing.refreshCalls)

        viewModel.onLocationPermissionResult(LocationPermissionStatus.GRANTED_APPROXIMATE)
        advanceUntilIdle()
        assertFalse(viewModel.locationUi.value.permissionBlocked)
        assertEquals(1, sharing.refreshCalls)
    }

    @Test
    fun `a 401 while saving locations is reported as an expired session`() = runTest {
        emergencies.historyResult = ApiResult.Success(listOf(event()))
        val viewModel = viewModel()
        advanceUntilIdle()

        sharing.state.value = sharing.state.value.copy(signedOut = true)
        advanceUntilIdle()

        assertTrue(viewModel.sessionExpired.value)
    }

    private companion object {
        const val USER_ID = 7L
    }
}
