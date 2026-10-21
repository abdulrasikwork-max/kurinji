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

data class LoginUiState(
    val emailError: FieldError? = null,
    val passwordError: FieldError? = null,
    /** True while the request to the server is running. */
    val isLoading: Boolean = false,
    /** Set when the server (or the network) rejected the attempt. */
    val authError: AuthError? = null,
    /** True after a successful login: the screen then opens Home. */
    val navigateToHome: Boolean = false
)

/**
 * Checks the form on the device first (same rules as Phase 1), then asks the backend through
 * [AuthRepository]. The password only lives inside this function call; it is never kept in state.
 */
class LoginViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onLoginClicked(email: String, password: String) {
        if (_uiState.value.isLoading) return

        val emailError = FormValidator.validateEmail(email)
        // Login only checks that a password was typed. Password rules are the server's job.
        val passwordError = FormValidator.validateRequired(password)
        if (emailError != null || passwordError != null) {
            _uiState.value = LoginUiState(emailError = emailError, passwordError = passwordError)
            return
        }

        _uiState.value = LoginUiState(isLoading = true)
        viewModelScope.launch {
            _uiState.value = when (val result = authRepository.login(email.trim(), password)) {
                is AuthResult.Success -> LoginUiState(navigateToHome = true)
                is AuthResult.Failure -> LoginUiState(authError = result.error)
            }
        }
    }

    fun onNavigationHandled() {
        _uiState.update { it.copy(navigateToHome = false) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { LoginViewModel(container.authRepository) }
        }
    }
}
