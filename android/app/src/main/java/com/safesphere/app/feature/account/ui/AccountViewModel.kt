package com.safesphere.app.feature.account.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.confirm.CodeRequest
import com.safesphere.app.core.confirm.Confirmation
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.core.validation.FieldError
import com.safesphere.app.feature.account.domain.AccountPolicy
import com.safesphere.app.feature.account.domain.AccountRepository
import com.safesphere.app.feature.account.domain.DeletionNotice
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.auth.domain.RealNamePolicy
import com.safesphere.app.feature.membership.domain.MembershipRepository
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Phase N2: the account actions that need a fresh code first. */
enum class ConfirmedAction { DELETE, CANCEL_DELETION, SIGN_OUT_OTHERS }

/** One outcome for the screen to show once. */
sealed interface AccountMessage {
    data object NameSaved : AccountMessage
    data class NameInvalid(val error: FieldError) : AccountMessage
    data object OthersSignedOut : AccountMessage
    data class DeletionScheduled(val dueAt: Instant) : AccountMessage
    data object DeletionCancelled : AccountMessage
    data class Failed(val failure: ApiResult.Failure) : AccountMessage
}

/** N2, one-shot: a code is on its way for [action]: the screen opens the code dialog. */
data class CodeNeeded(val action: ConfirmedAction, val request: CodeRequest)

/**
 * Account (Phase 10A, reworked in N2): the real name (N-24), signing out the other phones (N-26), requesting or
 * cancelling deletion. The sensitive actions first ask the server to text a fresh code to the account's own number
 * ([codeNeeded] opens the dialog), then send the typed code with the action. Codes are passed straight through and
 * never kept in the state. One action at a time ([busy]).
 */
class AccountViewModel(
    private val repository: AccountRepository,
    currentUser: Flow<AuthUser?>,
    /** N3: where the name lock (N-24) is read from; null = never locked (tests of the other actions). */
    private val membership: MembershipRepository? = null,
    /** N3: reads the account again (GET /api/users/me), so a name corrected by a verifier shows here at once. */
    private val refreshUser: suspend () -> Unit = {},
    private val now: () -> Instant = Instant::now
) : ViewModel() {

    private val _nameLocked = MutableStateFlow(false)

    /**
     * N3 (N-24): the name was checked against the college ID: the field is read-only and an admin corrects it. Read
     * from `GET /api/community/me` each time the screen starts; a 409 NAME_LOCKED also turns it on.
     */
    val nameLocked: StateFlow<Boolean> = _nameLocked.asStateFlow()

    fun refreshNameLock() {
        val source = membership ?: return
        viewModelScope.launch {
            val result = source.status()
            if (result is ApiResult.Success) _nameLocked.value = result.data.nameLocked
            // After the lock is known, so a corrected (locked) name replaces the one first shown in the field.
            refreshUser()
        }
    }

    /** The pending deletion banner (null = none). */
    val deletion: Flow<DeletionNotice?> = currentUser.map { AccountPolicy.deletionNotice(it, now()) }

    /** The real name as the account has it now (the name field starts from it). */
    val name: Flow<String?> = currentUser.map { it?.name }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<AccountMessage?>(null)
    val message: StateFlow<AccountMessage?> = _message.asStateFlow()

    private val _codeNeeded = MutableStateFlow<CodeNeeded?>(null)
    val codeNeeded: StateFlow<CodeNeeded?> = _codeNeeded.asStateFlow()

    private val _sessionExpired = MutableStateFlow(false)
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    fun onSaveName(typed: String) {
        val name = RealNamePolicy.clean(typed)
        val problem = when (RealNamePolicy.check(name)) {
            RealNamePolicy.Result.OK -> null
            RealNamePolicy.Result.TOO_SHORT -> if (name.isEmpty()) FieldError.REQUIRED else FieldError.NAME_TOO_SHORT
            RealNamePolicy.Result.TOO_LONG -> FieldError.NAME_TOO_LONG
            RealNamePolicy.Result.NOT_A_NAME -> FieldError.NOT_A_NAME
        }
        if (_nameLocked.value) return
        if (problem != null) {
            _message.value = AccountMessage.NameInvalid(problem)
            return
        }
        run({ repository.changeName(name) }) { AccountMessage.NameSaved }
    }

    /** Step 1 of a confirmed action: a code is texted to the account's number. */
    fun onAskCode(action: ConfirmedAction) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val result = repository.request()
            _busy.value = false
            when {
                result is ApiResult.Success -> _codeNeeded.value = CodeNeeded(action, result.data)
                result.isSessionExpired() -> _sessionExpired.value = true
                result is ApiResult.Failure -> _message.value = AccountMessage.Failed(result)
            }
        }
    }

    /** The dialog is open now (or was closed): the one-shot is used up. */
    fun onCodeDialogShown() {
        _codeNeeded.value = null
    }

    /** Step 2: the typed code confirms [action]. */
    fun onConfirm(action: ConfirmedAction, confirmation: Confirmation) {
        when (action) {
            ConfirmedAction.DELETE ->
                run({ repository.requestDeletion(confirmation) }) { AccountMessage.DeletionScheduled(it) }
            ConfirmedAction.CANCEL_DELETION ->
                run({ repository.cancelDeletion(confirmation) }) { AccountMessage.DeletionCancelled }
            ConfirmedAction.SIGN_OUT_OTHERS ->
                run({ repository.signOutOtherPhones(confirmation) }) { AccountMessage.OthersSignedOut }
        }
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
                result is ApiResult.Failure -> {
                    if (result.code == NAME_LOCKED) _nameLocked.value = true
                    _message.value = AccountMessage.Failed(result)
                }
            }
        }
    }

    companion object {
        /** The server's 409 code (backend `NameLockedException`). */
        const val NAME_LOCKED = "NAME_LOCKED"

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                AccountViewModel(container.accountRepository, container.authRepository.currentUser,
                    membership = container.membershipRepository,
                    refreshUser = { container.authRepository.refreshProfile() })
            }
        }
    }
}
