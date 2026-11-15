package com.safesphere.app.feature.auth.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.validation.FieldError
import com.safesphere.app.feature.account.domain.PendingDeletion
import com.safesphere.app.feature.auth.domain.AuthError
import com.safesphere.app.feature.auth.domain.AuthRepository
import com.safesphere.app.feature.auth.domain.AuthResult
import com.safesphere.app.feature.auth.domain.PendingVerification
import com.safesphere.app.feature.auth.domain.PhonePolicy
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val phoneError: FieldError? = null,
    /** True while the request to the server is running. */
    val isLoading: Boolean = false,
    /** Set when the server (or the network) refused the attempt. */
    val authError: AuthError? = null,
    /** The server's own words, for errors whose text depends on the case (e.g. how long to wait). */
    val authMessage: String? = null,
    /** One-shot: a code is on its way: open the code screen. */
    val navigateToVerification: Boolean = false
)

/**
 * Phase N2: the first sign-in screen, for everyone (a new number and a known one look the same here: the server answers
 * the same and texts a code either way). The number is checked on the phone first ([PhonePolicy], the server's rule),
 * then the code request is handed to the code screen through [PendingVerification] (memory only).
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
     * Phase 10A.3: an account on this phone was scheduled for deletion and its date has passed. What it left on this
     * phone (a sign-out keeps the safety data) can be removed here. Not shown before the date: until then the account
     * exists and signing in shows the deletion banner instead.
     */
    val deletedAccountData: Flow<Boolean> = pendingDeletion.map { it != null && !it.dueAt.isAfter(now()) }

    fun onRemoveDeletedAccountData() {
        viewModelScope.launch {
            val marker = pendingDeletion.first() ?: return@launch
            removeAccountData(marker.userId)
        }
    }

    fun onSendCodeClicked(phone: String) {
        if (_uiState.value.isLoading) return
        val normalized = PhonePolicy.normalize(phone)
        if (normalized == null) {
            _uiState.value = LoginUiState(phoneError = if (phone.isBlank()) FieldError.REQUIRED else FieldError.INVALID_PHONE)
            return
        }
        _uiState.value = LoginUiState(isLoading = true)
        viewModelScope.launch {
            _uiState.value = when (val result = authRepository.startSignIn(normalized)) {
                is AuthResult.Success -> {
                    pendingVerification.begin(result.data)
                    LoginUiState(navigateToVerification = true)
                }
                // A number the server refuses belongs next to the number field, not in the notice.
                is AuthResult.Failure -> if (result.error == AuthError.PHONE_INVALID) {
                    LoginUiState(phoneError = FieldError.INVALID_PHONE)
                } else {
                    LoginUiState(authError = result.error, authMessage = result.message)
                }
            }
        }
    }

    fun onNavigationHandled() {
        _uiState.update { it.copy(navigateToVerification = false) }
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
