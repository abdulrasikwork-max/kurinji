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
import com.safesphere.app.feature.auth.domain.PendingVerification
import com.safesphere.app.feature.auth.domain.PhonePolicy
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
    /** Phase 17B: the mobile number the code goes to. */
    val phoneError: FieldError? = null,
    /** Phase 13A: the chosen date of birth (kept only in this screen's memory; stored nowhere). */
    val dateOfBirth: LocalDate? = null,
    val dateOfBirthError: FieldError? = null,
    val passwordError: FieldError? = null,
    val confirmPasswordError: FieldError? = null,
    val isLoading: Boolean = false,
    val authError: AuthError? = null,
    /** The server's own words, for errors whose text depends on the case (e.g. how long to wait). */
    val authMessage: String? = null,
    /** Phase 17B, one-shot: the account exists and a code is on its way: open the code screen. */
    val navigateToVerification: Boolean = false
)

/**
 * Validates locally, then creates the account through [AuthRepository]. The confirmation field is
 * only a typing check: it is never sent to the server. Phase 13A: the date of birth must be 18+ ([AgePolicy], the same
 * rule as the server); it is sent once and kept nowhere. Phase 17B: a mobile number ([PhonePolicy]) receives a code;
 * the verification is handed to the code screen through [PendingVerification] (memory only).
 */
class RegisterViewModel(
    private val authRepository: AuthRepository,
    private val pendingVerification: PendingVerification = PendingVerification(),
    private val clock: Clock = Clock.systemUTC()
) : ViewModel() {

    private val _uiState = MutableStateFlow(RegisterUiState())
    val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

    /** Today's UTC date, for the date picker's limits. */
    fun today(): LocalDate = AgePolicy.today(clock)

    fun onDateOfBirthChosen(date: LocalDate) {
        _uiState.update { it.copy(dateOfBirth = date, dateOfBirthError = dateOfBirthError(date)) }
    }

    fun onRegisterClicked(fullName: String, email: String, phone: String, password: String, confirmPassword: String) {
        if (_uiState.value.isLoading) return
        val dateOfBirth = _uiState.value.dateOfBirth
        val normalizedPhone = PhonePolicy.normalize(phone)

        val validation = RegisterUiState(
            fullNameError = FormValidator.validateFullName(fullName),
            emailError = FormValidator.validateEmail(email),
            phoneError = when {
                phone.isBlank() -> FieldError.REQUIRED
                normalizedPhone == null -> FieldError.INVALID_PHONE
                else -> null
            },
            dateOfBirth = dateOfBirth,
            dateOfBirthError = dateOfBirthError(dateOfBirth),
            passwordError = FormValidator.validateNewPassword(password),
            confirmPasswordError = FormValidator.validateConfirmPassword(password, confirmPassword)
        )
        val hasErrors = validation.fullNameError != null || validation.emailError != null ||
            validation.phoneError != null || validation.dateOfBirthError != null || validation.passwordError != null ||
            validation.confirmPasswordError != null
        if (hasErrors || dateOfBirth == null || normalizedPhone == null) {
            _uiState.value = validation
            return
        }

        _uiState.value = RegisterUiState(dateOfBirth = dateOfBirth, isLoading = true)
        viewModelScope.launch {
            val result = authRepository.register(fullName.trim(), email.trim(), password, dateOfBirth, normalizedPhone)
            _uiState.value = when (result) {
                is AuthResult.Success -> {
                    pendingVerification.begin(result.data)
                    RegisterUiState(navigateToVerification = true) // the date of birth is forgotten once it was sent
                }
                is AuthResult.Failure -> RegisterUiState(
                    dateOfBirth = dateOfBirth,
                    // A number the server refuses belongs next to the number field, not in the notice.
                    phoneError = if (result.error == AuthError.PHONE_INVALID) FieldError.INVALID_PHONE else null,
                    authError = result.error.takeIf { it != AuthError.PHONE_INVALID },
                    authMessage = result.message
                )
            }
        }
    }

    fun onNavigationHandled() = _uiState.update { it.copy(navigateToVerification = false) }

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
