package com.safesphere.app.feature.moments.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.core.network.needsMembership
import com.safesphere.app.feature.moments.data.MomentShot
import com.safesphere.app.feature.moments.data.MomentPhotoSource
import com.safesphere.app.feature.moments.domain.AudienceMemory
import com.safesphere.app.feature.moments.domain.DailyQuestion
import com.safesphere.app.feature.moments.domain.DraftRules
import com.safesphere.app.feature.moments.domain.HappenedRef
import com.safesphere.app.feature.moments.domain.Moment
import com.safesphere.app.feature.moments.domain.MomentAudience
import com.safesphere.app.feature.moments.domain.MomentDraft
import com.safesphere.app.feature.moments.domain.MomentKind
import com.safesphere.app.feature.moments.domain.MomentRepository
import com.safesphere.app.feature.moments.domain.SharingStatus
import com.safesphere.app.feature.moments.domain.UploadedPhoto
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The photo of the draft: [jpeg] is the copy prepared on the phone (shown as the preview, memory only); [uploaded] is
 * set once the server has it; [failure] = the upload did not work (the person can try again or remove it).
 */
class DraftPhoto(val jpeg: ByteArray, val uploaded: UploadedPhoto? = null, val failure: ApiResult.Failure? = null) {
    val uploading: Boolean get() = uploaded == null && failure == null
    override fun toString() = "DraftPhoto(uploaded=${uploaded != null}, failed=${failure != null})"
}

data class CreateUiState(
    val loading: Boolean = true,
    /** 403 MEMBERSHIP_NEEDED (or no membership): Create offers "Get verified" instead of a draft. */
    val needsMembership: Boolean = false,
    val kind: MomentKind = MomentKind.SAY,
    /** D5: null until chosen (the first time), then the last choice on this phone. */
    val audience: MomentAudience? = null,
    val text: String = "",
    val where: String = "",
    /** A Who's in?'s clock time; it means its next occurrence within 24 hours ([DraftRules.nextPlanTime]). */
    val planTime: LocalTime? = null,
    /** Today's question, when this moment answers it. */
    val question: DailyQuestion? = null,
    /** N5 (N-92): "This happened" from this plan: a Say with a photo taken now, with the plan's audience. */
    val happened: HappenedRef? = null,
    val newMemberUntil: Instant? = null,
    val photo: DraftPhoto? = null,
    val preparingPhoto: Boolean = false,
    /** One-time: the camera's photo could not be read. */
    val photoUnreadable: Boolean = false,
    val sharing: Boolean = false,
    /** One-time: shared; the screen says what happened and closes. */
    val shared: Moment? = null,
    /** Shared: the screen closes (also after a rotation lost the dialog that said what happened). */
    val finished: Boolean = false,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
) {
    /** The room left, shown only to the writer and only near the end (negative = over). */
    val roomLeft: Int? get() = DraftRules.roomLeft(text)

    /** M2: told before sharing that a photo for all of MIET goes to a moderator first (not for My people). */
    val showsNewMemberLine: Boolean get() = newMemberUntil != null && audience != MomentAudience.MY_PEOPLE && happened == null

    val hasDraft: Boolean get() = !finished && (text.isNotBlank() || photo != null || where.isNotBlank() || planTime != null)

    val canShare: Boolean
        get() = !loading && !finished && !needsMembership && (audience != null || happened != null) && !sharing &&
            !preparingPhoto && (photo == null || photo.uploaded != null) && DraftRules.wordsFit(text, photo != null) &&
            (happened == null || photo?.uploaded != null) &&
            (kind == MomentKind.SAY || DraftRules.whereFits(where))
}

/**
 * Phase N4 (A2): Create. Opens ready to type; Say or Who's in? (an optional time within 24 hours and a plain-text
 * "where"); audience MIET or My people (no default the first time, then the last choice on this phone); at most one
 * photo, taken now with the camera app, prepared on the phone and uploaded at once (so sharing is quick); removed or
 * replaced uploads are deleted on the server. Nothing of the draft is stored on the phone. The server checks every
 * rule again and its answer says what happened.
 */
