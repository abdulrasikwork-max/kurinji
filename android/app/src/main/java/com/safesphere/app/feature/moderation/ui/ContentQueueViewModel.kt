package com.safesphere.app.feature.moderation.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.moderation.domain.ContentAppeal
import com.safesphere.app.feature.moderation.domain.ContentCaseSummary
import com.safesphere.app.feature.moderation.domain.ContentModerationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ContentQueueUiState(
    val isLoading: Boolean = false,
    /** False until the server answered; also false after a 403 (the queue is hidden and emptied). */
    val allowed: Boolean = false,
    val cases: List<ContentCaseSummary> = emptyList(),
    val appeals: List<ContentAppeal> = emptyList(),
    /** One-shot: a review was recorded. */
    val appealDecided: Boolean = false,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
)

/**
 * Phase 13D: the moderators' queue of reported posts, perspectives and people, and of requests to look again at those
 * decisions. A 403 empties and hides it (the role was taken away). Nothing is stored on the phone.
 */
class ContentQueueViewModel(private val repository: ContentModerationRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ContentQueueUiState())
    val uiState: StateFlow<ContentQueueUiState> = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, failure = null) }
            val cases = repository.openCases()
            val appeals = repository.openAppeals()
            val failure = (cases as? ApiResult.Failure) ?: (appeals as? ApiResult.Failure)
            if (failure != null) {
                fail(failure)
                return@launch
            }
            _uiState.update {
                it.copy(isLoading = false, allowed = true,
                    cases = (cases as ApiResult.Success).data, appeals = (appeals as ApiResult.Success).data)
            }
        }
    }

    /** Keep or undo a decision; the moderator who made it cannot (the screen hides the buttons, the server checks). */
    fun onDecideAppeal(appeal: ContentAppeal, overturn: Boolean) {
        if (appeal.youDecided || !appeal.open || _uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, failure = null) }
            when (val result = repository.decideAppeal(appeal.appealId, overturn)) {
                is ApiResult.Success -> _uiState.update { it.copy(appealDecided = true) }
                is ApiResult.Failure -> fail(result)
            }
            load()
        }
    }

    fun onAppealDecidedShown() = _uiState.update { it.copy(appealDecided = false) }
    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun fail(failure: ApiResult.Failure) = _uiState.update {
        if (failure.error == ApiError.FORBIDDEN) {
            ContentQueueUiState() // not (or no longer) a moderator: nothing stays on screen
        } else {
            it.copy(isLoading = false, failure = failure, sessionExpired = failure.isSessionExpired())
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ContentQueueViewModel(container.contentModerationRepository) }
        }
    }
}
