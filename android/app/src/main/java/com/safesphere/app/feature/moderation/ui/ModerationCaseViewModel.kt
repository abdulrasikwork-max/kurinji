package com.safesphere.app.feature.moderation.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.community.domain.ModerationReason
import com.safesphere.app.feature.moderation.domain.CaseAction
import com.safesphere.app.feature.moderation.domain.CaseDetail
import com.safesphere.app.feature.moderation.domain.CaseParticipant
import com.safesphere.app.feature.moderation.domain.ModerationPolicy
import com.safesphere.app.feature.moderation.domain.ModerationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface CaseState {
    data object Loading : CaseState
    /** 403: not a moderator, or this moderator took part in the signal (someone else must review it). */
    data class NotAllowed(val message: String?) : CaseState
    data class Ready(val detail: CaseDetail, val actions: List<CaseAction>) : CaseState
    data class Failed(val failure: ApiResult.Failure) : CaseState
}

sealed interface CaseEvent {
    data class Decided(val action: CaseAction) : CaseEvent
    data class Failed(val failure: ApiResult.Failure) : CaseEvent
}

data class CaseUiState(
    val state: CaseState = CaseState.Loading,
    val busy: Boolean = false,
    val event: CaseEvent? = null,
    val sessionExpired: Boolean = false
)

/**
 * One moderation case (Phase 7). Offers only decisions ModerationPolicy says can succeed; the server still checks the
 * role, the conflict-of-interest rule, the reason code and that the case is open. A 409 (closed meanwhile, or another
 * moderator acted first) reloads the case so the moderator sees what happened.
 */
class ModerationCaseViewModel(
    private val repository: ModerationRepository,
    private val caseId: String
) : ViewModel() {

    private val _ui = MutableStateFlow(CaseUiState())
    val ui: StateFlow<CaseUiState> = _ui.asStateFlow()

    fun load() {
        viewModelScope.launch { _ui.update { it.copy(state = fetch()) } }
    }

    /** QUARANTINE, DISMISS or REMOVE with a reason code (no free text). */
    fun onDecide(action: CaseAction, reason: ModerationReason) {
        if (action == CaseAction.RESTRICT) return
        decide(action, reason, null, null)
    }

    /** Pause one participant's community contributions; [participant] is known only by its case pseudonym. */
    fun onRestrict(participant: CaseParticipant, reason: ModerationReason, days: Int) {
        val ready = _ui.value.state as? CaseState.Ready ?: return
        if (!ModerationPolicy.canRestrict(ready.detail, participant) || days !in ModerationPolicy.RESTRICT_DAYS) return
        decide(CaseAction.RESTRICT, reason, participant.ref, days)
    }

    /** Phase 9C: pause every member of the coordination group behind this place in one decision (each can appeal). */
    fun onRestrictGroup(reason: ModerationReason, days: Int) {
        val ready = _ui.value.state as? CaseState.Ready ?: return
        if (!ModerationPolicy.canRestrictGroup(ready.detail) || days !in ModerationPolicy.RESTRICT_DAYS) return
        decide(CaseAction.RESTRICT, reason, null, days, wholeGroup = true)
    }

    fun onEventShown() = _ui.update { it.copy(event = null) }

    fun onSessionExpiredHandled() = _ui.update { it.copy(sessionExpired = false) }

    private fun decide(action: CaseAction, reason: ModerationReason, ref: String?, days: Int?, wholeGroup: Boolean = false) {
        val ready = _ui.value.state as? CaseState.Ready ?: return
        if (_ui.value.busy) return
        if (action != CaseAction.RESTRICT && action !in ready.actions) return
        if (reason !in ModerationPolicy.reasonsFor(action)) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            when (val result = repository.act(caseId, action, reason, ref, days, wholeGroup)) {
                is ApiResult.Success -> _ui.update {
                    it.copy(state = ready(result.data), event = CaseEvent.Decided(action))
                }
                is ApiResult.Failure -> {
                    _ui.update {
                        it.copy(event = CaseEvent.Failed(result),
                            sessionExpired = it.sessionExpired || result.isSessionExpired())
                    }
                    if (result.error == ApiError.CONFLICT || result.error == ApiError.FORBIDDEN) {
                        _ui.update { it.copy(state = fetch()) }
                    }
                }
            }
            _ui.update { it.copy(busy = false) }
        }
    }

    private suspend fun fetch(): CaseState = when (val result = repository.caseDetail(caseId)) {
        is ApiResult.Success -> ready(result.data)
        is ApiResult.Failure -> {
            if (result.isSessionExpired()) _ui.update { it.copy(sessionExpired = true) }
            if (result.error == ApiError.FORBIDDEN) CaseState.NotAllowed(result.message) else CaseState.Failed(result)
        }
    }

    private fun ready(detail: CaseDetail) = CaseState.Ready(
        detail.copy(participants = ModerationPolicy.ordered(detail.participants)),
        ModerationPolicy.availableActions(detail)
    )

    companion object {
        fun factory(container: AppContainer, caseId: String) = viewModelFactory {
            initializer { ModerationCaseViewModel(container.moderationRepository, caseId) }
        }
    }
}
