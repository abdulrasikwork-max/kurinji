package com.safesphere.app.feature.auth

import com.safesphere.app.core.validation.FieldError
import com.safesphere.app.feature.auth.domain.AuthError
import com.safesphere.app.feature.auth.ui.RegisterViewModel
import com.safesphere.app.testing.FakeAuthRepository
import com.safesphere.app.testing.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
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

    /** "Today" is 22 October 2026 (UTC) in these tests. */
    private val clock = Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneOffset.UTC)
    private val viewModel = RegisterViewModel(repository, clock)

    @Test
    fun `empty form reports every field and never calls the server`() = runTest {
        viewModel.onRegisterClicked("", "", "", "")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(FieldError.REQUIRED, state.fullNameError)
        assertEquals(FieldError.REQUIRED, state.emailError)
        assertEquals(FieldError.REQUIRED, state.dateOfBirthError)
        assertEquals(FieldError.REQUIRED, state.passwordError)
        assertEquals(FieldError.REQUIRED, state.confirmPasswordError)
        assertEquals(0, repository.registerCalls)
    }

    @Test
    fun `short password and mismatched confirmation are reported locally`() = runTest {
        viewModel.onDateOfBirthChosen(LocalDate.of(1998, 5, 14))
        viewModel.onRegisterClicked("Asha Devi", "asha@example.com", "short", "different")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(FieldError.PASSWORD_TOO_SHORT, state.passwordError)
        assertEquals(FieldError.PASSWORDS_DO_NOT_MATCH, state.confirmPasswordError)
        assertNull(state.fullNameError)
        assertNull(state.dateOfBirthError)
        assertEquals(0, repository.registerCalls)
    }

    @Test
    fun `valid form registers with trimmed values and the date of birth and navigates home`() = runTest {
        viewModel.onDateOfBirthChosen(LocalDate.of(1998, 5, 14))
        viewModel.onRegisterClicked(" Asha Devi ", " asha@example.com ", "longenough1", "longenough1")
        assertTrue(viewModel.uiState.value.isLoading)

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.navigateToHome)
        assertEquals("Asha Devi", repository.lastRegisterName)
        assertEquals(LocalDate.of(1998, 5, 14), repository.lastRegisterDateOfBirth)
        assertEquals(1, repository.registerCalls)
        assertNull(viewModel.uiState.value.dateOfBirth) // forgotten once the account exists
    }

    @Test
    fun `under 18 is refused on the phone and the server is never asked`() = runTest {
        viewModel.onDateOfBirthChosen(LocalDate.of(2008, 10, 23)) // 18 tomorrow
        assertEquals(FieldError.UNDER_AGE, viewModel.uiState.value.dateOfBirthError)

        viewModel.onRegisterClicked("Asha Devi", "asha@example.com", "longenough1", "longenough1")
        advanceUntilIdle()

        assertEquals(FieldError.UNDER_AGE, viewModel.uiState.value.dateOfBirthError)
        assertEquals(0, repository.registerCalls)
    }

    @Test
    fun `an 18th birthday today is accepted and a future date is invalid`() = runTest {
        viewModel.onDateOfBirthChosen(LocalDate.of(2008, 10, 22))
        assertNull(viewModel.uiState.value.dateOfBirthError)
        viewModel.onDateOfBirthChosen(LocalDate.of(2026, 10, 23))
        assertEquals(FieldError.INVALID_DATE_OF_BIRTH, viewModel.uiState.value.dateOfBirthError)
    }

    @Test
    fun `email already in use surfaces as an error and keeps the chosen date`() = runTest {
        repository.registerResult = FakeAuthRepository.failure(AuthError.EMAIL_ALREADY_USED)
        viewModel.onDateOfBirthChosen(LocalDate.of(1998, 5, 14))

        viewModel.onRegisterClicked("Asha Devi", "asha@example.com", "longenough1", "longenough1")
        advanceUntilIdle()

        assertEquals(AuthError.EMAIL_ALREADY_USED, viewModel.uiState.value.authError)
        assertFalse(viewModel.uiState.value.navigateToHome)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(LocalDate.of(1998, 5, 14), viewModel.uiState.value.dateOfBirth)
    }
}
