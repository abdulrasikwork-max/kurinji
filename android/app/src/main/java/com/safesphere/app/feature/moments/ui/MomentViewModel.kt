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
    val sessionExpired: Boolean = false
) {
    /** Keep is offered to the author while the moment is live and others can see it. */
    val canKeep: Boolean
        get() = moment != null && moment.mine && !moment.kept && moment.status == MomentStatus.LIVE && !moment.hiddenFromOthers
    val canUnkeep: Boolean get() = moment != null && moment.mine && moment.kept
    val canDelete: Boolean get() = moment != null && moment.mine
}

/**
 * Phase N4: one moment on its own page. The author can keep it on their profile (at most six, the server decides and
 * its words are shown), un-keep it, or delete it (there is no editing: delete and share again). Nothing is recorded
 * about who opened it.
 */
class MomentViewModel(private val repository: MomentRepository) : ViewModel() {

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

    fun onFailureShown() = _uiState.update { it.copy(failure = null) }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

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
            initializer { MomentViewModel(container.momentRepository) }
        }
    }
}
