package com.safesphere.app.feature.auth

import com.safesphere.app.core.validation.FieldError
import com.safesphere.app.feature.auth.domain.AuthError
import com.safesphere.app.feature.auth.domain.PendingVerification
import com.safesphere.app.feature.auth.ui.LoginViewModel
import com.safesphere.app.testing.FakeAuthRepository
import com.safesphere.app.testing.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Phase N2: the number screen, the only way in (sign-in and sign-up look the same here). */
class LoginViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeAuthRepository()
    private val pending = PendingVerification()
    private val viewModel = LoginViewModel(repository, pendingVerification = pending)

    @Test
    fun `an empty number is required and the server is never asked`() = runTest {
        viewModel.onSendCodeClicked("  ")
        advanceUntilIdle()

        assertEquals(FieldError.REQUIRED, viewModel.uiState.value.phoneError)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(0, repository.startCalls)
    }

    @Test
    fun `a number that is not an Indian mobile is reported locally`() = runTest {
        viewModel.onSendCodeClicked("12345")
        advanceUntilIdle()

        assertEquals(FieldError.INVALID_PHONE, viewModel.uiState.value.phoneError)
        assertEquals(0, repository.startCalls)
    }

    @Test
    fun `a valid number is sent normalised and opens the code screen with the request in memory`() = runTest {
        viewModel.onSendCodeClicked("098765 43210")
        assertTrue(viewModel.uiState.value.isLoading)
        advanceUntilIdle()

        assertEquals("+919876543210", repository.lastStartPhone)
        assertTrue(viewModel.uiState.value.navigateToVerification)
        assertEquals(FakeAuthRepository.CHALLENGE, pending.challenge.value)
        assertNull(repository.currentUserNow()) // a code request signs nobody in
    }

    @Test
    fun `a number the server refuses is shown at the number field`() = runTest {
        repository.startResult = FakeAuthRepository.failure(AuthError.PHONE_INVALID)
        viewModel.onSendCodeClicked("9876543210")
        advanceUntilIdle()

        assertEquals(FieldError.INVALID_PHONE, viewModel.uiState.value.phoneError)
        assertNull(viewModel.uiState.value.authError)
        assertFalse(viewModel.uiState.value.navigateToVerification)
    }

    @Test
    fun `too many codes keeps the server's words, and the network failure its own`() = runTest {
        repository.startResult = FakeAuthRepository.failure(AuthError.OTP_WAIT, "You can ask for a new code in 40 seconds.")
        viewModel.onSendCodeClicked("9876543210")
        advanceUntilIdle()
        assertEquals(AuthError.OTP_WAIT, viewModel.uiState.value.authError)
        assertEquals("You can ask for a new code in 40 seconds.", viewModel.uiState.value.authMessage)

        repository.startResult = FakeAuthRepository.failure(AuthError.NO_CONNECTION)
        viewModel.onSendCodeClicked("9876543210")
        advanceUntilIdle()
        assertEquals(AuthError.NO_CONNECTION, viewModel.uiState.value.authError)
        assertNull(pending.challenge.value)
    }

    @Test
    fun `a second tap while loading does not send a second request`() = runTest {
        viewModel.onSendCodeClicked("9876543210")
        viewModel.onSendCodeClicked("9876543210")
        advanceUntilIdle()

        assertEquals(1, repository.startCalls)
    }

    @Test
    fun `navigation flag is cleared once handled`() = runTest {
        viewModel.onSendCodeClicked("9876543210")
        advanceUntilIdle()
        viewModel.onNavigationHandled()

        assertFalse(viewModel.uiState.value.navigateToVerification)
    }
}
