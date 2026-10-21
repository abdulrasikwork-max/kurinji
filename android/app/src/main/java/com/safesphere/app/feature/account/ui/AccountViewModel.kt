package com.safesphere.app.feature.account.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.account.domain.AccountPolicy
import com.safesphere.app.feature.account.domain.AccountRepository
import com.safesphere.app.feature.account.domain.DeletionNotice
import com.safesphere.app.feature.account.domain.PasswordProblem
import com.safesphere.app.feature.auth.domain.AuthUser
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** One outcome for the screen to show once. */
sealed interface AccountMessage {
    data object PasswordChanged : AccountMessage
    data class DeletionScheduled(val dueAt: Instant) : AccountMessage
    data object DeletionCancelled : AccountMessage
    data class Invalid(val problem: PasswordProblem) : AccountMessage
    data object PasswordRequired : AccountMessage
    data class Failed(val failure: ApiResult.Failure) : AccountMessage
}

/**
 * Account and security (Phase 10A): change password, request or cancel deletion. Passwords are passed straight
 * through to the repository and never kept in the state. One action at a time ([busy]).
 */
class AccountViewModel(
    private val repository: AccountRepository,
    currentUser: Flow<AuthUser?>,
    private val now: () -> Instant = Instant::now
) : ViewModel() {

    /** The pending deletion banner (null = none). */
    val deletion: Flow<DeletionNotice?> = currentUser.map { AccountPolicy.deletionNotice(it, now()) }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<AccountMessage?>(null)
    val message: StateFlow<AccountMessage?> = _message.asStateFlow()

    private val _sessionExpired = MutableStateFlow(false)
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    fun onChangePassword(current: String, new: String, confirm: String) {
        val problem = AccountPolicy.checkNewPassword(current, new, confirm)
        if (problem != null) {
            _message.value = AccountMessage.Invalid(problem)
            return
        }
        run({ repository.changePassword(current, new) }) { AccountMessage.PasswordChanged }
    }

    fun onRequestDeletion(password: String) {
        if (password.isEmpty()) {
            _message.value = AccountMessage.PasswordRequired
            return
        }
        run({ repository.requestDeletion(password) }) { AccountMessage.DeletionScheduled(it) }
    }

    fun onCancelDeletion(password: String) {
        if (password.isEmpty()) {
            _message.value = AccountMessage.PasswordRequired
            return
        }
        run({ repository.cancelDeletion(password) }) { AccountMessage.DeletionCancelled }
    }

    fun onMessageShown() {
        _message.value = null
    }

    fun onSessionExpiredHandled() {
        _sessionExpired.value = false
    }

    private fun <T> run(call: suspend () -> ApiResult<T>, success: (T) -> AccountMessage) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val result = call()
            _busy.value = false
            when {
                result is ApiResult.Success -> _message.value = success(result.data)
                result.isSessionExpired() -> _sessionExpired.value = true
                result is ApiResult.Failure -> _message.value = AccountMessage.Failed(result)
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { AccountViewModel(container.accountRepository, container.authRepository.currentUser) }
        }
    }
}
