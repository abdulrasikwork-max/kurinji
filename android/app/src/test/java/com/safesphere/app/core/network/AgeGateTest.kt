package com.safesphere.app.core.network

import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.social.ui.ProfileSetupViewModel
import com.safesphere.app.testing.FakeSessionStorage
import com.safesphere.app.testing.FakeSocialRepository
import com.safesphere.app.testing.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/** Phase 17A: the one age state (R2 in docs/PHASE17_PLAN.md). */
class AgeGateTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val gate = AgeGate()
    private val handler = ApiCallHandler(
        SessionEnder(FakeSessionStorage(AuthSession("jwt", AuthUser(1, "Asha", "asha@example.com")))), gate)

    private fun conflict(body: String) = HttpException(Response.error<Any>(409, body.toResponseBody(null)))

    @Test
    fun `a refusal with the age code sets the state and keeps the server's words for the screen`() = runTest {
        val result = handler.execute<Unit> {
            throw conflict("""{"status":409,"message":"Confirm your age to use this","code":"AGE_NOT_CONFIRMED"}""")
        }

        assertEquals(ApiResult.Failure(ApiError.CONFLICT, "Confirm your age to use this", "AGE_NOT_CONFIRMED"), result)
        assertTrue(gate.required.value)
    }

    @Test
    fun `any other conflict leaves the age state alone`() = runTest {
        handler.execute<Unit> { throw conflict("""{"status":409,"message":"Handle taken"}""") }

        assertFalse(gate.required.value)
    }

    @Test
    fun `the age screen is opened by the app at most once per sign-in`() {
        assertFalse(gate.claimPrompt()) // nothing required yet

        gate.onRefused()
        assertTrue(gate.claimPrompt())
        gate.onRefused() // every further refused call
        assertFalse(gate.claimPrompt())

        gate.reset() // sign-out or another account
        gate.onRefused()
        assertTrue(gate.claimPrompt())
    }

    @Test
    fun `confirming on the age screen clears the state and says so once`() = runTest {
        val repository = FakeSocialRepository().apply { ageConfirmedResult = ApiResult.Success(false) }
        val clock = Clock.fixed(Instant.parse("2026-10-27T06:00:00Z"), ZoneOffset.UTC)
        val vm = ProfileSetupViewModel(repository, clock, gate)
        advanceUntilIdle()
        assertTrue(gate.required.value)

        vm.onDateOfBirthChosen(LocalDate.of(1990, 1, 1))
        vm.onConfirmAge()
        advanceUntilIdle()

        assertFalse(gate.required.value)
        assertTrue(vm.uiState.value.ageJustConfirmed)
        vm.onAgeConfirmedShown()
        assertFalse(vm.uiState.value.ageJustConfirmed)
    }
}
