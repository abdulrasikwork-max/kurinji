package com.safesphere.app.feature.emergency.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.emergency.domain.EmergencyEvent
import com.safesphere.app.feature.emergency.domain.EmergencyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EmergencyHistoryUiState(
    val isLoading: Boolean = true,
    val events: List<EmergencyEvent> = emptyList(),
    val hasLoaded: Boolean = false,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
)

class EmergencyHistoryViewModel(private val repository: EmergencyRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(EmergencyHistoryUiState())
    val uiState: StateFlow<EmergencyHistoryUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val result = repository.getHistory()) {
                is ApiResult.Success ->
                    _uiState.update { it.copy(isLoading = false, hasLoaded = true, events = result.data, failure = null) }
                is ApiResult.Failure ->
                    _uiState.update {
                        it.copy(isLoading = false, failure = result, sessionExpired = result.isSessionExpired())
                    }
            }
        }
    }

    fun onSessionExpiredHandled() {
        _uiState.update { it.copy(sessionExpired = false) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { EmergencyHistoryViewModel(container.emergencyRepository) }
        }
    }
}
