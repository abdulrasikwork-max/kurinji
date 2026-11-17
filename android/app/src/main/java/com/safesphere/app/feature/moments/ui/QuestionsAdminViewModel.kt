package com.safesphere.app.feature.moments.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.core.network.needsMembership
import com.safesphere.app.feature.moments.domain.QuestionAdminRepository
import com.safesphere.app.feature.moments.domain.QuestionRules
import com.safesphere.app.feature.moments.domain.ScheduledQuestion
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class QuestionsAdminUiState(
    val loading: Boolean = true,
    val questions: List<ScheduledQuestion> = emptyList(),
    /** The day being written for; null until chosen (the next free day is suggested). */
    val day: LocalDate? = null,
    val text: String = "",
    val busy: Boolean = false,
    /** 403: not an admin (any more), or no active membership. The screen says so and offers nothing. */
    val notAllowed: Boolean = false,
    val needsMembership: Boolean = false,
    /** One-time: a question was scheduled (the field is cleared). */
    val scheduled: Boolean = false,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false,
    val today: LocalDate? = null
) {
    val dayTaken: Boolean get() = day != null && QuestionRules.taken(day, questions)

    val canSchedule: Boolean
        get() = !loading && !busy && !notAllowed && day != null && !dayTaken && QuestionRules.textFits(text)
}

/**
 * Phase N4 (A3): admins write and schedule the daily question (one per India day, today to 60 days ahead) and delete
 * one (its answers stay as plain moments). No editing. The server checks the role and every rule again; its 409 and 403
 * words are shown as they are.
 */
class QuestionsAdminViewModel(
    private val repository: QuestionAdminRepository,
    private val now: () -> Instant = Instant::now
) : ViewModel() {

    private val _uiState = MutableStateFlow(QuestionsAdminUiState())
    val uiState: StateFlow<QuestionsAdminUiState> = _uiState.asStateFlow()

    fun load() {
        _uiState.update { it.copy(loading = true, failure = null, today = QuestionRules.today(now())) }
        viewModelScope.launch {
            when (val result = repository.list()) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(loading = false, questions = result.data, notAllowed = false,
                        day = it.day ?: nextFreeDay(result.data))
                }
                is ApiResult.Failure -> fail(result)
            }
        }
    }

    fun onDay(day: LocalDate) {
        if (QuestionRules.dayAllowed(day, now())) _uiState.update { it.copy(day = day) }
    }

    fun onText(text: String) = _uiState.update { if (it.text == text) it else it.copy(text = text) }

    fun onSchedule() {
        val state = _uiState.value
        val day = state.day ?: return
        if (!state.canSchedule || !QuestionRules.dayAllowed(day, now())) return
        _uiState.update { it.copy(busy = true, failure = null) }
        viewModelScope.launch {
            when (val result = repository.schedule(day, state.text)) {
                is ApiResult.Success -> _uiState.update {
                    val list = (it.questions + result.data).sortedBy { q -> q.day }
                    it.copy(busy = false, questions = list, text = "", scheduled = true, day = nextFreeDay(list))
                }
                is ApiResult.Failure -> fail(result)
            }
        }
    }

    fun onDelete(question: ScheduledQuestion) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, failure = null) }
        viewModelScope.launch {
            when (val result = repository.delete(question.id)) {
                is ApiResult.Success -> _uiState.update { it.copy(busy = false, questions = it.questions - question) }
                // Already gone is what the admin wanted.
                is ApiResult.Failure -> if (result.error == ApiError.NOT_FOUND) {
                    _uiState.update { it.copy(busy = false, questions = it.questions - question) }
                } else {
                    fail(result)
                }
            }
        }
    }

    fun onScheduledShown() = _uiState.update { it.copy(scheduled = false) }
    fun onFailureShown() = _uiState.update { it.copy(failure = null) }
    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    /** The first day from today on that has no question yet (null when all 61 are taken). */
    private fun nextFreeDay(questions: List<ScheduledQuestion>): LocalDate? {
        val today = QuestionRules.today(now())
        return (0..QuestionRules.DAYS_AHEAD).map { today.plusDays(it) }.firstOrNull { !QuestionRules.taken(it, questions) }
    }

    private fun fail(failure: ApiResult.Failure) = _uiState.update {
        it.copy(loading = false, busy = false, failure = failure,
            notAllowed = it.notAllowed || (failure.error == ApiError.FORBIDDEN && !failure.needsMembership()),
            needsMembership = failure.needsMembership(), sessionExpired = failure.isSessionExpired())
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { QuestionsAdminViewModel(container.questionAdminRepository) }
        }
    }
}
