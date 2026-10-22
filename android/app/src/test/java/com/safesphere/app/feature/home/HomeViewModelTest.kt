package com.safesphere.app.feature.home

import com.safesphere.app.core.escalation.EscalationLevel
import com.safesphere.app.feature.auth.domain.AuthError
import com.safesphere.app.feature.emergency.domain.ActiveEmergencyRecord
import com.safesphere.app.feature.emergency.location.LocationSharingState
import com.safesphere.app.feature.emergency.location.LocationSharingStatus
import com.safesphere.app.feature.home.ui.ActiveSessionBanner
import com.safesphere.app.feature.home.ui.HomeStatus
import com.safesphere.app.feature.home.ui.HomeStatusCard
import com.safesphere.app.feature.home.ui.HomeViewModel
import com.safesphere.app.feature.safewalk.domain.CheckInPhase
import com.safesphere.app.feature.safewalk.domain.CheckInStatus
import com.safesphere.app.feature.safewalk.domain.Journey
import com.safesphere.app.feature.safewalk.domain.JourneyStatus
import com.safesphere.app.feature.safewalk.session.SafeWalkState
import com.safesphere.app.testing.FakeActiveEmergencyStore
import com.safesphere.app.testing.FakeAuthRepository
import com.safesphere.app.testing.FakeLocationSharingController
import com.safesphere.app.testing.FakeSafeWalkController
import com.safesphere.app.testing.MainDispatcherRule
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val safeWalk = FakeSafeWalkController()
    private val sharing = FakeLocationSharingController()
    private val emergencyStore = FakeActiveEmergencyStore()

    private fun viewModel(repository: FakeAuthRepository = FakeAuthRepository(FakeAuthRepository.DEFAULT_USER)) =
        HomeViewModel(repository, safeWalk, sharing, emergencyStore)

    @Test
    fun `asks the server for the profile once when it starts`() = runTest {
        val repository = FakeAuthRepository(FakeAuthRepository.DEFAULT_USER)
        val viewModel = viewModel(repository)

        advanceUntilIdle()

        assertEquals(1, repository.refreshCalls)
        assertFalse(viewModel.sessionExpired.value)
    }

    @Test
    fun `C1 - a rejected token only gives the dialog once the session is really gone`() = runTest {
        // "Sign in to sync": the session (and the person) are still here, so Home shows its calm banner instead.
        val stillThere = FakeAuthRepository(FakeAuthRepository.DEFAULT_USER).apply {
            refreshResult = FakeAuthRepository.failure(AuthError.UNAUTHORIZED)
        }
        val calm = viewModel(stillThere)
        advanceUntilIdle()
        assertFalse(calm.sessionExpired.value)

        // Signed out (password changed elsewhere, account deleted): the dialog, as before.
        val gone = FakeAuthRepository(null).apply { refreshResult = FakeAuthRepository.failure(AuthError.UNAUTHORIZED) }
        val viewModel = viewModel(gone)
        advanceUntilIdle()
        assertTrue(viewModel.sessionExpired.value)
        viewModel.onSessionExpiredHandled()
        assertFalse(viewModel.sessionExpired.value)
    }

    @Test
    fun `being offline does not expire the session`() = runTest {
        val repository = FakeAuthRepository(FakeAuthRepository.DEFAULT_USER).apply {
            refreshResult = FakeAuthRepository.failure(AuthError.NO_CONNECTION)
        }
        val viewModel = viewModel(repository)

        advanceUntilIdle()

        assertFalse(viewModel.sessionExpired.value)
    }

    // ---- Phase 4B: active-session banner ---------------------------------------------------------

    @Test
    fun `no banner when nothing is running, and an active walk is recovered on Home`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertNull(viewModel.activeSession.value)
        assertTrue("recover" in safeWalk.calls)
    }

    @Test
    fun `an active walk shows the walk banner, with its check-in phase`() = runTest {
        val viewModel = viewModel()
        safeWalk.state.value = SafeWalkState(journey = journey, checkIn = CheckInStatus(CheckInPhase.MISSED, EscalationLevel.CHECK_IN_MISSED, due, null))
        advanceUntilIdle()

        assertEquals(ActiveSessionBanner.SafeWalk(CheckInPhase.MISSED), viewModel.activeSession.value)
    }

    @Test
    fun `an emergency wins over a walk`() = runTest {
        val viewModel = viewModel()
        safeWalk.state.value = SafeWalkState(journey = journey)
        sharing.state.value = LocationSharingState(emergencyId = 3, status = LocationSharingStatus.AVAILABLE)
        advanceUntilIdle()

        assertEquals(ActiveSessionBanner.Emergency, viewModel.activeSession.value)
    }

    @Test
    fun `after a restart, the emergency remembered on this phone is shown - but only for the same user`() = runTest {
        emergencyStore.record = ActiveEmergencyRecord(FakeAuthRepository.DEFAULT_USER.id, 3, due)
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(ActiveSessionBanner.Emergency, viewModel.activeSession.value)

        emergencyStore.record = ActiveEmergencyRecord(userId = 999, emergencyId = 3, startedAt = due)
        viewModel.onScreenVisible()
        advanceUntilIdle()
        assertNull(viewModel.activeSession.value)
    }

    // ---- Status card: only facts this phone knows, never "safe" -------------------------------------

    @Test
    fun `the card says it is still checking until the phone has looked, and only then that nothing is running`() = runTest {
        safeWalk.recoverGate = CompletableDeferred() // a slow server look-up
        val viewModel = viewModel()
        assertEquals(HomeStatus.CHECKING, viewModel.status.value)
        advanceUntilIdle()
        assertEquals(HomeStatus.CHECKING, viewModel.status.value)

        safeWalk.recoverGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(HomeStatus.NOTHING_RUNNING, viewModel.status.value)
    }

    @Test
    fun `a running walk is shown at once, even while the phone is still checking`() = runTest {
        safeWalk.recoverGate = CompletableDeferred()
        val viewModel = viewModel()
        safeWalk.state.value = SafeWalkState(journey = journey)
        advanceUntilIdle()

        assertEquals(HomeStatus.SAFE_WALK, viewModel.status.value)
        safeWalk.recoverGate?.complete(Unit)
    }

    @Test
    fun `a check-in due soon is still a running walk, a missed or unanswered one is the missed status`() = runTest {
        val viewModel = viewModel()
        val cases = listOf(
            CheckInStatus(CheckInPhase.ON_TRACK, EscalationLevel.NORMAL, due, null) to HomeStatus.SAFE_WALK,
            CheckInStatus(CheckInPhase.DUE_SOON, EscalationLevel.NORMAL, due, null) to HomeStatus.SAFE_WALK,
            CheckInStatus(CheckInPhase.MISSED, EscalationLevel.CHECK_IN_MISSED, due, null) to HomeStatus.CHECK_IN_MISSED,
            CheckInStatus(CheckInPhase.NO_RESPONSE, EscalationLevel.NO_RESPONSE, due, null) to HomeStatus.CHECK_IN_MISSED
        )
        for ((checkIn, expected) in cases) {
            safeWalk.state.value = SafeWalkState(journey = journey, checkIn = checkIn)
            advanceUntilIdle()
            assertEquals("${checkIn.phase}", expected, viewModel.status.value)
        }

        safeWalk.state.value = SafeWalkState(journey = journey, endedStatus = JourneyStatus.COMPLETED)
        advanceUntilIdle()
        assertEquals(HomeStatus.NOTHING_RUNNING, viewModel.status.value)
    }

    @Test
    fun `an emergency hides the card, also when it is only remembered on this phone`() = runTest {
        emergencyStore.record = ActiveEmergencyRecord(FakeAuthRepository.DEFAULT_USER.id, 3, due)
        val viewModel = viewModel()
        safeWalk.state.value = SafeWalkState(journey = journey)
        advanceUntilIdle()

        assertEquals(HomeStatus.EMERGENCY, viewModel.status.value)
        assertNull(HomeStatusCard.contentFor(viewModel.status.value))
        assertEquals(ActiveSessionBanner.Emergency, viewModel.activeSession.value)
    }

    @Test
    fun `a live emergency on this phone also hides the card`() = runTest {
        val viewModel = viewModel()
        sharing.state.value = LocationSharingState(emergencyId = 3, status = LocationSharingStatus.AVAILABLE)
        advanceUntilIdle()

        assertEquals(HomeStatus.EMERGENCY, viewModel.status.value)
    }

    private val due = Instant.parse("2026-10-20T18:10:00Z")
    private val journey = Journey(1, JourneyStatus.ACTIVE, null, 10, EscalationLevel.NORMAL, due, null, due, due)
}
