package com.safesphere.app.feature.auth

import com.safesphere.app.core.validation.FieldError
import com.safesphere.app.feature.auth.domain.AuthError
import com.safesphere.app.feature.auth.domain.PendingVerification
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
    private val pending = PendingVerification()

    /** "Today" is 22 October 2026 (UTC) in these tests. */
    private val clock = Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneOffset.UTC)
    private val viewModel = RegisterViewModel(repository, pending, clock)

    @Test
    fun `empty form reports every field and never calls the server`() = runTest {
        viewModel.onRegisterClicked("", "", "", "", "")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(FieldError.REQUIRED, state.fullNameError)
        assertEquals(FieldError.REQUIRED, state.emailError)
        assertEquals(FieldError.REQUIRED, state.phoneError)
        assertEquals(FieldError.REQUIRED, state.dateOfBirthError)
        assertEquals(FieldError.REQUIRED, state.passwordError)
        assertEquals(FieldError.REQUIRED, state.confirmPasswordError)
        assertEquals(0, repository.registerCalls)
    }

    @Test
    fun `short password, mismatched confirmation and a wrong number are reported locally`() = runTest {
        viewModel.onDateOfBirthChosen(LocalDate.of(1998, 5, 14))
        viewModel.onRegisterClicked("Asha Devi", "asha@example.com", "12345", "short", "different")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(FieldError.PASSWORD_TOO_SHORT, state.passwordError)
        assertEquals(FieldError.PASSWORDS_DO_NOT_MATCH, state.confirmPasswordError)
        assertEquals(FieldError.INVALID_PHONE, state.phoneError)
        assertNull(state.fullNameError)
        assertNull(state.dateOfBirthError)
        assertEquals(0, repository.registerCalls)
    }

    @Test
    fun `17B - a valid form registers with the normalised number and opens the code screen, signed out`() = runTest {
        viewModel.onDateOfBirthChosen(LocalDate.of(1998, 5, 14))
        viewModel.onRegisterClicked(" Asha Devi ", " asha@example.com ", "98765 43210", "longenough1", "longenough1")
        assertTrue(viewModel.uiState.value.isLoading)

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.navigateToVerification)
        assertEquals(FakeAuthRepository.CHALLENGE, pending.challenge.value)
        assertEquals("Asha Devi", repository.lastRegisterName)
        assertEquals("+919876543210", repository.lastRegisterPhone)
        assertEquals(LocalDate.of(1998, 5, 14), repository.lastRegisterDateOfBirth)
        assertNull(repository.currentUserNow()) // no session before the code
        assertNull(viewModel.uiState.value.dateOfBirth) // forgotten once the account exists
        assertFalse(viewModel.uiState.value.toString().contains("longenough1")) // the state holds no password

        viewModel.onNavigationHandled()
        assertFalse(viewModel.uiState.value.navigateToVerification)
    }

    @Test
    fun `17B - a number the server refuses is shown at the number field`() = runTest {
        repository.registerResult = FakeAuthRepository.failure(AuthError.PHONE_INVALID)
        viewModel.onDateOfBirthChosen(LocalDate.of(1998, 5, 14))
        viewModel.onRegisterClicked("Asha Devi", "asha@example.com", "9876543210", "longenough1", "longenough1")
        advanceUntilIdle()

        assertEquals(FieldError.INVALID_PHONE, viewModel.uiState.value.phoneError)
        assertNull(viewModel.uiState.value.authError)
        assertNull(pending.challenge.value)
    }

    @Test
    fun `17B - too many sign-ups keeps the server's words`() = runTest {
        repository.registerResult = FakeAuthRepository.failure(AuthError.OTP_WAIT, "Too many codes asked for.")
        viewModel.onDateOfBirthChosen(LocalDate.of(1998, 5, 14))
        viewModel.onRegisterClicked("Asha Devi", "asha@example.com", "9876543210", "longenough1", "longenough1")
        advanceUntilIdle()

        assertEquals(AuthError.OTP_WAIT, viewModel.uiState.value.authError)
        assertEquals("Too many codes asked for.", viewModel.uiState.value.authMessage)
    }

    @Test
    fun `under 18 is refused on the phone and the server is never asked`() = runTest {
        viewModel.onDateOfBirthChosen(LocalDate.of(2008, 10, 23)) // 18 tomorrow
        assertEquals(FieldError.UNDER_AGE, viewModel.uiState.value.dateOfBirthError)

        viewModel.onRegisterClicked("Asha Devi", "asha@example.com", "9876543210", "longenough1", "longenough1")
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

        viewModel.onRegisterClicked("Asha Devi", "asha@example.com", "9876543210", "longenough1", "longenough1")
        advanceUntilIdle()

        assertEquals(AuthError.EMAIL_ALREADY_USED, viewModel.uiState.value.authError)
        assertFalse(viewModel.uiState.value.navigateToVerification)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(LocalDate.of(1998, 5, 14), viewModel.uiState.value.dateOfBirth)
    }
}
