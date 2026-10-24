package com.safesphere.app.feature.moderation.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.moderation.domain.ContentAction
import com.safesphere.app.feature.moderation.domain.ContentCaseDetail
import com.safesphere.app.feature.moderation.domain.ContentDecisionReason
import com.safesphere.app.feature.moderation.domain.ContentModerationPolicy
import com.safesphere.app.feature.moderation.domain.ContentModerationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ContentCaseUiState(
    val isLoading: Boolean = true,
    val detail: ContentCaseDetail? = null,
    /** 403: not a moderator, or part of this case (another moderator decides it). Nothing of the case stays shown. */
    val notAllowed: Boolean = false,
    /** Photos the moderator chose to see (D15: hidden by default). */
    val shownPhotos: Set<String> = emptySet(),
    val isBusy: Boolean = false,
    /** One-shot: a decision was recorded. */
    val decided: Boolean = false,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
) {
    val actions: List<ContentAction> get() = detail?.let(ContentModerationPolicy::actionsFor).orEmpty()
}

/**
 * Phase 13D: one reported post, perspective or person. Offers only the decisions the server listed (and none to a
 * moderator who is part of the case); a 409 (closed meanwhile, or another moderator first) reloads the case.
 */
class ContentCaseViewModel(
    private val repository: ContentModerationRepository,
    private val caseId: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContentCaseUiState())
    val uiState: StateFlow<ContentCaseUiState> = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val result = repository.caseDetail(caseId)) {
                is ApiResult.Success -> _uiState.update { it.copy(isLoading = false, detail = result.data, failure = null) }
                is ApiResult.Failure -> fail(result)
            }
        }
    }

    fun onShowPhoto(mediaId: String) = _uiState.update { it.copy(shownPhotos = it.shownPhotos + mediaId) }

    fun onAct(action: ContentAction, reason: ContentDecisionReason) {
        val state = _uiState.value
        if (state.isBusy || action !in state.actions || reason !in ContentModerationPolicy.reasonsFor(action)) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, failure = null) }
            when (val result = repository.act(caseId, action, reason)) {
                is ApiResult.Success -> _uiState.update { it.copy(isBusy = false, detail = result.data, decided = true) }
                is ApiResult.Failure -> {
                    fail(result)
                    if (result.error == ApiError.CONFLICT) load()
                }
            }
        }
    }

    fun photoUrl(mediaId: String) = repository.photoUrl(caseId, mediaId)
    fun onDecidedShown() = _uiState.update { it.copy(decided = false) }
    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun fail(failure: ApiResult.Failure) = _uiState.update {
        if (failure.error == ApiError.FORBIDDEN) {
            ContentCaseUiState(isLoading = false, notAllowed = true)
        } else {
            it.copy(isLoading = false, isBusy = false, failure = failure, sessionExpired = failure.isSessionExpired())
        }
    }

    companion object {
        const val ARG_CASE_ID = "caseId"

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                ContentCaseViewModel(container.contentModerationRepository,
                    createSavedStateHandle().get<String>(ARG_CASE_ID).orEmpty())
            }
        }
    }
}
