package com.safesphere.app.feature.membership.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.auth.domain.PendingVerification
import com.safesphere.app.feature.membership.domain.MembershipRepository
import com.safesphere.app.feature.membership.domain.RelinkCode
import com.safesphere.app.feature.membership.domain.RollNumberPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RelinkUiState(
    val rollProblem: Boolean = false,
    val busy: Boolean = false,
    /** The code to show an IRL admin (memory only; FLAG_SECURE on the screen). */
    val code: RelinkCode? = null,
    /** No sign-up ticket in memory any more (used, ran out, or the app was closed): the number again. */
    val lost: Boolean = false,
    val failure: ApiResult.Failure? = null
)

/**
 * Phase N3 (N-25): "I'm moving from another number". The right code for a NEW number without an account gave a
 * one-use sign-up ticket ([PendingVerification]); instead of making a new account, the person sends it with their
 * roll number and gets a code (1 hour). An IRL admin, with the ID card, moves the existing member account to this
 * number; the person then signs in with it. The ticket is used up either way.
 */
class RelinkViewModel(
    private val repository: MembershipRepository,
    private val pendingVerification: PendingVerification
) : ViewModel() {

    private val _uiState = MutableStateFlow(RelinkUiState(lost = pendingVerification.ticket.value == null))
    val uiState: StateFlow<RelinkUiState> = _uiState.asStateFlow()

    fun onGetCode(typedRoll: String) {
        val state = _uiState.value
        if (state.busy || state.code != null) return
        val ticket = pendingVerification.ticket.value
        if (ticket == null) {
            _uiState.update { it.copy(lost = true) }
            return
        }
        if (!RollNumberPolicy.accepts(typedRoll)) {
            _uiState.update { it.copy(rollProblem = true) }
            return
        }
        _uiState.update { it.copy(busy = true, rollProblem = false, failure = null) }
        viewModelScope.launch {
            when (val result = repository.relink(ticket.token, typedRoll)) {
                is ApiResult.Success -> {
                    pendingVerification.clear() // the server used the ticket up
                    _uiState.update { it.copy(busy = false, code = result.data) }
                }
                is ApiResult.Failure -> {
                    // The roll number was checked here first, so a 400 means the ticket ended; a 409 (the number
                    // already has an account) also ends this sign-up.
                    val ended = result.error == ApiError.VALIDATION || result.error == ApiError.CONFLICT
                    if (ended) pendingVerification.clear()
                    _uiState.update { it.copy(busy = false, lost = ended, failure = result) }
                }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { RelinkViewModel(container.membershipRepository, container.pendingVerification) }
        }
    }
}
