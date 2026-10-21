package com.safesphere.app.feature.account

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.SessionEnder
import com.safesphere.app.feature.account.data.RemoteAccountRepository
import com.safesphere.app.feature.account.data.remote.AccountApi
import com.safesphere.app.feature.account.data.remote.ChangePasswordRequestDto
import com.safesphere.app.feature.account.data.remote.DeletionScheduledDto
import com.safesphere.app.feature.account.data.remote.PasswordConfirmationDto
import com.safesphere.app.feature.account.domain.TokenRenewalPolicy
import com.safesphere.app.feature.account.session.SessionRenewer
import com.safesphere.app.feature.auth.data.remote.AuthResponseDto
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.emergency.domain.EmergencyStatus
import com.safesphere.app.feature.emergency.ui.EmergencyUiState
import com.safesphere.app.feature.emergency.ui.EmergencyViewModel
import com.safesphere.app.feature.emergency.ui.EndAction
import com.safesphere.app.testing.FakeActiveEmergencyStore
import com.safesphere.app.testing.FakeAuthApi
import com.safesphere.app.testing.FakeEmergencyRepository
import com.safesphere.app.testing.FakeEmergencyRepository.Companion.event
import com.safesphere.app.testing.FakeLocationSharingController
import com.safesphere.app.testing.FakeSafetyCircleRepository
import com.safesphere.app.testing.FakeSessionStorage
import com.safesphere.app.testing.MainDispatcherRule
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase 10A.4 "session continuity" on the phone: when the token is renewed (above all during an SOS or Safe Walk), that a
 * renewal never revives a replaced session, and the SOS screen's password prompt when the SOS was started elsewhere.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionContinuityTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val base = Instant.parse("2026-10-22T10:00:00Z")
    private val user = AuthUser(7, "Asha", "asha@example.com")

    // ---- TokenRenewalPolicy ------------------------------------------------------------------------------------

    @Test
    fun `the expiry is read from the token, and renewal is due with less than 12 hours left`() {
        assertEquals(base.plusSeconds(3_600), TokenRenewalPolicy.expiresAt(jwt(base.plusSeconds(3_600))))
        assertNull(TokenRenewalPolicy.expiresAt("not-a-jwt"))
        assertNull(TokenRenewalPolicy.expiresAt("a.%%%.c"))

        assertFalse(TokenRenewalPolicy.due(base.plus(Duration.ofHours(13)), base))
        assertFalse(TokenRenewalPolicy.due(base.plus(Duration.ofHours(12)), base))
        assertTrue(TokenRenewalPolicy.due(base.plus(Duration.ofHours(11)), base))
        assertFalse("an expired token cannot be renewed anyway", TokenRenewalPolicy.due(base.minusSeconds(1), base))
        assertFalse("unreadable: never renew in a loop", TokenRenewalPolicy.due(null, base))
    }

    // ---- SessionRenewer ----------------------------------------------------------------------------------------

    @Test
    fun `during an SOS the token is checked every 10 minutes and renewed before it runs out`() = runTest {
        val token = MutableStateFlow<String?>(jwt(base.plus(Duration.ofHours(12)).plusSeconds(30 * 60)))
        var renewals = 0
        SessionRenewer(backgroundScope, token, MutableStateFlow(true), { renewals++; ApiResult.Success(Unit) }) {
            base.plusMillis(testScheduler.currentTime)
        }
        runCurrent()
        assertEquals(0, renewals) // 12 h 30 min left
        advanceTimeBy(20 * 60_000L + 1)
        runCurrent()
        assertEquals(0, renewals) // 12 h 10 min left at the 20-minute look
        advanceTimeBy(20 * 60_000L)
        runCurrent()
        assertEquals(1, renewals) // exactly 12 h at the 30-minute look (not yet), 11 h 50 min at the 40-minute look
    }

    @Test
    fun `without an SOS or walk it looks hourly, and a new token restarts the clock`() = runTest {
        val token = MutableStateFlow<String?>(jwt(base.plus(Duration.ofHours(12)).plusSeconds(30 * 60)))
        var renewals = 0
        SessionRenewer(backgroundScope, token, MutableStateFlow(false), {
            renewals++
            token.value = jwt(base.plusMillis(testScheduler.currentTime).plus(Duration.ofHours(24)))
            ApiResult.Success(Unit)
        }) { base.plusMillis(testScheduler.currentTime) }
        runCurrent()
        advanceTimeBy(30 * 60_000L)
        runCurrent()
        assertEquals(0, renewals) // not looked yet
        advanceTimeBy(30 * 60_000L + 1)
        runCurrent()
        assertEquals(1, renewals) // the hourly look found 11 h 30 min left
        advanceTimeBy(3 * 60 * 60_000L)
        runCurrent()
        assertEquals(1, renewals) // the fresh token has 21 h left
        token.value = null
        runCurrent()
    }

    @Test
    fun `an SOS starting makes the renewer look at once`() = runTest {
        val token = MutableStateFlow<String?>(jwt(base.plus(Duration.ofHours(2))))
        val sos = MutableStateFlow(false)
        var renewals = 0
        SessionRenewer(backgroundScope, token, sos, { renewals++; ApiResult.Failure(ApiError.NO_CONNECTION) }) {
            base.plusMillis(testScheduler.currentTime)
        }
        runCurrent()
        assertEquals(1, renewals) // 2 h left: due at once; offline, so it failed
        sos.value = true
        runCurrent()
        assertEquals(2, renewals) // the SOS restarted the look immediately
        advanceTimeBy(10 * 60_000L + 1)
        runCurrent()
        assertEquals(3, renewals) // and keeps trying every 10 minutes while offline
    }

    // ---- RemoteAccountRepository.renewToken ------------------------------------------------------------------

    @Test
    fun `a renewal replaces the token, but never a session that changed meanwhile`() = runTest {
        val storage = FakeSessionStorage(AuthSession("old", user))
        val api = RenewApi { FakeAuthApi.authResponse("renewed") }
        val ender = SessionEnder(storage)
        val repository = RemoteAccountRepository(api, ApiCallHandler(ender), storage, ender)

        assertEquals(ApiResult.Success(Unit), repository.renewToken())
        assertEquals("renewed", storage.savedSession?.token)

        // A sign-out (or a password change) happened while the renewal was on its way: it must win.
        val raced = RenewApi {
            storage.clear()
            FakeAuthApi.authResponse("late")
        }
        storage.save(AuthSession("current", user))
        RemoteAccountRepository(raced, ApiCallHandler(ender), storage, ender).renewToken()
        assertNull(storage.savedSession)
    }

    // ---- the SOS screen: started on another phone ------------------------------------------------------------

    @Test
    fun `ending an SOS started elsewhere asks for the password, then sends it once`() = runTest {
        val emergencies = FakeEmergencyRepository().apply {
            historyResult = ApiResult.Success(listOf(event(id = 5)))
            resolveResult = ApiResult.Failure(ApiError.FORBIDDEN, "This SOS was started on another phone.")
        }
        val viewModel = viewModel(emergencies, FakeLocationSharingController())
        advanceUntilIdle()

        viewModel.onImSafeClicked()
        advanceUntilIdle()
        val asking = viewModel.uiState.value as EmergencyUiState.Active
        assertEquals(EndAction.RESOLVE, asking.passwordNeededFor)
        assertNull(asking.failure)
        assertNull(emergencies.lastEndPassword)

        viewModel.onEndPasswordDismissed()
        assertNull((viewModel.uiState.value as EmergencyUiState.Active).passwordNeededFor) // the SOS stays active

        viewModel.onImSafeClicked()
        advanceUntilIdle()
        // A wrong password: shown as a failure, NOT asked again in a loop.
        emergencies.resolveResult = ApiResult.Failure(ApiError.CONFLICT, "Password is incorrect")
        viewModel.onEndWithPassword("wrong")
        advanceUntilIdle()
        val wrong = viewModel.uiState.value as EmergencyUiState.Active
        assertEquals("wrong", emergencies.lastEndPassword)
        assertEquals(ApiError.CONFLICT, wrong.failure?.error)
        assertNull(wrong.passwordNeededFor)

        emergencies.resolveResult = ApiResult.Success(event(id = 5, status = EmergencyStatus.RESOLVED).copy(endedElsewhere = true))
        viewModel.onImSafeClicked()
        advanceUntilIdle()
        viewModel.onEndWithPassword("right")
        advanceUntilIdle()
        assertTrue((viewModel.uiState.value as EmergencyUiState.Ended).event.endedElsewhere)
    }

    @Test
    fun `the starting phone notices when its SOS was ended elsewhere, and stops sharing its location`() = runTest {
        val emergencies = FakeEmergencyRepository().apply { historyResult = ApiResult.Success(listOf(event(id = 5))) }
        val sharing = FakeLocationSharingController()
        val viewModel = viewModel(emergencies, sharing)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is EmergencyUiState.Active)

        emergencies.historyResult = ApiResult.Success(
            listOf(event(id = 5, status = EmergencyStatus.RESOLVED).copy(endedElsewhere = true)))
        viewModel.onScreenVisible()
        advanceUntilIdle()

        val ended = viewModel.uiState.value as EmergencyUiState.Ended
        assertTrue(ended.event.endedElsewhere)
        assertEquals(1, sharing.stopCalls)
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private fun viewModel(emergencies: FakeEmergencyRepository, sharing: FakeLocationSharingController) =
        EmergencyViewModel(
            emergencyRepository = emergencies,
            safetyCircleRepository = FakeSafetyCircleRepository(emptyList()),
            locationSharing = sharing,
            activeEmergencyStore = FakeActiveEmergencyStore(),
            currentUserId = { 7L },
            trackingServiceRunning = MutableStateFlow(false)
        )

    /** An unsigned token with only the claims the phone reads (the phone never verifies signatures). */
    private fun jwt(expiresAt: Instant): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"HS256"}""".toByteArray())
        val payload = encoder.encodeToString("""{"sub":"7","exp":${expiresAt.epochSecond}}""".toByteArray())
        return "$header.$payload.signature"
    }

    private class RenewApi(val outcome: suspend () -> AuthResponseDto) : AccountApi {
        override suspend fun changePassword(body: ChangePasswordRequestDto): AuthResponseDto = error("unused")
        override suspend fun requestDeletion(body: PasswordConfirmationDto): DeletionScheduledDto = error("unused")
        override suspend fun cancelDeletion(body: PasswordConfirmationDto) = Unit
        override suspend fun renewToken(): AuthResponseDto = outcome()
    }
}
