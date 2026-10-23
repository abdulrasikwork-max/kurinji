package com.safesphere.app.feature.social.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.social.domain.PersonProfile
import com.safesphere.app.feature.social.domain.SocialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PersonProfileUiState(
    val isLoading: Boolean = true,
    val person: PersonProfile? = null,
    /** No one with that handle, or someone the viewer may not see: the same answer, on purpose. */
    val notFound: Boolean = false,
    val isBusy: Boolean = false,
    /** One-shot: a short confirmation to show (a string resource). */
    val message: Int? = null,
    /** One-shot: the person was blocked; the screen closes. */
    val closed: Boolean = false,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
)

/**
 * Phase 13A: someone's profile, found by exact handle or opened from a list. Connect (or accept, when they asked first:
 * asking back connects), remove a connection, mute, block. A block closes the screen; the other person is never told.
 */
class PersonProfileViewModel(
    private val repository: SocialRepository,
    private val handle: String?,
    private val profileId: String?
) : ViewModel() {

    private val _uiState = MutableStateFlow(PersonProfileUiState())
    val uiState: StateFlow<PersonProfileUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = when {
                profileId != null -> repository.profile(profileId)
                handle != null -> repository.findByHandle(handle)
                else -> ApiResult.Failure(ApiError.NOT_FOUND)
            }
            when (result) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isLoading = false, person = result.data, notFound = false, failure = null)
                }
                is ApiResult.Failure -> if (result.error == ApiError.NOT_FOUND) {
                    _uiState.update { it.copy(isLoading = false, person = null, notFound = true) }
                } else {
                    fail(result)
                }
            }
        }
    }

    fun onConnect() = act(null) { repository.connect(it) }

    fun onRemoveConnectionConfirmed() = act(com.safesphere.app.R.string.social_connection_removed) {
        repository.removeConnection(it)
    }

    fun onMute() = act(com.safesphere.app.R.string.social_muted_message) { repository.mute(it) }

    fun onBlockConfirmed() {
        val person = _uiState.value.person ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            when (val result = repository.block(person.publicId)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isBusy = false, closed = true, message = com.safesphere.app.R.string.social_blocked_message)
                }
                is ApiResult.Failure -> fail(result)
            }
        }
    }

    fun onMessageShown() = _uiState.update { it.copy(message = null) }

    /** One-shot: without this, the next state (for example the toast being marked shown) would close a second screen. */
    fun onClosedHandled() = _uiState.update { it.copy(closed = false) }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    /** Runs an action on the person, then reloads their profile so the buttons match the server. */
    private fun act(message: Int?, call: suspend (publicId: String) -> ApiResult<*>) {
        val person = _uiState.value.person ?: return
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            when (val result = call(person.publicId)) {
                is ApiResult.Success -> _uiState.update { it.copy(isBusy = false, message = message, failure = null) }
                is ApiResult.Failure -> fail(result)
            }
            reload(person.publicId)
        }
    }

    private suspend fun reload(publicId: String) {
        when (val result = repository.profile(publicId)) {
            is ApiResult.Success -> _uiState.update { it.copy(person = result.data) }
            is ApiResult.Failure -> if (result.error == ApiError.NOT_FOUND) {
                _uiState.update { it.copy(person = null, notFound = true) }
            }
        }
    }

    private fun fail(failure: ApiResult.Failure) {
        _uiState.update {
            it.copy(isLoading = false, isBusy = false, failure = failure, sessionExpired = failure.isSessionExpired())
        }
    }

    companion object {
        const val ARG_HANDLE = "handle"
        const val ARG_PROFILE_ID = "profileId"

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                val args = createSavedStateHandle()
                PersonProfileViewModel(
                    container.socialRepository,
                    args.get<String>(ARG_HANDLE)?.takeIf { it.isNotBlank() },
                    args.get<String>(ARG_PROFILE_ID)?.takeIf { it.isNotBlank() }
                )
            }
        }
    }
}
