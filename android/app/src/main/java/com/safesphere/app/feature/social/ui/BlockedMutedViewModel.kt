package com.safesphere.app.feature.social.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.social.domain.PersonSummary
import com.safesphere.app.feature.social.domain.SocialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BlockedMutedUiState(
    val isLoading: Boolean = true,
    val hasLoaded: Boolean = false,
    val blocked: List<PersonSummary> = emptyList(),
    val muted: List<PersonSummary> = emptyList(),
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
)

/** Phase 13A: the person's own blocked and muted lists (nobody else can see them). Unblocking does not reconnect. */
class BlockedMutedViewModel(private val repository: SocialRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(BlockedMutedUiState())
    val uiState: StateFlow<BlockedMutedUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load(keepFailure: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val blocked = repository.blocked()
            val muted = repository.muted()
            val failure = (blocked as? ApiResult.Failure) ?: (muted as? ApiResult.Failure)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    hasLoaded = true,
                    blocked = (blocked as? ApiResult.Success)?.data ?: it.blocked,
                    muted = (muted as? ApiResult.Success)?.data ?: it.muted,
                    failure = failure ?: if (keepFailure) it.failure else null,
                    sessionExpired = failure?.isSessionExpired() == true
                )
            }
        }
    }

    fun onUnblockConfirmed(person: PersonSummary) = runAction { repository.unblock(person.publicId) }

    fun onUnmute(person: PersonSummary) = runAction { repository.unmute(person.publicId) }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun runAction(call: suspend () -> ApiResult<*>) {
        viewModelScope.launch {
            val result = call()
            if (result is ApiResult.Failure) {
                _uiState.update { it.copy(failure = result, sessionExpired = result.isSessionExpired()) }
            }
            load(keepFailure = result is ApiResult.Failure)
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { BlockedMutedViewModel(container.socialRepository) }
        }
    }
}
