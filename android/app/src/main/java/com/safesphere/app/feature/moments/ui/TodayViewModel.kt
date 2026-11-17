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
import com.safesphere.app.feature.moments.domain.DailyQuestion
import com.safesphere.app.feature.moments.domain.Moment
import com.safesphere.app.feature.moments.domain.MomentRepository
import com.safesphere.app.feature.moments.domain.People
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TodayUiState(
    val people: People = People.ALL,
    val question: DailyQuestion? = null,
    val moments: List<Moment> = emptyList(),
    /** The first page of the chosen view has been read at least once. */
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    /** The wall has an end: every moment of the last 24 hours the viewer may see has been shown. */
    val ended: Boolean = false,
    val failure: ApiResult.Failure? = null,
    /** 403 MEMBERSHIP_NEEDED: Today is for verified members; the screen offers "Get verified". */
    val needsMembership: Boolean = false,
    val sessionExpired: Boolean = false
)

/**
 * Phase N4: Today, the community's wall of the last 24 hours (docs/PHASE_N4.md §3). Newest first, page by page as the
 * person nears the end, and then it ENDS. Two views: everyone at MIET, or "My people". Nothing is counted, ranked or
 * recorded about who looked; nothing is kept on the phone (the list lives in this view model only). A refresh keeps
 * what is on the screen until the new page arrives.
 */
class TodayViewModel(
    private val repository: MomentRepository,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) : ViewModel() {

    private val _uiState = MutableStateFlow(TodayUiState())
    val uiState: StateFlow<TodayUiState> = _uiState.asStateFlow()

    private var cursor: String? = null
    private var load: Job? = null
    private var loadedAtMs = 0L
    private var started = false

    fun start() {
        if (started) return
        started = true
        firstPage(clear = true)
    }

    /** The other view was chosen: its wall replaces this one. */
    fun onPeople(people: People) {
        if (people == _uiState.value.people) return
        _uiState.update { it.copy(people = people, moments = emptyList(), loaded = false, ended = false, question = it.question) }
        firstPage(clear = true)
    }

    /** Pulled down, the tab tapped again, "Try again": read again; what is shown stays until the answer arrives. */
    fun refresh() {
        if (!started) return
        firstPage(clear = false)
    }

    /** The screen is shown again: read again when the wall is older than [STALE_MS] (a moment may have ended). */
    fun onShown() {
        if (started && _uiState.value.loaded && nowMs() - loadedAtMs > STALE_MS) refresh()
    }

    /** The person came back from sharing or deleting: the wall has changed. */
    fun onChanged() = refresh()

    fun onNearEnd() {
        val state = _uiState.value
        if (!state.loaded || state.loading || state.loadingMore || state.ended || cursor == null) return
        val people = state.people
        _uiState.update { it.copy(loadingMore = true) }
        load = viewModelScope.launch {
            when (val result = repository.today(people, cursor)) {
                is ApiResult.Success -> {
                    cursor = result.data.nextCursor
                    _uiState.update { now ->
                        val known = now.moments.mapTo(HashSet()) { it.id }
                        now.copy(moments = now.moments + result.data.moments.filterNot { it.id in known },
                            question = result.data.question ?: now.question, loadingMore = false,
                            ended = result.data.nextCursor == null, failure = null)
                    }
                }
                // The moment the cursor named is gone (deleted meanwhile): start again from the top.
                is ApiResult.Failure -> if (result.error == ApiError.VALIDATION) {
                    _uiState.update { it.copy(loadingMore = false) }
                    firstPage(clear = false)
                } else {
                    fail(result)
                }
            }
        }
    }

    /** A moment was deleted from its page: it leaves the wall at once. */
    fun onRemoved(id: String) = _uiState.update { s -> s.copy(moments = s.moments.filterNot { it.id == id }) }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun firstPage(clear: Boolean) {
        load?.cancel()
        val people = _uiState.value.people
        _uiState.update {
            it.copy(loading = true, loadingMore = false, failure = null, needsMembership = false,
                moments = if (clear) emptyList() else it.moments)
        }
        load = viewModelScope.launch {
            when (val result = repository.today(people, null)) {
                is ApiResult.Success -> {
                    cursor = result.data.nextCursor
                    loadedAtMs = nowMs()
                    _uiState.update {
                        it.copy(question = result.data.question, moments = result.data.moments, loaded = true,
                            loading = false, ended = result.data.nextCursor == null, failure = null)
                    }
                }
                is ApiResult.Failure -> fail(result)
            }
        }
    }

    private fun fail(failure: ApiResult.Failure) = _uiState.update {
        val membership = failure.needsMembership()
        it.copy(loading = false, loadingMore = false, failure = if (membership) null else failure,
            needsMembership = membership, sessionExpired = failure.isSessionExpired(),
            moments = if (membership) emptyList() else it.moments,
            question = if (membership) null else it.question)
    }

    companion object {
        /** A wall older than this is read again when the screen comes back (moments end; new ones arrive). */
        const val STALE_MS = 2 * 60 * 1000L

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { TodayViewModel(container.momentRepository) }
        }
    }
}
