package com.safesphere.app.feature.auth

import com.safesphere.app.core.validation.FieldError
import com.safesphere.app.feature.auth.domain.AuthError
import com.safesphere.app.feature.auth.ui.RegisterViewModel
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

class RegisterViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeAuthRepository()
    private val viewModel = RegisterViewModel(repository)

    @Test
    fun `empty form reports every field and never calls the server`() = runTest {
        viewModel.onRegisterClicked("", "", "", "")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(FieldError.REQUIRED, state.fullNameError)
        assertEquals(FieldError.REQUIRED, state.emailError)
        assertEquals(FieldError.REQUIRED, state.passwordError)
        assertEquals(FieldError.REQUIRED, state.confirmPasswordError)
        assertEquals(0, repository.registerCalls)
    }

    @Test
    fun `short password and mismatched confirmation are reported locally`() = runTest {
        viewModel.onRegisterClicked("Asha Devi", "asha@example.com", "short", "different")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(FieldError.PASSWORD_TOO_SHORT, state.passwordError)
        assertEquals(FieldError.PASSWORDS_DO_NOT_MATCH, state.confirmPasswordError)
        assertNull(state.fullNameError)
        assertEquals(0, repository.registerCalls)
    }

    @Test
    fun `valid form registers with trimmed values and navigates home`() = runTest {
        viewModel.onRegisterClicked(" Asha Devi ", " asha@example.com ", "longenough1", "longenough1")
        assertTrue(viewModel.uiState.value.isLoading)

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.navigateToHome)
        assertEquals("Asha Devi", repository.lastRegisterName)
        assertEquals(1, repository.registerCalls)
    }

    @Test
    fun `email already in use surfaces as an error`() = runTest {
        repository.registerResult = FakeAuthRepository.failure(AuthError.EMAIL_ALREADY_USED)

        viewModel.onRegisterClicked("Asha Devi", "asha@example.com", "longenough1", "longenough1")
        advanceUntilIdle()

        assertEquals(AuthError.EMAIL_ALREADY_USED, viewModel.uiState.value.authError)
        assertFalse(viewModel.uiState.value.navigateToHome)
        assertFalse(viewModel.uiState.value.isLoading)
    }
}
