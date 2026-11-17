package com.safesphere.app.feature.moments.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.moments.domain.Moment
import com.safesphere.app.feature.moments.domain.MomentRepository
import com.safesphere.app.feature.moments.domain.MomentStatus
import com.safesphere.app.feature.moments.domain.PlanPeople
import com.safesphere.app.feature.moments.domain.ResponseRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MomentUiState(
    val moment: Moment? = null,
    val loading: Boolean = false,
    /** Gone, ended, or not for this reader: the same words for all three (the server answers the same). */
    val gone: Boolean = false,
    /** A covered moment the reader chose to read. */
    val open: Boolean = false,
    val busy: Boolean = false,
    /** The author deleted it: the screen closes. */
    val removed: Boolean = false,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false,
    /** N5: the people sheet's lists (the author's, or "who is in" for someone in the plan); null until read. */
    val people: PlanPeople? = null,
    val peopleLoading: Boolean = false,
    /** N5: a response changed the moment; the wall behind reads it again (one-shot for the screen). */
    val changed: Boolean = false,
    /** N5: the server suggested one calm support line after the author's follow-up (one-shot, never stored). */
    val support: Boolean = false
) {
    /** Keep is offered to the author while the moment is live and others can see it. */
    val canKeep: Boolean
        get() = moment != null && moment.mine && !moment.kept && moment.status == MomentStatus.LIVE && !moment.hiddenFromOthers
    val canUnkeep: Boolean get() = moment != null && moment.mine && moment.kept
    val canDelete: Boolean get() = moment != null && moment.mine

    /** N5: what the reader may do with the moment now (the server decides again on every tap). */
    val actions: ResponseActions get() = moment?.let(ResponseLabels::actions) ?: ResponseActions.NONE
}

/**
 * Phase N4: one moment on its own page. The author can keep it on their profile (at most six, the server decides and
 * its words are shown), un-keep it, or delete it (there is no editing: delete and share again). Nothing is recorded
 * about who opened it.
 *
 * N5: Same and Say more (toggles), the author's one follow-up, I'm in and taking it back, the author's people (Add,
 * Not this time, Bring back, Remove) and Call it off. Every answer is the moment (or the lists) as the server has it
 * now, so the screen never guesses; a refusal shows the server's own words.
 */
