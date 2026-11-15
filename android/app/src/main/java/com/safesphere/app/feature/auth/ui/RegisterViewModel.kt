package com.safesphere.app.feature.auth.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.validation.FieldError
import com.safesphere.app.feature.auth.domain.AuthError
import com.safesphere.app.feature.auth.domain.AuthRepository
import com.safesphere.app.feature.auth.domain.AuthResult
import com.safesphere.app.feature.auth.domain.PendingVerification
import com.safesphere.app.feature.auth.domain.RealNamePolicy
import com.safesphere.app.feature.social.domain.AgePolicy
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RegisterUiState(
    val fullNameError: FieldError? = null,
    /** Phase 13A: the chosen date of birth (kept only in this screen's memory; stored nowhere). */
    val dateOfBirth: LocalDate? = null,
    val dateOfBirthError: FieldError? = null,
    val isLoading: Boolean = false,
    val authError: AuthError? = null,
    /** The server's own words, for errors whose text depends on the case (e.g. how long to wait). */
    val authMessage: String? = null,
    /**
     * No sign-up in this app's memory (the app was closed, or the ticket was used or ran out): the person enters the
     * number again.
     */
    val lost: Boolean = false,
    /** One-shot: the account exists and this phone is signed in: the profile (handle) comes next. */
    val navigateToProfile: Boolean = false
)

/**
 * Phase N2: "About you", only for a number that has no account yet (the code was right; the server gave a one-use
 * ticket, handed over in memory by [PendingVerification]). The person's real name ([RealNamePolicy], the server's
 * rule) and an adult date of birth ([AgePolicy], Phase 13A: sent once, stored nowhere) create the account. A refused
 * name or age leaves the ticket usable for a corrected try.
 */
class RegisterViewModel(
    private val authRepository: AuthRepository,
    private val pendingVerification: PendingVerification = PendingVerification(),
    private val clock: Clock = Clock.systemUTC()
) : ViewModel() {

    private val _uiState = MutableStateFlow(RegisterUiState(lost = pendingVerification.ticket.value == null))
    val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

    /** Today's UTC date, for the date picker's limits. */
    fun today(): LocalDate = AgePolicy.today(clock)

    fun onDateOfBirthChosen(date: LocalDate) {
        _uiState.update { it.copy(dateOfBirth = date, dateOfBirthError = dateOfBirthError(date)) }
    }

    fun onCreateClicked(fullName: String) {
        if (_uiState.value.isLoading) return
        val ticket = pendingVerification.ticket.value
        if (ticket == null) {
            _uiState.update { it.copy(lost = true) }
            return
        }
        val dateOfBirth = _uiState.value.dateOfBirth
        val name = RealNamePolicy.clean(fullName)
        val nameError = nameError(name)
        val ageError = dateOfBirthError(dateOfBirth)
        if (nameError != null || ageError != null || dateOfBirth == null) {
            _uiState.value = RegisterUiState(fullNameError = nameError, dateOfBirth = dateOfBirth,
                dateOfBirthError = ageError)
            return
        }

        _uiState.value = RegisterUiState(dateOfBirth = dateOfBirth, isLoading = true)
        viewModelScope.launch {
            _uiState.value = when (val result = authRepository.signUp(ticket, name, dateOfBirth)) {
                is AuthResult.Success -> {
                    pendingVerification.clear()
                    RegisterUiState(navigateToProfile = true) // the date of birth is forgotten once it was sent
                }
                is AuthResult.Failure -> if (result.error == AuthError.TICKET_INVALID ||
                    result.error == AuthError.PHONE_IN_USE) {
                    pendingVerification.clear()
                    RegisterUiState(lost = true, authError = result.error)
                } else {
                    RegisterUiState(dateOfBirth = dateOfBirth, authError = result.error, authMessage = result.message)
                }
            }
        }
    }

    fun onNavigationHandled() = _uiState.update { it.copy(navigateToProfile = false) }

    private fun nameError(name: String): FieldError? = when (RealNamePolicy.check(name)) {
        RealNamePolicy.Result.OK -> null
        RealNamePolicy.Result.TOO_SHORT -> if (name.isEmpty()) FieldError.REQUIRED else FieldError.NAME_TOO_SHORT
        RealNamePolicy.Result.TOO_LONG -> FieldError.NAME_TOO_LONG
        RealNamePolicy.Result.NOT_A_NAME -> FieldError.NOT_A_NAME
    }

    private fun dateOfBirthError(date: LocalDate?): FieldError? = when (AgePolicy.check(date, today())) {
        AgePolicy.Result.ADULT -> null
        AgePolicy.Result.UNDER_AGE -> FieldError.UNDER_AGE
        AgePolicy.Result.INVALID -> if (date == null) FieldError.REQUIRED else FieldError.INVALID_DATE_OF_BIRTH
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { RegisterViewModel(container.authRepository, container.pendingVerification) }
        }
    }
}
