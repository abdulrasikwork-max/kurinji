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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RegisterUiState(
    val fullNameError: FieldError? = null,
    val emailError: FieldError? = null,
    val passwordError: FieldError? = null,
    val confirmPasswordError: FieldError? = null,
    val isLoading: Boolean = false,
    val authError: AuthError? = null,
    val navigateToHome: Boolean = false
)

/**
 * Validates locally, then creates the account through [AuthRepository]. The confirmation field is
 * only a typing check: it is never sent to the server.
 */
class RegisterViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(RegisterUiState())
    val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

    fun onRegisterClicked(fullName: String, email: String, password: String, confirmPassword: String) {
        if (_uiState.value.isLoading) return

        val validation = RegisterUiState(
            fullNameError = FormValidator.validateFullName(fullName),
            emailError = FormValidator.validateEmail(email),
            passwordError = FormValidator.validateNewPassword(password),
            confirmPasswordError = FormValidator.validateConfirmPassword(password, confirmPassword)
        )
        val hasErrors = validation.fullNameError != null || validation.emailError != null ||
            validation.passwordError != null || validation.confirmPasswordError != null
        if (hasErrors) {
            _uiState.value = validation
            return
        }

        _uiState.value = RegisterUiState(isLoading = true)
        viewModelScope.launch {
            val result = authRepository.register(fullName.trim(), email.trim(), password)
            _uiState.value = when (result) {
                is AuthResult.Success -> RegisterUiState(navigateToHome = true)
                is AuthResult.Failure -> RegisterUiState(authError = result.error)
            }
        }
    }

    fun onNavigationHandled() {
        _uiState.update { it.copy(navigateToHome = false) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { RegisterViewModel(container.authRepository) }
        }
    }
}
