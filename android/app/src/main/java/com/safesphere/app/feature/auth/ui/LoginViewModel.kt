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
import com.safesphere.app.feature.account.domain.PendingDeletion
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    val navigateToHome: Boolean = false,
    /** The server's own words, for errors whose text depends on the case (e.g. how long to wait). */
    val authMessage: String? = null,
    /** Phase 17B, one-shot: the password was right but the phone is not verified yet: open the code screen. */
    val navigateToVerification: Boolean = false
)

/**
 * Checks the form on the device first (same rules as Phase 1), then asks the backend through
 * [AuthRepository]. The password only lives inside this function call; it is never kept in state.
 *
 * Phase 17B: an account without a verified phone (an interrupted sign-up, or an account from before 17B) is not an
 * error to show: the same call then asks for a verification and the code screen opens (it asks for a number first when
 * the account has none). Nothing about the verification is stored on the phone.
 */
class LoginViewModel(
    private val authRepository: AuthRepository,
    private val pendingDeletion: Flow<PendingDeletion?> = flowOf(null),
    private val removeAccountData: suspend (userId: Long) -> Boolean = { true },
    private val pendingVerification: PendingVerification = PendingVerification(),
    private val now: () -> Instant = Instant::now
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    /**
     * Phase 10A.3: an account on this phone was scheduled for deletion and its date has passed. Its evidence and
     * recovery key may still be on this phone (a sign-out keeps them); the person can remove them here. Not shown
     * before the date: until then the account exists and signing in shows the deletion banner instead.
     */
    val deletedAccountData: Flow<Boolean> = pendingDeletion.map { it != null && !it.dueAt.isAfter(now()) }

    fun onRemoveDeletedAccountData() {
        viewModelScope.launch {
            val marker = pendingDeletion.first() ?: return@launch
            removeAccountData(marker.userId)
        }
    }

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
            val result = authRepository.login(email.trim(), password)
            _uiState.value = when {
                result is AuthResult.Success -> LoginUiState(navigateToHome = true)
                result is AuthResult.Failure && result.error == AuthError.PHONE_NOT_VERIFIED ->
                    when (val started = authRepository.startPhoneVerification(email.trim(), password)) {
                        is AuthResult.Success -> {
                            pendingVerification.begin(started.data)
                            LoginUiState(navigateToVerification = true)
                        }
                        is AuthResult.Failure -> LoginUiState(authError = started.error, authMessage = started.message)
                    }
                result is AuthResult.Failure -> LoginUiState(authError = result.error, authMessage = result.message)
                else -> LoginUiState(authError = AuthError.UNKNOWN)
            }
        }
    }

    fun onNavigationHandled() {
        _uiState.update { it.copy(navigateToHome = false, navigateToVerification = false) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                LoginViewModel(container.authRepository, container.pendingDeletion.pending, container.accountDataWiper::wipe,
                    pendingVerification = container.pendingVerification)
            }
        }
    }
}
