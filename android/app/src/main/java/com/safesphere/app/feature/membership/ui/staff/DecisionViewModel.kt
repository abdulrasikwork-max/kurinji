package com.safesphere.app.feature.membership.ui.staff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.auth.domain.RealNamePolicy
import com.safesphere.app.feature.membership.data.RemoteStaffRepository
import com.safesphere.app.feature.membership.domain.Decision
import com.safesphere.app.feature.membership.domain.RefusalReason
import com.safesphere.app.feature.membership.domain.RequestDetail
import com.safesphere.app.feature.membership.domain.RollNumberPolicy
import com.safesphere.app.feature.membership.domain.StaffRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DecideStep { CODE, DETAILS, DONE }

class DecideUiState(
    val step: DecideStep = DecideStep.CODE,
    val byPhoto: Boolean = false,
    val detail: RequestDetail? = null,
    /** The decrypted ID photo: this screen's memory only, never written to the phone; gone when the screen closes. */
    val photo: ByteArray? = null,
    val photoMissing: Boolean = false,
    val busy: Boolean = false,
    val codeProblem: Boolean = false,
    val rollProblem: Boolean = false,
    val nameProblem: Boolean = false,
    val decision: Decision? = null,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
) {
    fun copy(
        step: DecideStep = this.step, byPhoto: Boolean = this.byPhoto, detail: RequestDetail? = this.detail,
        photo: ByteArray? = this.photo, photoMissing: Boolean = this.photoMissing, busy: Boolean = this.busy,
        codeProblem: Boolean = this.codeProblem, rollProblem: Boolean = this.rollProblem,
        nameProblem: Boolean = this.nameProblem, decision: Decision? = this.decision,
        failure: ApiResult.Failure? = this.failure, sessionExpired: Boolean = this.sessionExpired
    ) = DecideUiState(step, byPhoto, detail, photo, photoMissing, busy, codeProblem, rollProblem, nameProblem, decision,
        failure, sessionExpired)

    override fun toString() = "DecideUiState(step=$step, byPhoto=$byPhoto, busy=$busy, photo=${photo != null})"
}

/**
 * Phase N3 (A2): one decision, in person (a verifier or admin types the applicant's code, then the roll number from
 * the ID card they hold) or by an ID photo (admins only; the photo is shown from memory). Approve may carry the name as
 * spelled on the ID (owner's item 8: logged and shown to the person). Refuse needs a reason. Nobody decides their own
 * request: the server refuses it.
 */
class DecisionViewModel(private val repository: StaffRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(DecideUiState())
    val uiState: StateFlow<DecideUiState> = _uiState.asStateFlow()

    private var started = false

    /** In person: [ref] null (the code comes first). By photo: the request's [ref] from the admin's queue. */
    fun start(ref: String?, byPhoto: Boolean) {
        if (started) return
        started = true
        if (!byPhoto || ref == null) return
        _uiState.value = DecideUiState(byPhoto = true, busy = true)
        viewModelScope.launch {
            when (val queue = repository.photoQueue()) {
                is ApiResult.Failure -> fail(queue)
                is ApiResult.Success -> {
                    val detail = queue.data.firstOrNull { it.ref == ref }
                    if (detail == null) {
                        // Decided or withdrawn meanwhile.
                        _uiState.update { it.copy(busy = false, failure = ApiResult.Failure(ApiError.NOT_FOUND)) }
                        return@launch
                    }
                    _uiState.update { it.copy(step = DecideStep.DETAILS, detail = detail) }
                    when (val photo = repository.photo(ref)) {
                        is ApiResult.Success -> _uiState.update { it.copy(photo = photo.data) }
                        is ApiResult.Failure -> _uiState.update { it.copy(photoMissing = true, failure = photo) }
                    }
                    _uiState.update { it.copy(busy = false) }
                }
            }
        }
    }

    fun onLookup(code: String) {
        if (_uiState.value.busy) return
        if (RemoteStaffRepository.cleanCode(code) == null) {
            _uiState.update { it.copy(codeProblem = true) }
            return
        }
        _uiState.update { it.copy(busy = true, codeProblem = false, failure = null) }
        viewModelScope.launch {
            when (val result = repository.lookup(code)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(step = DecideStep.DETAILS, detail = result.data, busy = false)
                }
                is ApiResult.Failure -> fail(result)
            }
        }
    }

    /** Approve: the roll number typed from the ID card; [correctedName] only when it differs from the shown name. */
    fun onApprove(typedRoll: String, correctedName: String) {
        val state = _uiState.value
        val detail = state.detail ?: return
        if (state.busy) return
        val name = RealNamePolicy.clean(correctedName).takeIf { it.isNotEmpty() && it != detail.name }
        val rollProblem = !RollNumberPolicy.accepts(typedRoll)
        val nameProblem = name != null && RealNamePolicy.check(name) != RealNamePolicy.Result.OK
        if (rollProblem || nameProblem) {
            _uiState.update { it.copy(rollProblem = rollProblem, nameProblem = nameProblem) }
            return
        }
        decide(detail, approve = true, roll = typedRoll, name = name, reason = null)
    }

    fun onRefuse(reason: RefusalReason) {
        val state = _uiState.value
        val detail = state.detail ?: return
        if (state.busy) return
        decide(detail, approve = false, roll = null, name = null, reason = reason)
    }

    /** In person: ready for the next person (nothing about the last one is kept). */
    fun onNext() {
        _uiState.value = DecideUiState()
    }

    fun onFailureShown() = _uiState.update { it.copy(failure = null) }
    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun decide(detail: RequestDetail, approve: Boolean, roll: String?, name: String?, reason: RefusalReason?) {
        _uiState.update { it.copy(busy = true, rollProblem = false, nameProblem = false, failure = null) }
        viewModelScope.launch {
            val result = if (_uiState.value.byPhoto) repository.decideByPhoto(detail.ref, approve, roll, name, reason)
            else repository.decideInPerson(detail.ref, approve, roll, name, reason)
            when (result) {
                // The decision is made: the photo and the details leave the screen's memory.
                is ApiResult.Success -> _uiState.update {
                    it.copy(step = DecideStep.DONE, decision = result.data, photo = null, busy = false)
                }
                // A 409 (the roll number does not match) keeps the details: retype it or refuse.
                is ApiResult.Failure -> fail(result)
            }
        }
    }

    private fun fail(failure: ApiResult.Failure) = _uiState.update {
        it.copy(busy = false, failure = failure, sessionExpired = failure.isSessionExpired())
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { DecisionViewModel(container.staffRepository) }
        }
    }
}
