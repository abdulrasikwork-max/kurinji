package com.safesphere.app.feature.auth

import com.safesphere.app.core.validation.FieldError
import com.safesphere.app.feature.auth.domain.AuthError
import com.safesphere.app.feature.auth.ui.LoginViewModel
import com.safesphere.app.feature.auth.ui.ResendStatus
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

class LoginViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeAuthRepository()
    private val viewModel = LoginViewModel(repository)

    @Test
    fun `empty form shows required errors and never calls the server`() = runTest {
        viewModel.onLoginClicked(email = "", password = "")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(FieldError.REQUIRED, state.emailError)
        assertEquals(FieldError.REQUIRED, state.passwordError)
        assertFalse(state.isLoading)
        assertEquals(0, repository.loginCalls)
    }

    @Test
    fun `invalid email is reported locally`() = runTest {
        viewModel.onLoginClicked(email = "not-an-email", password = "secret")
        advanceUntilIdle()

        assertEquals(FieldError.INVALID_EMAIL, viewModel.uiState.value.emailError)
        assertEquals(0, repository.loginCalls)
    }

    @Test
    fun `valid form shows loading, then navigates home on success`() = runTest {
        viewModel.onLoginClicked(email = " user@example.com ", password = "anything")
        assertTrue(viewModel.uiState.value.isLoading)

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.navigateToHome)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals("user@example.com", repository.lastLoginEmail)
    }

    @Test
    fun `an unverified email does not sign in and offers to send the email again`() = runTest {
        repository.loginResult = FakeAuthRepository.failure(AuthError.EMAIL_NOT_VERIFIED)
        viewModel.onLoginClicked(email = "asha@example.com", password = "right-password")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(AuthError.EMAIL_NOT_VERIFIED, state.authError)
        assertFalse(state.navigateToHome)
        assertTrue(state.canResendVerification)

        viewModel.onResendVerificationClicked(" asha@example.com ", "right-password")
        advanceUntilIdle()
        assertEquals(ResendStatus.Sent, viewModel.uiState.value.resend)
        assertEquals("asha@example.com", repository.lastResendEmail)
        assertEquals(AuthError.EMAIL_NOT_VERIFIED, viewModel.uiState.value.authError) // still not signed in
    }

    @Test
    fun `resend is not offered or sent after other errors`() = runTest {
        repository.loginResult = FakeAuthRepository.failure(AuthError.INVALID_CREDENTIALS)
        viewModel.onLoginClicked(email = "asha@example.com", password = "wrong")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canResendVerification)
        viewModel.onResendVerificationClicked("asha@example.com", "wrong")
        advanceUntilIdle()
        assertEquals(0, repository.resendCalls)
    }

    @Test
    fun `a failed resend shows why`() = runTest {
        repository.loginResult = FakeAuthRepository.failure(AuthError.EMAIL_NOT_VERIFIED)
        repository.resendResult = FakeAuthRepository.failure(AuthError.NO_CONNECTION)
        viewModel.onLoginClicked(email = "asha@example.com", password = "right-password")
        advanceUntilIdle()
        viewModel.onResendVerificationClicked("asha@example.com", "right-password")
        advanceUntilIdle()
        assertEquals(ResendStatus.Failed(AuthError.NO_CONNECTION), viewModel.uiState.value.resend)
    }

    @Test
    fun `wrong credentials surface as an error and do not navigate`() = runTest {
        repository.loginResult = FakeAuthRepository.failure(AuthError.INVALID_CREDENTIALS)

        viewModel.onLoginClicked(email = "user@example.com", password = "wrong")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(AuthError.INVALID_CREDENTIALS, state.authError)
        assertFalse(state.navigateToHome)
        assertFalse(state.isLoading)
    }

    @Test
    fun `network failure surfaces as NO_CONNECTION`() = runTest {
        repository.loginResult = FakeAuthRepository.failure(AuthError.NO_CONNECTION)

        viewModel.onLoginClicked(email = "user@example.com", password = "anything")
        advanceUntilIdle()

        assertEquals(AuthError.NO_CONNECTION, viewModel.uiState.value.authError)
    }

    @Test
    fun `a second tap while loading does not send a second request`() = runTest {
        viewModel.onLoginClicked(email = "user@example.com", password = "anything")
        viewModel.onLoginClicked(email = "user@example.com", password = "anything")
        advanceUntilIdle()

        assertEquals(1, repository.loginCalls)
    }

    @Test
    fun `navigation flag is cleared once handled`() = runTest {
        viewModel.onLoginClicked(email = "user@example.com", password = "anything")
        advanceUntilIdle()
        viewModel.onNavigationHandled()

        assertFalse(viewModel.uiState.value.navigateToHome)
        assertNull(viewModel.uiState.value.authError)
    }
}
