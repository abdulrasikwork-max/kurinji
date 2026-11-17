package com.safesphere.app.feature.replies.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.core.network.needsMembership
import com.safesphere.app.feature.replies.domain.RepliesPage
import com.safesphere.app.feature.replies.domain.RepliesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RepliesUiState(
    val page: RepliesPage? = null,
    val loading: Boolean = false,
    val needsMembership: Boolean = false,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
) {
    val rows: List<RepliesRow> get() = if (needsMembership) emptyList() else RepliesLabels.rows(page, loading)
}

/**
 * Phase N5 (D19): Replies, the one home for what people said back. Read each time the tab is shown; after a read the
 * server is told the person opened Replies (their own time, never per message) and the dot goes. The "New" marks of
 * that read stay until the next one. Nothing is stored.
 */
class RepliesViewModel(
    private val repository: RepliesRepository,
    private val marker: RepliesMarker
) : ViewModel() {

    private val _uiState = MutableStateFlow(RepliesUiState())
    val uiState: StateFlow<RepliesUiState> = _uiState.asStateFlow()

    private var load: Job? = null

    /** The tab is shown (also again after another tab): read again; the page stays until the new one arrives. */
    fun onShown() {
        if (load?.isActive == true) return
        _uiState.update { it.copy(loading = true, failure = null) }
        load = viewModelScope.launch {
            when (val result = repository.replies()) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(page = result.data, loading = false, needsMembership = false) }
                    // Opened: the server's time moves, the dot goes. A failure here only means the dot may return.
                    if (repository.opened() is ApiResult.Success) marker.opened()
                }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(loading = false, needsMembership = result.needsMembership(),
                        failure = if (result.needsMembership()) null else result, sessionExpired = result.isSessionExpired())
                }
            }
        }
    }

    fun onFailureShown() = _uiState.update { it.copy(failure = null) }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { RepliesViewModel(container.repliesRepository, container.repliesMarker) }
        }
    }
}
