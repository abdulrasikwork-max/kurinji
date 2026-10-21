package com.safesphere.app.feature.moderation.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.community.domain.AppealStatus
import com.safesphere.app.feature.moderation.domain.AppealItem
import com.safesphere.app.feature.moderation.domain.CaseSummary
import com.safesphere.app.feature.moderation.domain.ModerationPolicy
import com.safesphere.app.feature.moderation.domain.ModerationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface QueueState {
    data object Loading : QueueState
    /** Not a moderator (or no longer one): nothing is shown, whatever was shown before. */
    data object NotAllowed : QueueState
    data class Ready(val cases: List<CaseSummary>, val appeals: List<AppealItem>) : QueueState
    data class Failed(val failure: ApiResult.Failure) : QueueState
}

sealed interface ModerationEvent {
    data class AppealDecided(val decision: AppealStatus) : ModerationEvent
    data class Failed(val failure: ApiResult.Failure) : ModerationEvent
}

data class ModerationUiState(
    val queue: QueueState = QueueState.Loading,
    val busy: Boolean = false,
    val event: ModerationEvent? = null,
    val sessionExpired: Boolean = false
)

/**
 * The moderation queue (Phase 7): open cases (oldest first) and open appeals. It first asks the server whether this
 * account may moderate; the server checks again on every call, so a role removed meanwhile ends in NotAllowed.
 */
class ModerationViewModel(private val repository: ModerationRepository) : ViewModel() {

    private val _ui = MutableStateFlow(ModerationUiState())
    val ui: StateFlow<ModerationUiState> = _ui.asStateFlow()

    fun load() {
        viewModelScope.launch {
            if (_ui.value.queue !is QueueState.Ready) _ui.update { it.copy(queue = QueueState.Loading) }
            _ui.update { it.copy(queue = fetchQueue()) }
        }
    }

    /** Uphold or overturn. Never offered for the moderator's own restriction (the server refuses it too). */
    fun onDecideAppeal(appeal: AppealItem, decision: AppealStatus) {
        if (_ui.value.busy || !ModerationPolicy.canDecide(appeal)) return
        if (decision != AppealStatus.UPHELD && decision != AppealStatus.OVERTURNED) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            when (val result = repository.decideAppeal(appeal.id, decision)) {
                is ApiResult.Success -> _ui.update { it.copy(event = ModerationEvent.AppealDecided(decision)) }
                is ApiResult.Failure -> fail(result)
            }
            _ui.update { it.copy(queue = fetchQueue(), busy = false) }
        }
    }

    fun onEventShown() = _ui.update { it.copy(event = null) }

    fun onSessionExpiredHandled() = _ui.update { it.copy(sessionExpired = false) }

    private suspend fun fetchQueue(): QueueState {
        when (val access = repository.access()) {
            is ApiResult.Failure -> return failedState(access)
            is ApiResult.Success -> if (!access.data.canModerate) return QueueState.NotAllowed
        }
        val cases = when (val result = repository.openCases()) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> return failedState(result)
        }
        val appeals = when (val result = repository.openAppeals()) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> return failedState(result)
        }
        return QueueState.Ready(cases, appeals)
    }

    private fun failedState(failure: ApiResult.Failure): QueueState {
        if (failure.isSessionExpired()) _ui.update { it.copy(sessionExpired = true) }
        return if (failure.error == ApiError.FORBIDDEN) QueueState.NotAllowed else QueueState.Failed(failure)
    }

    private fun fail(failure: ApiResult.Failure) = _ui.update {
        it.copy(event = ModerationEvent.Failed(failure), sessionExpired = it.sessionExpired || failure.isSessionExpired())
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ModerationViewModel(container.moderationRepository) }
        }
    }
}
