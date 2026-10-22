package com.safesphere.app.feature.emergency

import com.safesphere.app.core.location.LocationPermissionStatus
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.emergency.domain.ActiveEmergencyRecord
import com.safesphere.app.feature.emergency.domain.EmergencyIds
import com.safesphere.app.feature.emergency.domain.EmergencyStatus
import com.safesphere.app.feature.emergency.session.SosRecorder
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
import com.safesphere.app.testing.settle
import com.safesphere.app.testing.sosRecorderForTest
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
import org.junit.Rule
import org.junit.Test

/** The emergency state machine, driven with virtual time (no real waiting). Phase 11: with the real [SosRecorder]. */
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
    private val dialPrompts = mutableListOf<Long>()
    private var recorder: SosRecorder? = null

    private fun TestScope.sos(): SosRecorder = recorder ?: sosRecorderForTest(
        emergencies, store, USER_ID, rebinders = listOf(sharing), dialPrompts = dialPrompts
    ).also { recorder = it }

    private fun TestScope.viewModel() = EmergencyViewModel(
        emergencyRepository = emergencies,
        safetyCircleRepository = circle,
        locationSharing = sharing,
        sos = sos(),
        currentUserId = { signedInUserId },
        trackingServiceRunning = MutableStateFlow(false),
        onEmergencyStarted = { emergencyStartedCalls++ }
    )

    private var emergencyStartedCalls = 0

    @Test
    fun `starting an SOS tells the Safe Walk to stop, restoring one does not`() = runTest {
        emergencies.historyResult = ApiResult.Success(listOf(event(id = 9)))
        viewModel()
        settle()
        assertEquals(0, emergencyStartedCalls) // only restored

        emergencies.historyResult = ApiResult.Success(emptyList())
        val fresh = idleViewModel()
        fresh.onStartSosClicked()
        settle()
        assertEquals(1, emergencyStartedCalls)
    }

    private fun TestScope.idleViewModel(): EmergencyViewModel {
        val viewModel = viewModel()
        settle()
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
        settle()

        val state = viewModel.uiState.value as EmergencyUiState.Active
        assertEquals(listOf("Mum"), state.circle.map { it.name })
    }

    @Test
    fun `finished emergencies in the history do not reopen the active screen`() = runTest {
        emergencies.historyResult = ApiResult.Success(listOf(event(status = EmergencyStatus.RESOLVED)))

        val viewModel = viewModel()
        settle()

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
        settle()

        val active = viewModel.uiState.value as EmergencyUiState.Active
        assertEquals(EmergencyStatus.ACTIVE, active.event.status)
        assertFalse(active.notOnServerYet)
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
        settle()

        assertEquals(EmergencyUiState.Idle, viewModel.uiState.value)
        assertEquals(0, emergencies.startCalls)
        assertNull(store.record)
    }

    @Test
    fun `tapping Start twice does not run two countdowns`() = runTest {
        val viewModel = idleViewModel()

        viewModel.onStartSosClicked()
        viewModel.onStartSosClicked()
        settle()

        assertEquals(1, emergencies.startCalls)
    }

    // ---- Phase 11: SOS without internet --------------------------------------------------------

    @Test
    fun `without a connection the SOS still starts on this phone and is recorded once the server answers`() = runTest {
        emergencies.startResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        val viewModel = idleViewModel()
        viewModel.onStartSosClicked()
        advanceTimeBy(5_000)
        settle()

        val local = viewModel.uiState.value as EmergencyUiState.Active
        assertTrue(local.notOnServerYet)
        assertTrue(EmergencyIds.isLocal(local.event.id))
        assertEquals(listOf(local.event.id), sharing.started) // location is collected at once
        assertEquals(false, store.record?.onServer)            // remembered on the phone (a restart keeps it)
        assertEquals(1, emergencies.startCalls)

        // The phone keeps trying (backoff); later attempts carry the phone's start time and the SAME reference.
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(2, emergencies.startCalls)
        assertNotNull(emergencies.recordRequests.last().startedAt)
        assertEquals(1, emergencies.recordRequests.map { it.clientRef }.toSet().size)

        emergencies.startResult = ApiResult.Success(event(id = 31))
        advanceTimeBy(10_001)
        settle()

        val recorded = viewModel.uiState.value as EmergencyUiState.Active
        assertEquals(31L, recorded.event.id)
        assertFalse(recorded.notOnServerYet)
        assertEquals(listOf(local.event.id to 31L), sharing.rebinds)
        assertEquals(true, store.record?.onServer)
        assertEquals(31L, store.record?.emergencyId)
    }

    @Test
    fun `if the server records the SOS while the contact list is still loading, the screen shows the recorded SOS`() = runTest {
        // Found on the emulator: the recorder's answer came before the Active state existed and was missed.
        circle.contactsDelayMillis = 2_000
        emergencies.startResult = ApiResult.Success(event(id = 77))
        val viewModel = idleViewModel()
        viewModel.onStartSosClicked()
        advanceTimeBy(5_000)
        settle()
        advanceTimeBy(2_001)
        settle()

        val active = viewModel.uiState.value as EmergencyUiState.Active
        assertEquals(77L, active.event.id)
        assertFalse(active.notOnServerYet)
    }

    @Test
    fun `I'm safe before the server has the SOS ends it on this phone and records it later, already ended`() = runTest {
        emergencies.startResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        val viewModel = idleViewModel()
        viewModel.onStartSosClicked()
        advanceTimeBy(5_000)
        settle()
        val stopsBefore = sharing.stopCalls

        viewModel.onImSafeClicked()
        settle()

        val ended = viewModel.uiState.value as EmergencyUiState.Ended
        assertEquals(EmergencyStatus.RESOLVED, ended.event.status)
        assertNotNull(ended.unsent)
        assertEquals(0, emergencies.resolveCalls) // nothing to resolve on the server yet
        assertEquals(stopsBefore + 1, sharing.stopCalls)
        assertNull(store.record)
        assertEquals(1, store.unsent.size)

        emergencies.startResult = ApiResult.Success(event(id = 40, status = EmergencyStatus.RESOLVED))
        advanceTimeBy(60_001)
        settle()

        val request = emergencies.recordRequests.last()
        assertNotNull(request.startedAt)
        assertEquals(true, request.ended?.resolved)
        assertNull((viewModel.uiState.value as EmergencyUiState.Ended).unsent)
        assertTrue(store.unsent.isEmpty())
    }

    @Test
    fun `without a connection I'm safe on a recorded SOS ends it here and tells the server later with its time`() = runTest {
        emergencies.historyResult = ApiResult.Success(listOf(event()))
        emergencies.resolveResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        val viewModel = viewModel()
        settle()

        viewModel.onImSafeClicked()
        settle()
        val ended = viewModel.uiState.value as EmergencyUiState.Ended
        assertEquals(EmergencyStatus.RESOLVED, ended.event.status)
        assertNotNull(ended.unsent)
        assertEquals(1, sharing.stopCalls)
        val endedAt = ended.unsent?.ended?.at

        emergencies.resolveResult = ApiResult.Success(event(status = EmergencyStatus.RESOLVED))
        advanceTimeBy(60_001)
        settle()

        assertTrue(emergencies.resolveCalls >= 2)
        assertEquals(endedAt, emergencies.lastEndedAt)
        assertNull((viewModel.uiState.value as EmergencyUiState.Ended).unsent)
        assertTrue(store.unsent.isEmpty())
    }

    @Test
    fun `an SOS started offline is shown again after a restart, without asking the server first`() = runTest {
        emergencies.startResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        emergencies.historyResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        val ref = "00000000-0000-4000-8000-000000000099"
        store.record = ActiveEmergencyRecord(USER_ID, EmergencyIds.localIdFor(ref), Instant.parse("2026-10-15T09:00:00Z"),
            clientRef = ref, onServer = false)

        val viewModel = viewModel()
        settle()

        val state = viewModel.uiState.value as EmergencyUiState.Active
        assertTrue(state.notOnServerYet)
        assertEquals(EmergencyIds.localIdFor(ref), state.event.id)
        assertEquals(ref, emergencies.recordRequests.single().clientRef) // the same SOS, not a new one
    }

    @Test
    fun `Call 112 before the server has the SOS is recorded once it has`() = runTest {
        emergencies.startResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        val viewModel = idleViewModel()
        viewModel.onStartSosClicked()
        advanceTimeBy(5_000)
        settle()

        viewModel.onCallEmergencyNumberClicked()
        settle()
        assertTrue(dialPrompts.isEmpty())

        emergencies.startResult = ApiResult.Success(event(id = 61))
        advanceTimeBy(10_001)
        settle()
        assertEquals(listOf(61L), dialPrompts)
    }

    @Test
    fun `if the person is signed out when the countdown ends, nothing starts and the session is reported`() = runTest {
        val viewModel = idleViewModel()
        signedInUserId = null
        viewModel.onStartSosClicked()
        advanceTimeBy(5_000)
        settle()
        assertTrue(viewModel.uiState.value is EmergencyUiState.StartFailed)
        assertTrue(viewModel.sessionExpired.value)
        assertEquals(0, emergencies.startCalls)

        viewModel.onDismissStartFailureClicked()
        assertEquals(EmergencyUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `if the server says one is already active, that one is shown`() = runTest {
        val viewModel = idleViewModel()
        emergencies.startResult = ApiResult.Failure(ApiError.CONFLICT, "An emergency is already active")
        emergencies.historyResult = ApiResult.Success(listOf(event(id = 55, status = EmergencyStatus.ACTIVE)))

        viewModel.onStartSosClicked()
        advanceTimeBy(5_000)
        settle()

        val active = viewModel.uiState.value as EmergencyUiState.Active
        assertEquals(55L, active.event.id)
    }

    @Test
    fun `I'm safe ends the emergency as RESOLVED`() = runTest {
        emergencies.historyResult = ApiResult.Success(listOf(event()))
        val viewModel = viewModel()
        settle()

        viewModel.onImSafeClicked()
        settle()

        val ended = viewModel.uiState.value as EmergencyUiState.Ended
        assertEquals(EmergencyStatus.RESOLVED, ended.event.status)
        assertNull(ended.unsent)
        assertEquals(1, emergencies.resolveCalls)
        assertEquals(0, emergencies.cancelCalls)
    }

    @Test
    fun `False alarm ends the emergency as CANCELLED`() = runTest {
        emergencies.historyResult = ApiResult.Success(listOf(event()))
        val viewModel = viewModel()
        settle()

        viewModel.onFalseAlarmClicked()
        settle()

        val ended = viewModel.uiState.value as EmergencyUiState.Ended
        assertEquals(EmergencyStatus.CANCELLED, ended.event.status)
        assertEquals(1, emergencies.cancelCalls)
    }

    @Test
    fun `if the server refuses to end it, the emergency stays active and the problem is shown`() = runTest {
        emergencies.historyResult = ApiResult.Success(listOf(event()))
        emergencies.resolveResult = ApiResult.Failure(ApiError.CONFLICT, "This emergency has already ended")
        val viewModel = viewModel()
        settle()

        viewModel.onImSafeClicked()
        settle()

        val state = viewModel.uiState.value as EmergencyUiState.Active
        assertFalse(state.isEnding)
        assertNotNull(state.failure)
        assertEquals(ApiError.CONFLICT, state.failure?.error)
    }

    @Test
    fun `an expired session is reported`() = runTest {
        emergencies.historyResult = ApiResult.Failure(ApiError.UNAUTHORIZED)

        val viewModel = viewModel()
        settle()

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
        advanceTimeBy(5_000)
        settle()

        val localId = sharing.started.single()
        assertTrue(EmergencyIds.isLocal(localId))
        assertEquals(listOf(localId to 21L), sharing.rebinds)
        assertEquals(21L, store.record?.emergencyId)
        assertEquals(USER_ID, store.record?.userId)
        assertEquals(event().startedAt, store.record?.startedAt)
        assertEquals(true, store.record?.onServer)
    }

    @Test
    fun `cancelling the countdown starts no location saving`() = runTest {
        val viewModel = idleViewModel()
        viewModel.onStartSosClicked()
        runCurrent()
        viewModel.onCancelCountdownClicked()
        settle()

        assertTrue(sharing.started.isEmpty())
        assertNull(store.record)
    }

    @Test
    fun `ending the emergency stops location saving and forgets the record on the phone`() = runTest {
        emergencies.historyResult = ApiResult.Success(listOf(event()))
        val viewModel = viewModel()
        settle()
        assertNotNull(store.record)

        viewModel.onImSafeClicked()
        settle()

        assertEquals(1, sharing.stopCalls)
        assertNull(store.record)
        assertTrue(store.unsent.isEmpty())
    }

    @Test
    fun `if ending is refused, location saving continues`() = runTest {
        emergencies.historyResult = ApiResult.Success(listOf(event()))
        emergencies.cancelResult = ApiResult.Failure(ApiError.CONFLICT, "This emergency has already ended")
        val viewModel = viewModel()
        settle()

        viewModel.onFalseAlarmClicked()
        settle()

        assertEquals(0, sharing.stopCalls)
        assertNotNull(store.record)
    }

    @Test
    fun `emergency recoverable after restart - shown again from the server and location saving resumes`() = runTest {
        emergencies.historyResult = ApiResult.Success(listOf(event(id = 33)))

        val viewModel = viewModel()
        settle()

        val state = viewModel.uiState.value as EmergencyUiState.Active
        assertFalse(state.restoredOffline)
        assertEquals(listOf(33L), sharing.started)
    }

    @Test
    fun `emergency recoverable after restart while offline - shown from the record on the phone`() = runTest {
        emergencies.historyResult = ApiResult.Failure(ApiError.NO_CONNECTION)
        store.record = ActiveEmergencyRecord(USER_ID, 44, Instant.parse("2026-10-15T09:00:00Z"))

        val viewModel = viewModel()
        settle()

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
        settle()

        assertEquals(EmergencyUiState.Idle, viewModel.uiState.value)
        assertTrue(sharing.started.isEmpty())
    }

    @Test
    fun `when the server says nothing is active, a stale record is dropped and sharing stops`() = runTest {
        store.record = ActiveEmergencyRecord(USER_ID, 44, Instant.parse("2026-10-15T09:00:00Z"))
        emergencies.historyResult = ApiResult.Success(listOf(event(id = 44, status = EmergencyStatus.RESOLVED)))

        val viewModel = viewModel()
        settle()

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
        settle()

        viewModel.onLocationPermissionResult(LocationPermissionStatus.PERMANENTLY_DENIED)
        settle()
        assertTrue(viewModel.locationUi.value.permissionBlocked)
        assertEquals(0, sharing.refreshCalls)

        viewModel.onLocationPermissionResult(LocationPermissionStatus.GRANTED_APPROXIMATE)
        settle()
        assertFalse(viewModel.locationUi.value.permissionBlocked)
        assertEquals(1, sharing.refreshCalls)
    }

    @Test
    fun `a 401 while saving locations is reported as an expired session`() = runTest {
        emergencies.historyResult = ApiResult.Success(listOf(event()))
        val viewModel = viewModel()
        settle()

        sharing.state.value = sharing.state.value.copy(signedOut = true)
        settle()

        assertTrue(viewModel.sessionExpired.value)
    }

    private companion object {
        const val USER_ID = 7L
    }
}
