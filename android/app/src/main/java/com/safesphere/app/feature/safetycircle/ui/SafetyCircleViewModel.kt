package com.safesphere.app.feature.safetycircle.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.safetycircle.domain.SafetyCircleRepository
import com.safesphere.app.feature.safetycircle.domain.SafetyContact
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SafetyCircleUiState(
    val isLoading: Boolean = true,
    val contacts: List<SafetyContact> = emptyList(),
    /** False until the first answer arrives, so the empty state is not flashed while loading. */
    val hasLoaded: Boolean = false,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
)

class SafetyCircleViewModel(private val repository: SafetyCircleRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(SafetyCircleUiState())
    val uiState: StateFlow<SafetyCircleUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /** Reloads the list. Called again when the screen comes back from the Add / Edit form. */
    fun load(keepFailure: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val result = repository.getContacts()) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        hasLoaded = true,
                        contacts = result.data,
                        failure = if (keepFailure) it.failure else null
                    )
                }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, failure = result, sessionExpired = result.isSessionExpired())
                }
            }
        }
    }

    /** Switch in the list. Sends the contact with only "enabled" changed; the server decides the rest. */
    fun onEnabledChanged(contact: SafetyContact, enabled: Boolean) {
        runAction { repository.updateContact(contact.id, contact.toInput().copy(isEnabled = enabled)) }
    }

    /** Called only after the person confirmed the delete dialog. */
    fun onDeleteConfirmed(contact: SafetyContact) {
        runAction { repository.deleteContact(contact.id) }
    }

    fun onSessionExpiredHandled() {
        _uiState.update { it.copy(sessionExpired = false) }
    }

    /** Runs a change, then always reloads so the screen shows what the server really holds. */
    private fun runAction(call: suspend () -> ApiResult<*>) {
        viewModelScope.launch {
            val result = call()
            if (result is ApiResult.Failure) {
                _uiState.update { it.copy(failure = result, sessionExpired = result.isSessionExpired()) }
            }
            load(keepFailure = true)
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SafetyCircleViewModel(container.safetyCircleRepository) }
        }
    }
}
