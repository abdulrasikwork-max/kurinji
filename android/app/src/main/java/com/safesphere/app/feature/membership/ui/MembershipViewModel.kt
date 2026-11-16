package com.safesphere.app.feature.membership.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.membership.data.IdPhotoShot
import com.safesphere.app.feature.membership.data.IdPhotoSource
import com.safesphere.app.feature.membership.domain.CommunityStatus
import com.safesphere.app.feature.membership.domain.MembershipRepository
import com.safesphere.app.feature.membership.domain.MembershipState
import com.safesphere.app.feature.membership.domain.Programme
import com.safesphere.app.feature.membership.domain.RollNumberPolicy
import com.safesphere.app.feature.membership.domain.VerificationMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the ID photo is: made → prepared on the phone (no metadata) → sent. */
enum class PhotoStep { IDLE, PREPARING, UPLOADING, UNREADABLE, FAILED }

enum class MembershipNotice { CODE_RENEWED, PHOTO_SENT, WITHDRAWN }

data class MembershipUiState(
    val loading: Boolean = false,
    val status: CommunityStatus? = null,
    val programmes: List<Programme> = emptyList(),
    /** Owner's item 3: in person is preselected (preferred); the ID photo is the alternative. */
    val method: VerificationMethod = VerificationMethod.IN_PERSON,
    val programme: Programme? = null,
    val year: Int? = null,
    val rollProblem: Boolean = false,
    val programmeProblem: Boolean = false,
    val yearProblem: Boolean = false,
    val busy: Boolean = false,
    /** The in-person code, in this screen's memory only (the server keeps its HMAC; FLAG_SECURE on the screen). */
    val code: String? = null,
    val codeMinutes: Long = 0,
    val photo: PhotoStep = PhotoStep.IDLE,
    val failure: ApiResult.Failure? = null,
    val notice: MembershipNotice? = null,
    val sessionExpired: Boolean = false
) {
    override fun toString() = "MembershipUiState(state=${status?.state}, busy=$busy, code=${if (code == null) "none" else "<hidden>"})"
}

/**
 * Phase N3 (A1): "Get verified" and the person's own membership. One request at a time, ONE way (owner's item 3). The
 * roll number is typed, normalised and sent once, never kept in the state; the in-person code is held only here. An
 * ID photo is made by the camera app into the app's cache, re-encoded on the phone, sent, and its file deleted at once
 * (sent or not).
 */
