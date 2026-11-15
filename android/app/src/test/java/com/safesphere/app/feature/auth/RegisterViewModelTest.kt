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

/** Phase N2: "About you", the real name and an adult date of birth for a number that has no account yet. */
class RegisterViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeAuthRepository()
    private val pending = PendingVerification().apply { signUpWith(FakeAuthRepository.TICKET) }

    /** "Today" is 22 October 2026 (UTC) in these tests. */
    private val clock = Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneOffset.UTC)
    private val viewModel = RegisterViewModel(repository, pending, clock)

    @Test
    fun `an empty form reports both fields and never calls the server`() = runTest {
        viewModel.onCreateClicked("   ")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(FieldError.REQUIRED, state.fullNameError)
        assertEquals(FieldError.REQUIRED, state.dateOfBirthError)
        assertEquals(0, repository.signUpCalls)
    }

    @Test
    fun `the real-name rule is checked on the phone`() = runTest {
        viewModel.onDateOfBirthChosen(LocalDate.of(2000, 1, 15))
        viewModel.onCreateClicked("K4vya")
        assertEquals(FieldError.NOT_A_NAME, viewModel.uiState.value.fullNameError)
        viewModel.onCreateClicked("K")
        assertEquals(FieldError.NAME_TOO_SHORT, viewModel.uiState.value.fullNameError)
        viewModel.onCreateClicked("a".repeat(61))
        assertEquals(FieldError.NAME_TOO_LONG, viewModel.uiState.value.fullNameError)
        advanceUntilIdle()
        assertEquals(0, repository.signUpCalls)
    }

    @Test
    fun `a valid form signs up with the ticket and the cleaned name, then opens the profile`() = runTest {
        viewModel.onDateOfBirthChosen(LocalDate.of(2000, 1, 15))
        viewModel.onCreateClicked("  கவியா   ராமன் ")
        assertTrue(viewModel.uiState.value.isLoading)
        advanceUntilIdle()

        assertEquals(FakeAuthRepository.TICKET, repository.lastSignUpTicket)
        assertEquals("கவியா ராமன்", repository.lastSignUpName)
        assertEquals(LocalDate.of(2000, 1, 15), repository.lastSignUpDateOfBirth)
        assertTrue(viewModel.uiState.value.navigateToProfile)
        assertNull(viewModel.uiState.value.dateOfBirth) // forgotten once it was sent
        assertNull(pending.ticket.value)
        assertEquals(FakeAuthRepository.DEFAULT_USER, repository.currentUserNow())
    }

    @Test
    fun `under 18 is refused on the phone and the server is never asked`() = runTest {
        viewModel.onDateOfBirthChosen(LocalDate.of(2008, 10, 23)) // 18 tomorrow
        assertEquals(FieldError.UNDER_AGE, viewModel.uiState.value.dateOfBirthError)

        viewModel.onCreateClicked("Asha Devi")
        advanceUntilIdle()

        assertEquals(FieldError.UNDER_AGE, viewModel.uiState.value.dateOfBirthError)
        assertEquals(0, repository.signUpCalls)
    }

    @Test
    fun `an 18th birthday today is accepted and a future date is invalid`() = runTest {
        viewModel.onDateOfBirthChosen(LocalDate.of(2008, 10, 22))
        assertNull(viewModel.uiState.value.dateOfBirthError)
        viewModel.onDateOfBirthChosen(LocalDate.of(2026, 10, 23))
        assertEquals(FieldError.INVALID_DATE_OF_BIRTH, viewModel.uiState.value.dateOfBirthError)
    }

    @Test
    fun `a used or ended ticket sends the person back to the number`() = runTest {
        repository.signUpResult = FakeAuthRepository.failure(AuthError.TICKET_INVALID)
        viewModel.onDateOfBirthChosen(LocalDate.of(2000, 1, 15))
        viewModel.onCreateClicked("Asha Devi")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.lost)
        assertNull(pending.ticket.value)
        assertFalse(viewModel.uiState.value.navigateToProfile)
    }

    @Test
    fun `other failures keep the form and the chosen date for another try`() = runTest {
        repository.signUpResult = FakeAuthRepository.failure(AuthError.NO_CONNECTION)
        viewModel.onDateOfBirthChosen(LocalDate.of(2000, 1, 15))
        viewModel.onCreateClicked("Asha Devi")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(AuthError.NO_CONNECTION, state.authError)
        assertEquals(LocalDate.of(2000, 1, 15), state.dateOfBirth)
        assertFalse(state.lost)
        assertEquals(FakeAuthRepository.TICKET, pending.ticket.value)
    }

    @Test
    fun `without a ticket in memory the screen says so at once`() = runTest {
        val empty = RegisterViewModel(repository, PendingVerification(), clock)
        assertTrue(empty.uiState.value.lost)
        empty.onCreateClicked("Asha Devi")
        advanceUntilIdle()
        assertEquals(0, repository.signUpCalls)
    }
}
