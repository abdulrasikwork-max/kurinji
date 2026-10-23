package com.safesphere.app.feature.auth.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.validation.FieldError
import com.safesphere.app.core.validation.FormValidator
import com.safesphere.app.feature.auth.domain.AuthError
import com.safesphere.app.feature.auth.domain.AuthRepository
import com.safesphere.app.feature.auth.domain.AuthResult
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
    val emailError: FieldError? = null,
    /** Phase 13A: the chosen date of birth (kept only in this screen's memory; stored nowhere). */
    val dateOfBirth: LocalDate? = null,
    val dateOfBirthError: FieldError? = null,
    val passwordError: FieldError? = null,
    val confirmPasswordError: FieldError? = null,
    val isLoading: Boolean = false,
    val authError: AuthError? = null,
    val navigateToHome: Boolean = false
)

/**
 * Validates locally, then creates the account through [AuthRepository]. The confirmation field is
 * only a typing check: it is never sent to the server. Phase 13A: the date of birth must be 18+ ([AgePolicy], the same
 * rule as the server); it is sent once with the sign-up and kept nowhere.
 */
class RegisterViewModel(
    private val authRepository: AuthRepository,
    private val clock: Clock = Clock.systemUTC()
) : ViewModel() {

    private val _uiState = MutableStateFlow(RegisterUiState())
    val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

    /** Today's UTC date, for the date picker's limits. */
    fun today(): LocalDate = AgePolicy.today(clock)

    fun onDateOfBirthChosen(date: LocalDate) {
        _uiState.update { it.copy(dateOfBirth = date, dateOfBirthError = dateOfBirthError(date)) }
    }

    fun onRegisterClicked(fullName: String, email: String, password: String, confirmPassword: String) {
        if (_uiState.value.isLoading) return
        val dateOfBirth = _uiState.value.dateOfBirth

        val validation = RegisterUiState(
            fullNameError = FormValidator.validateFullName(fullName),
            emailError = FormValidator.validateEmail(email),
            dateOfBirth = dateOfBirth,
            dateOfBirthError = dateOfBirthError(dateOfBirth),
            passwordError = FormValidator.validateNewPassword(password),
            confirmPasswordError = FormValidator.validateConfirmPassword(password, confirmPassword)
        )
        val hasErrors = validation.fullNameError != null || validation.emailError != null ||
            validation.dateOfBirthError != null || validation.passwordError != null ||
            validation.confirmPasswordError != null
        if (hasErrors || dateOfBirth == null) {
            _uiState.value = validation
            return
        }

        _uiState.value = RegisterUiState(dateOfBirth = dateOfBirth, isLoading = true)
        viewModelScope.launch {
            val result = authRepository.register(fullName.trim(), email.trim(), password, dateOfBirth)
            _uiState.value = when (result) {
                is AuthResult.Success -> RegisterUiState(navigateToHome = true)
                is AuthResult.Failure -> RegisterUiState(dateOfBirth = dateOfBirth, authError = result.error)
            }
        }
    }

    fun onNavigationHandled() {
        _uiState.update { it.copy(navigateToHome = false) }
    }

    private fun dateOfBirthError(date: LocalDate?): FieldError? = when (AgePolicy.check(date, today())) {
        AgePolicy.Result.ADULT -> null
        AgePolicy.Result.UNDER_AGE -> FieldError.UNDER_AGE
        AgePolicy.Result.INVALID -> if (date == null) FieldError.REQUIRED else FieldError.INVALID_DATE_OF_BIRTH
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { RegisterViewModel(container.authRepository) }
        }
    }
}