class MembershipViewModel(
    private val repository: MembershipRepository,
    private val photos: IdPhotoSource
) : ViewModel() {

    private val _uiState = MutableStateFlow(MembershipUiState())
    val uiState: StateFlow<MembershipUiState> = _uiState.asStateFlow()

    /** The camera app's target while it is open (survives rotation with this view model; swept otherwise). */
    var pendingShot: IdPhotoShot? = null

    /** Reads the state (and, for the full screen, the programme list once). */
    fun load(withProgrammes: Boolean = true) {
        if (_uiState.value.loading) return
        _uiState.update { it.copy(loading = true) }
        viewModelScope.launch {
            if (withProgrammes && _uiState.value.programmes.isEmpty()) {
                val list = repository.programmes()
                if (list is ApiResult.Success) _uiState.update { it.copy(programmes = list.data) }
            }
            when (val result = repository.status()) {
                is ApiResult.Success -> show(result.data, freshCode = null)
                is ApiResult.Failure -> fail(result)
            }
            _uiState.update { it.copy(loading = false) }
        }
    }

    fun onMethod(method: VerificationMethod) = _uiState.update { it.copy(method = method) }

    fun onProgramme(programme: Programme) = _uiState.update {
        it.copy(programme = programme, programmeProblem = false,
            year = it.year?.takeIf { year -> year in programme.years }, yearProblem = false)
    }

    fun onYear(year: Int) = _uiState.update { it.copy(year = year, yearProblem = false) }

    /** "Ask to be verified": checks on the phone first; the typed roll number is not kept. */
    fun onAsk(typedRoll: String) {
        val state = _uiState.value
        if (state.busy) return
        val rollProblem = !RollNumberPolicy.accepts(typedRoll)
        val programme = state.programme
        val year = state.year
        val yearProblem = year == null || programme == null || year !in programme.years
        if (rollProblem || programme == null || yearProblem) {
            _uiState.update { it.copy(rollProblem = rollProblem, programmeProblem = programme == null,
                yearProblem = programme != null && yearProblem) }
            return
        }
        busy(call = { repository.start(state.method, typedRoll, programme.code, year!!) })
    }

    fun onNewCode() {
        if (_uiState.value.busy) return
        busy({ repository.newCode() }, MembershipNotice.CODE_RENEWED)
    }

    /** The camera app (or, without one, the Photo Picker) returned a photo. */
    fun onPhotoTaken(shot: IdPhotoShot) {
        pendingShot = null
        if (_uiState.value.photo == PhotoStep.PREPARING || _uiState.value.photo == PhotoStep.UPLOADING) {
            photos.discard(shot)
            return
        }
        _uiState.update { it.copy(photo = PhotoStep.PREPARING, failure = null) }
        viewModelScope.launch {
            val jpeg = try {
                photos.prepare(shot)
            } finally {
                photos.discard(shot) // the file of the ID card never outlives this step
            }
            if (jpeg == null) {
                _uiState.update { it.copy(photo = PhotoStep.UNREADABLE) }
                return@launch
            }
            _uiState.update { it.copy(photo = PhotoStep.UPLOADING) }
            when (val result = repository.uploadPhoto(jpeg)) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(photo = PhotoStep.IDLE, notice = MembershipNotice.PHOTO_SENT) }
                    show(result.data, freshCode = null)
                }
                is ApiResult.Failure -> {
                    _uiState.update { it.copy(photo = PhotoStep.FAILED) }
                    fail(result)
                }
            }
        }
    }

    /** The camera was closed without a photo. */
    fun onPhotoCancelled() {
        pendingShot?.let(photos::discard)
        pendingShot = null
    }

    fun onWithdraw() {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, failure = null) }
        viewModelScope.launch {
            when (val result = repository.withdraw()) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(code = null, notice = MembershipNotice.WITHDRAWN) }
                    when (val status = repository.status()) {
                        is ApiResult.Success -> show(status.data, freshCode = null)
                        is ApiResult.Failure -> fail(status)
                    }
                }
                is ApiResult.Failure -> fail(result)
            }
            _uiState.update { it.copy(busy = false) }
        }
    }

    fun onFailureShown() = _uiState.update { it.copy(failure = null) }
    fun onNoticeShown() = _uiState.update { it.copy(notice = null) }
    fun onPhotoStepShown() = _uiState.update {
        if (it.photo == PhotoStep.UNREADABLE || it.photo == PhotoStep.FAILED) it.copy(photo = PhotoStep.IDLE) else it
    }
    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun busy(call: suspend () -> ApiResult<CommunityStatus>, notice: MembershipNotice? = null) {
        _uiState.update { it.copy(busy = true, failure = null) }
        viewModelScope.launch {
            when (val result = call()) {
                is ApiResult.Success -> {
                    show(result.data, freshCode = result.data.request?.code)
                    if (notice != null) _uiState.update { it.copy(notice = notice) }
                }
                is ApiResult.Failure -> fail(result)
            }
            _uiState.update { it.copy(busy = false) }
        }
    }

    /**
     * A fresh code (from start / "show a new code") replaces the one in memory; a plain read keeps it only while the
     * server still says a code is alive, so an expired code is never shown as if it worked.
     */
    private fun show(status: CommunityStatus, freshCode: String?) = _uiState.update { now ->
        val request = status.request
        val code = when {
            status.state != MembershipState.PENDING || request == null -> null
            freshCode != null -> freshCode
            request.codeAlive -> now.code
            else -> null
        }
        now.copy(status = status, code = code,
            codeMinutes = if (code != null) ((request?.codeExpiresInSeconds ?: 0) + 59) / 60 else 0,
            rollProblem = false)
    }

    private fun fail(failure: ApiResult.Failure) =
        _uiState.update { it.copy(failure = failure, sessionExpired = failure.isSessionExpired()) }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { MembershipViewModel(container.membershipRepository, container.idPhotoSource) }
        }
    }
}