class MomentViewModel(
    private val repository: MomentRepository,
    private val responses: ResponseRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MomentUiState())
    val uiState: StateFlow<MomentUiState> = _uiState.asStateFlow()

    private var id: String? = null

    fun load(momentId: String) {
        if (id == momentId && (_uiState.value.moment != null || _uiState.value.loading)) return
        id = momentId
        _uiState.update { it.copy(loading = true, failure = null, gone = false) }
        viewModelScope.launch {
            when (val result = repository.moment(momentId)) {
                is ApiResult.Success -> _uiState.update { it.copy(moment = result.data, loading = false) }
                is ApiResult.Failure -> if (result.error == ApiError.NOT_FOUND) {
                    _uiState.update { it.copy(loading = false, gone = true, moment = null) }
                } else {
                    fail(result)
                }
            }
        }
    }

    fun retry() {
        val momentId = id ?: return
        id = null
        load(momentId)
    }

    fun onUncover() = _uiState.update { it.copy(open = true) }

    fun onKeep() = change { momentId ->
        when (val result = repository.keep(momentId)) {
            is ApiResult.Success -> _uiState.update { it.copy(moment = result.data, busy = false, failure = null) }
            is ApiResult.Failure -> fail(result)
        }
    }

    fun onUnkeep() = change { momentId ->
        when (val result = repository.unkeep(momentId)) {
            is ApiResult.Success -> _uiState.update { it.copy(moment = it.moment?.copy(kept = false), busy = false, failure = null) }
            is ApiResult.Failure -> fail(result)
        }
    }

    fun onDelete() = change { momentId ->
        when (val result = repository.delete(momentId)) {
            is ApiResult.Success -> _uiState.update { it.copy(busy = false, removed = true) }
            // Already gone is what the person wanted.
            is ApiResult.Failure -> if (result.error == ApiError.NOT_FOUND) {
                _uiState.update { it.copy(busy = false, removed = true) }
            } else {
                fail(result)
            }
        }
    }

    // ---- N5: responses ----------------------------------------------------------------------------------------------

    /** Same on or off (D14): the opposite of what the reader said before. */
    fun onSame() {
        val on = _uiState.value.moment?.responses?.same?.mine != true
        respond { responses.same(it, on) }
    }

    /** Say more on or off (D15). */
    fun onSayMore() {
        val on = _uiState.value.moment?.responses?.sayMore != true
        respond { responses.sayMore(it, on) }
    }

    /** The author's one follow-up (D15). Blank words never leave the phone. */
    fun onAddMore(text: String) {
        if (text.isBlank()) return
        respond { responses.addMore(it, text) }
    }

    fun onAskIn() = respond { responses.askIn(it) }

    /** Takes an ask back, or leaves the plan; the moment is read again to show where the reader stands now. */
    fun onWithdraw() = change { momentId ->
        when (val result = responses.leave(momentId)) {
            is ApiResult.Success -> reread(momentId)
            // Nothing left to take back: read where the reader stands.
            is ApiResult.Failure -> if (result.error == ApiError.NOT_FOUND) reread(momentId) else fail(result)
        }
    }

    fun onCallOff() = respond { responses.callOff(it) }

    /** The people sheet opens: read the lists (again each time, they change while it is closed). */
    fun loadPeople() {
        val momentId = _uiState.value.moment?.id ?: return
        if (_uiState.value.peopleLoading) return
        _uiState.update { it.copy(peopleLoading = true) }
        viewModelScope.launch { people(momentId, responses.people(momentId)) }
    }

    fun onAdd(ref: String) = peopleChange { responses.add(it, ref) }

    fun onSetAside(ref: String, aside: Boolean) = peopleChange { responses.setAside(it, ref, aside) }

    fun onRemove(ref: String) = peopleChange { responses.remove(it, ref) }

    fun onPeopleClosed() = _uiState.update { it.copy(people = null) }

    fun onChangedHandled() = _uiState.update { it.copy(changed = false) }

    fun onSupportShown() = _uiState.update { it.copy(support = false) }

    fun onFailureShown() = _uiState.update { it.copy(failure = null) }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun respond(call: suspend (String) -> ApiResult<Moment>) = change { momentId ->
        when (val result = call(momentId)) {
            is ApiResult.Success -> _uiState.update {
                it.copy(moment = result.data, busy = false, failure = null, changed = true,
                    support = it.support || result.data.supportSuggested)
            }
            is ApiResult.Failure -> if (result.error == ApiError.NOT_FOUND) gone() else fail(result)
        }
    }

    private fun peopleChange(call: suspend (String) -> ApiResult<PlanPeople>) = change { momentId ->
        val result = call(momentId)
        people(momentId, result)
        // The author's lines on the page follow the lists.
        if (result is ApiResult.Success) reread(momentId)
    }

    private fun people(momentId: String, result: ApiResult<PlanPeople>) {
        if (_uiState.value.moment?.id != momentId) return
        when (result) {
            is ApiResult.Success -> _uiState.update { it.copy(people = result.data, peopleLoading = false, busy = false) }
            is ApiResult.Failure -> _uiState.update {
                it.copy(peopleLoading = false, busy = false, failure = result, sessionExpired = result.isSessionExpired())
            }
        }
    }

    private suspend fun reread(momentId: String) {
        when (val result = repository.moment(momentId)) {
            is ApiResult.Success -> _uiState.update { it.copy(moment = result.data, busy = false, failure = null, changed = true) }
            is ApiResult.Failure -> if (result.error == ApiError.NOT_FOUND) gone() else fail(result)
        }
    }

    /** It went (deleted, or no longer for this reader): the page says so in the one calm line. */
    private fun gone() = _uiState.update {
        it.copy(busy = false, loading = false, gone = true, moment = null, people = null, changed = true)
    }

    private fun change(action: suspend (String) -> Unit) {
        val momentId = _uiState.value.moment?.id ?: return
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, failure = null) }
        viewModelScope.launch { action(momentId) }
    }

    private fun fail(failure: ApiResult.Failure) = _uiState.update {
        it.copy(loading = false, busy = false, failure = failure, sessionExpired = failure.isSessionExpired())
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { MomentViewModel(container.momentRepository, container.responseRepository) }
        }
    }
}
