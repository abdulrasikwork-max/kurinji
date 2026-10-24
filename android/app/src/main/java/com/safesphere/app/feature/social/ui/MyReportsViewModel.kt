package com.safesphere.app.feature.social.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.social.domain.MyReport
import com.safesphere.app.feature.social.domain.ReportRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MyReportsUiState(
    val isLoading: Boolean = true,
    val reports: List<MyReport> = emptyList(),
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
)

/** Phase 13D: the caller's own reports (newest 50): what, why, and whether a moderator reviewed it. Nothing stored. */
class MyReportsViewModel(private val repository: ReportRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(MyReportsUiState())
    val uiState: StateFlow<MyReportsUiState> = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, failure = null) }
            when (val result = repository.myReports()) {
                is ApiResult.Success -> _uiState.update { it.copy(isLoading = false, reports = result.data) }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, failure = result, sessionExpired = result.isSessionExpired())
                }
            }
        }
    }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { MyReportsViewModel(container.reportRepository) }
        }
    }
}