class CreateViewModel(
    private val repository: MomentRepository,
    private val photos: MomentPhotoSource,
    private val audienceMemory: AudienceMemory,
    private val sharingStatus: suspend () -> ApiResult<SharingStatus>,
    /** Outlives the screen: deleting an upload nobody will use must not be cut off when Create closes. */
    private val background: CoroutineScope,
    private val savedState: SavedStateHandle,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateUiState())
    val uiState: StateFlow<CreateUiState> = _uiState.asStateFlow()

    private var started = false

    /** Each new photo gets a number, so a late upload of a photo already replaced is discarded, never attached. */
    private var photoGeneration = 0

    /** The camera app is open with this target (also kept in [savedState] across the app being closed). */
    var pendingShot: MomentShot? = null
        private set

    fun start(question: DailyQuestion?, happened: HappenedRef? = null) {
        if (started) return
        started = true
        // This happened is always a Say and answers no question (N-92).
        _uiState.update { if (happened != null) it.copy(happened = happened, kind = MomentKind.SAY) else it.copy(question = question) }
        viewModelScope.launch {
            val remembered = runCatching { audienceMemory.last() }.getOrNull()
            when (val status = sharingStatus()) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(loading = false, needsMembership = !status.data.member, audience = it.audience ?: remembered,
                        newMemberUntil = status.data.newMemberUntil?.takeIf { until -> until.isAfter(now()) })
                }
                // Unknown: the draft opens anyway; the server decides when it is shared.
                is ApiResult.Failure -> _uiState.update {
                    it.copy(loading = false, audience = it.audience ?: remembered, sessionExpired = status.isSessionExpired())
                }
            }
        }
        // The app was closed while the camera app was open: the photo it wrote is picked up again.
        savedState.get<String>(KEY_SHOT)?.let { name -> pendingShot = photos.shotNamed(name) }
    }

    fun onKind(kind: MomentKind) = _uiState.update { if (it.happened != null) it else it.copy(kind = kind) }

    fun onAudience(audience: MomentAudience) = _uiState.update { it.copy(audience = audience) }

    fun onText(text: String) = _uiState.update { if (it.text == text) it else it.copy(text = text) }

    fun onWhere(where: String) = _uiState.update { if (it.where == where) it else it.copy(where = where) }

    fun onPlanTime(time: LocalTime?) = _uiState.update { it.copy(planTime = time) }

    fun onRemoveQuestion() = _uiState.update { it.copy(question = null) }

    /** The plan's moment as it would be sent now (later today, otherwise tomorrow). */
    fun planAt(): Instant? = _uiState.value.planTime?.let { DraftRules.nextPlanTime(it, now(), zone()) }

    fun nowInstant(): Instant = now()

    /** The camera app is about to write into [shot]. */
    fun onCameraOpening(shot: MomentShot) {
        pendingShot = shot
        savedState[KEY_SHOT] = shot.name
    }

    /** The camera app came back ([taken] = a photo was saved into the pending target). */
    fun onCameraResult(taken: Boolean) {
        val shot = pendingShot ?: return
        pendingShot = null
        savedState.remove<String>(KEY_SHOT)
        if (!taken) {
            photos.discard(shot)
            return
        }
        _uiState.update { it.copy(preparingPhoto = true) }
        viewModelScope.launch {
            val jpeg = photos.prepare(shot)
            photos.discard(shot) // the camera's file never outlives this step
            if (jpeg == null) {
                _uiState.update { it.copy(preparingPhoto = false, photoUnreadable = true) }
                return@launch
            }
            forgetUpload(_uiState.value.photo)
            _uiState.update { it.copy(preparingPhoto = false, photo = DraftPhoto(jpeg)) }
            upload(jpeg)
        }
    }

    fun onRetryUpload() {
        val photo = _uiState.value.photo ?: return
        if (photo.failure == null) return
        _uiState.update { it.copy(photo = DraftPhoto(photo.jpeg)) }
        upload(photo.jpeg)
    }

    fun onRemovePhoto() {
        val photo = _uiState.value.photo ?: return
        photoGeneration++
        forgetUpload(photo)
        _uiState.update { it.copy(photo = null) }
    }

    fun onPhotoUnreadableShown() = _uiState.update { it.copy(photoUnreadable = false) }

    fun onShare() {
        val state = _uiState.value
        if (!state.canShare) return
        val happened = state.happened
        // This happened sends no audience (the server uses the plan's); the draft field is then unused.
        val audience = state.audience ?: if (happened != null) MomentAudience.COMMUNITY else return
        val whosIn = state.kind == MomentKind.WHOS_IN
        val draft = MomentDraft(
            kind = state.kind,
            audience = audience,
            text = state.text.trim(),
            mediaId = state.photo?.uploaded?.mediaId,
            planAt = if (whosIn) planAt() else null,
            planWhere = if (whosIn) state.where.trim().ifEmpty { null } else null,
            questionId = state.question?.id,
            happenedOf = happened?.id
        )
        _uiState.update { it.copy(sharing = true, failure = null) }
        viewModelScope.launch {
            when (val result = repository.share(draft)) {
                is ApiResult.Success -> {
                    if (happened == null) runCatching { audienceMemory.remember(audience) }
                    // The photo is part of the moment now: nothing to clean up when the screen closes.
                    _uiState.update { it.copy(sharing = false, photo = null, shared = result.data, finished = true) }
                }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(sharing = false, failure = result, needsMembership = result.needsMembership(),
                        sessionExpired = result.isSessionExpired())
                }
            }
        }
    }

    /** The person leaves without sharing: the uploaded photo is deleted on the server (it would go after 24 h anyway). */
    fun onDiscard() {
        photoGeneration++
        forgetUpload(_uiState.value.photo)
        _uiState.update { it.copy(photo = null, text = "", where = "", planTime = null) }
    }

    fun onSharedHandled() = _uiState.update { it.copy(shared = null) }

    fun onFailureShown() = _uiState.update { it.copy(failure = null) }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    override fun onCleared() {
        // Closed some other way (back stack cleared): an unused upload is still removed.
        forgetUpload(_uiState.value.photo)
        pendingShot?.let(photos::discard)
    }

    private fun upload(jpeg: ByteArray) {
        val generation = ++photoGeneration
        viewModelScope.launch {
            val result = repository.uploadPhoto(jpeg)
            if (generation != photoGeneration) {
                // Replaced or removed meanwhile: this upload is never used.
                if (result is ApiResult.Success) discardLater(result.data.mediaId)
                return@launch
            }
            when (result) {
                is ApiResult.Success -> _uiState.update { it.copy(photo = DraftPhoto(jpeg, uploaded = result.data)) }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(photo = DraftPhoto(jpeg, failure = result), needsMembership = it.needsMembership || result.needsMembership(),
                        sessionExpired = result.isSessionExpired())
                }
            }
        }
    }

    private fun forgetUpload(photo: DraftPhoto?) {
        photo?.uploaded?.let { discardLater(it.mediaId) }
    }

    private fun discardLater(mediaId: String) {
        background.launch { repository.discardPhoto(mediaId) }
    }

    companion object {
        private const val KEY_SHOT = "create_pending_shot"

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                CreateViewModel(container.momentRepository, container.momentPhotoFiles, container.audienceMemory,
                    container::sharingStatus, container.backgroundScope, createSavedStateHandle())
            }
        }
    }
}
