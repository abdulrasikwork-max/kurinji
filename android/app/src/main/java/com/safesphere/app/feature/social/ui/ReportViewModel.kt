package com.safesphere.app.feature.social.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.social.domain.ReportReason
import com.safesphere.app.feature.social.domain.ReportRepository
import com.safesphere.app.feature.social.domain.ReportTarget
import com.safesphere.app.feature.social.domain.SocialStanding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One-shot confirmations of the report and review flows. */
enum class ReportMessage { REPORT_SENT, REVIEW_ASKED, CHECK_REVIEW_ASKED }

data class ReportUiState(
    val isBusy: Boolean = false,
    /** The caller's own standing (pause and decisions), loaded where a screen needs it. */
    val standing: SocialStanding? = null,
    val message: ReportMessage? = null,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false,
    /** Phase 13E: own content whose automatic collapse the person asked a moderator to look at (this screen only). */
    val checkReviewAsked: Set<String> = emptySet()
)

/**
 * Phase 13D: reporting a post, a perspective or a person, and the caller's own standing and review requests. Shared by
 * the post, profile and Connections screens. Nothing is stored on the phone; the server answers a report the same way
 * whether it is new or a repeat, and the screen says only that a moderator will look at it.
 */
class ReportViewModel(private val repository: ReportRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportUiState())
    val uiState: StateFlow<ReportUiState> = _uiState.asStateFlow()

    fun report(target: ReportTarget, targetId: String, reason: ReportReason, note: String?) {
        if (_uiState.value.isBusy || !ReportReason.accepts(target, reason, note)) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, failure = null) }
            when (val result = repository.report(target, targetId, reason, note)) {
                is ApiResult.Success -> _uiState.update { it.copy(isBusy = false, message = ReportMessage.REPORT_SENT) }
                is ApiResult.Failure -> fail(result)
            }
        }
    }

    fun loadStanding() {
        viewModelScope.launch {
            when (val result = repository.standing()) {
                is ApiResult.Success -> _uiState.update { it.copy(standing = result.data) }
                // Not knowing the standing only hides the review button; the screen works without it.
                is ApiResult.Failure -> if (result.isSessionExpired()) fail(result)
            }
        }
    }

    /** "Ask for a review" of one decision; another moderator decides. The standing is read again afterwards. */
    fun appeal(decisionId: String) {
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, failure = null) }
            when (val result = repository.appeal(decisionId)) {
                is ApiResult.Success -> _uiState.update { it.copy(isBusy = false, message = ReportMessage.REVIEW_ASKED) }
                is ApiResult.Failure -> fail(result)
            }
            when (val standing = repository.standing()) {
                is ApiResult.Success -> _uiState.update { it.copy(standing = standing.data) }
                is ApiResult.Failure -> Unit
            }
        }
    }

    /** Phase 13E: "Ask for a review" of one's own content that the automatic check collapsed. */
    fun askCheckReview(target: ReportTarget, targetId: String) {
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, failure = null) }
            when (val result = repository.askCheckReview(target, targetId)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isBusy = false, message = ReportMessage.CHECK_REVIEW_ASKED,
                        checkReviewAsked = it.checkReviewAsked + targetId)
                }
                is ApiResult.Failure -> fail(result)
            }
        }
    }

    fun onMessageShown() = _uiState.update { it.copy(message = null) }
    fun onFailureShown() = _uiState.update { it.copy(failure = null) }
    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun fail(failure: ApiResult.Failure) = _uiState.update {
        it.copy(isBusy = false, failure = failure, sessionExpired = failure.isSessionExpired())
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ReportViewModel(container.reportRepository) }
        }
    }
}
