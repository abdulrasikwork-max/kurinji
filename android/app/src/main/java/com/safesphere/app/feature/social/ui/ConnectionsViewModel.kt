package com.safesphere.app.feature.social.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.social.domain.HandlePolicy
import com.safesphere.app.feature.social.domain.InviteItem
import com.safesphere.app.feature.social.domain.NewInvite
import com.safesphere.app.feature.social.domain.OwnProfile
import com.safesphere.app.feature.social.domain.PersonSummary
import com.safesphere.app.feature.social.domain.RequestItem
import com.safesphere.app.feature.social.domain.SocialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConnectionsUiState(
    val isLoading: Boolean = true,
    val hasLoaded: Boolean = false,
    /** Null while loading or when the person has no profile yet (then the screen offers to make one). */
    val me: OwnProfile? = null,
    val incoming: List<RequestItem> = emptyList(),
    val outgoing: List<RequestItem> = emptyList(),
    val connections: List<PersonSummary> = emptyList(),
    val invites: List<InviteItem> = emptyList(),
    /** One-shot: a code just created, shown once in a dialog (the server cannot show it again). */
    val newInvite: NewInvite? = null,
    /** One-shot: open this person's profile (found by handle). */
    val openHandle: String? = null,
    /** One-shot: connected with this person through an invite code. */
    val connectedWith: PersonSummary? = null,
    val handleInvalid: Boolean = false,
    val codeInvalid: Boolean = false,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
)

/**
 * Phase 13A: the person's own connections screen: requests for them and from them, their connections, invite codes,
 * and finding someone by exact handle. Every list is the caller's own; nothing shows a count.
 */
class ConnectionsViewModel(private val repository: SocialRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ConnectionsUiState())
    val uiState: StateFlow<ConnectionsUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load(keepFailure: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val profile = repository.ownProfile()
            if (profile is ApiResult.Failure) {
                // 409 = the age is not confirmed yet: the profile screen asks for it. Shown as "no profile yet".
                if (profile.error == ApiError.CONFLICT) {
                    _uiState.update { it.copy(isLoading = false, hasLoaded = true, me = null) }
                } else {
                    fail(profile)
                }
                return@launch
            }
            val me = (profile as ApiResult.Success).data
            if (me == null) {
                _uiState.update { it.copy(isLoading = false, hasLoaded = true, me = null) }
                return@launch
            }
            val incoming = repository.incomingRequests()
            val outgoing = repository.outgoingRequests()
            val connections = repository.connections()
            val invites = repository.invites()
            val firstFailure = listOf(incoming, outgoing, connections, invites).firstOrNull { it is ApiResult.Failure }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    hasLoaded = true,
                    me = me,
                    incoming = (incoming as? ApiResult.Success)?.data ?: it.incoming,
                    outgoing = (outgoing as? ApiResult.Success)?.data ?: it.outgoing,
                    connections = (connections as? ApiResult.Success)?.data ?: it.connections,
                    invites = (invites as? ApiResult.Success)?.data ?: it.invites,
                    failure = (firstFailure as? ApiResult.Failure) ?: if (keepFailure) it.failure else null,
                    sessionExpired = (firstFailure as? ApiResult.Failure)?.isSessionExpired() == true
                )
            }
        }
    }

    fun onAccept(item: RequestItem) = runAction { repository.accept(item.requestId) }

    fun onDecline(item: RequestItem) = runAction { repository.decline(item.requestId) }

    fun onCancel(item: RequestItem) = runAction { repository.cancel(item.requestId) }

    fun onRevokeInvite(invite: InviteItem) = runAction { repository.revokeInvite(invite.id) }

    fun onCreateInvite() {
        viewModelScope.launch {
            when (val result = repository.createInvite()) {
                is ApiResult.Success -> _uiState.update { it.copy(newInvite = result.data, failure = null) }
                is ApiResult.Failure -> fail(result)
            }
            load(keepFailure = true)
        }
    }

    fun onRedeem(code: String) {
        val compact = code.filter { it.isLetterOrDigit() }
        if (compact.length != INVITE_CODE_LENGTH) {
            _uiState.update { it.copy(codeInvalid = true) }
            return
        }
        viewModelScope.launch {
            when (val result = repository.redeem(code)) {
                is ApiResult.Success -> _uiState.update { it.copy(connectedWith = result.data.person, failure = null) }
                is ApiResult.Failure -> fail(result)
            }
            load(keepFailure = true)
        }
    }

    /** Finding someone is by their exact handle only (no search by name, no suggestions). */
    fun onFind(handle: String) {
        val normalized = HandlePolicy.normalize(handle)
        if (HandlePolicy.check(normalized) == HandlePolicy.Result.INVALID_FORMAT) {
            _uiState.update { it.copy(handleInvalid = true) }
            return
        }
        _uiState.update { it.copy(openHandle = normalized, handleInvalid = false) }
    }

    fun onHandleTyped() = _uiState.update { it.copy(handleInvalid = false) }

    fun onCodeTyped() = _uiState.update { it.copy(codeInvalid = false) }

    fun onNewInviteShown() = _uiState.update { it.copy(newInvite = null) }

    fun onOpenHandled() = _uiState.update { it.copy(openHandle = null) }

    fun onConnectedShown() = _uiState.update { it.copy(connectedWith = null) }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    /** Runs a change, then always reloads so the screen shows what the server really holds. */
    private fun runAction(call: suspend () -> ApiResult<*>) {
        viewModelScope.launch {
            val result = call()
            if (result is ApiResult.Failure) fail(result)
            load(keepFailure = result is ApiResult.Failure)
        }
    }

    private fun fail(failure: ApiResult.Failure) {
        _uiState.update { it.copy(isLoading = false, failure = failure, sessionExpired = failure.isSessionExpired()) }
    }

    companion object {
        const val INVITE_CODE_LENGTH = 26

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ConnectionsViewModel(container.socialRepository) }
        }
    }
}
