package com.safesphere.app.feature.auth

import com.safesphere.app.core.validation.FieldError
import com.safesphere.app.feature.auth.domain.AuthError
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
