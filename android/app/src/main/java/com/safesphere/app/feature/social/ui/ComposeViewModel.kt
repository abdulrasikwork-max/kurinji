package com.safesphere.app.feature.social.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.social.data.PhotoSource
import com.safesphere.app.feature.social.data.PlaceMemory
import com.safesphere.app.feature.social.domain.Audience
import com.safesphere.app.feature.social.domain.City
import com.safesphere.app.feature.social.domain.CommentPolicy
import com.safesphere.app.feature.social.domain.ComposerIssue
import com.safesphere.app.feature.social.domain.ComposerPolicy
import com.safesphere.app.feature.social.domain.Daypart
import com.safesphere.app.feature.social.domain.ExperienceRepository
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.Place
import com.safesphere.app.feature.social.domain.PostDraft
import com.safesphere.app.feature.social.domain.PostType
import com.safesphere.app.feature.social.domain.Precision
import com.safesphere.app.feature.social.domain.PrivatePlacePolicy
import java.time.Clock
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One picked photo: being prepared and uploaded, ready (with its server id), or failed. */
data class ComposePhoto(val key: String, val mediaId: String? = null, val failed: Boolean = false) {
    val uploading: Boolean get() = mediaId == null && !failed
}

data class ComposeUiState(
    val type: PostType = PostType.EXPERIENCE,
    val body: String = "",
    val tip: String = "",
    val category: Interest? = null,
    val place: Place? = null,
    /** The chosen place is near a private place (or private places cannot be read): City / None only. */
    val placeIsPrivate: Boolean = false,
    val privatePlacesUnknown: Boolean = false,
    val precision: Precision? = null,
    val city: City? = null,
    val month: YearMonth? = null,
    val daypart: Daypart? = null,
    /** Never preselected on the first post (D14); afterwards the last choice. */
    val audience: Audience? = null,
    val commentPolicy: CommentPolicy = CommentPolicy.EVERYONE,
    /** Phase 13D (Q6): approve perspectives from people the author is not connected with first. */
    val approveFirst: Boolean = false,
    val photos: List<ComposePhoto> = emptyList(),
    val issues: List<ComposerIssue> = emptyList(),
    /** Set after the person tried to share: the issues are shown from then on. */
    val showIssues: Boolean = false,
    val isBusy: Boolean = false,
    /** One-shot: the new post's id; the screen opens it. */
    val sharedPostId: String? = null,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
) {
    val allowedPrecisions: List<Precision> get() = ComposerPolicy.allowedPrecisions(place, placeIsPrivate)
    val isDelayed: Boolean get() = ComposerPolicy.isDelayed(precision)
    val photosPending: Boolean get() = photos.any { it.uploading }
    val canAddPhotos: Boolean get() = photos.size < ComposerPolicy.MAX_PHOTOS

    /** Phase 13D (Q6): the switch exists only while everyone may add perspectives. */
    val canApproveFirst: Boolean
        get() = ComposerPolicy.effectiveCommentPolicy(audience, commentPolicy) == CommentPolicy.EVERYONE
}

/**
 * Phase 13B: the composer (design §9.5). Experience or Ask; photos from the system Photo Picker are prepared on the
 * phone (smaller, no metadata) and uploaded at once; where (a place and how much of it, the city, or nothing); when (a
 * month and a time of day); a category; who can see it, never preselected the first time; who may add perspectives.
 * A place near a private place can only be shown as the city or not at all.
 */
class ComposeViewModel(
    private val repository: ExperienceRepository,
    private val placeMemory: PlaceMemory,
    private val photoSource: PhotoSource,
    private val clock: Clock
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComposeUiState())
    val uiState: StateFlow<ComposeUiState> = _uiState.asStateFlow()

    private var privateCells: List<String> = emptyList()
    private var nextPhotoKey = 0

    val currentMonth: YearMonth get() = YearMonth.now(clock)

    init {
        _uiState.update { it.copy(month = currentMonth) }
        viewModelScope.launch {
            val snapshot = placeMemory.snapshot()
            privateCells = snapshot.data.privatePlaces.map { it.cell }
            _uiState.update {
                it.copy(audience = it.audience ?: snapshot.data.lastAudience,
                    privatePlacesUnknown = snapshot.privatePlacesUnknown)
            }
            recheck()
        }
    }

    fun onType(type: PostType) = change { it.copy(type = type) }
    fun onBody(text: String) = change { it.copy(body = text) }
    fun onTip(text: String) = change { it.copy(tip = text) }
    fun onCategory(category: Interest?) = change { it.copy(category = category) }
    fun onMonth(month: YearMonth) = change { it.copy(month = month) }
    fun onDaypart(daypart: Daypart?) = change { it.copy(daypart = daypart) }
    fun onAudience(audience: Audience) = change { it.copy(audience = audience) }
    fun onCommentPolicy(policy: CommentPolicy) = change { it.copy(commentPolicy = policy) }
    fun onCity(city: City) = change { it.copy(city = city) }
    fun onApproveFirst(on: Boolean) = change { it.copy(approveFirst = on) }

    fun onPrecision(precision: Precision) = change {
        if (precision in it.allowedPrecisions) it.copy(precision = precision) else it
    }

    /** A place from the picker. Public posts default to the area (design §4.2); near a private place, to the city. */
    fun onPlaceChosen(place: Place) = change {
        val private = it.privatePlacesUnknown || PrivatePlacePolicy.isPrivate(place.cell, privateCells)
        it.copy(place = place, placeIsPrivate = private, precision = if (private) Precision.CITY else Precision.AREA)
    }

    fun onCityOnly() = change { it.copy(place = null, placeIsPrivate = false, precision = Precision.CITY) }

    fun onNoPlace() = change { it.copy(place = null, placeIsPrivate = false, precision = Precision.NONE, city = null) }

    fun onPhotosPicked(uris: List<Uri>) {
        val room = ComposerPolicy.MAX_PHOTOS - _uiState.value.photos.size
        uris.take(room.coerceAtLeast(0)).forEach { uri ->
            val key = "photo-${nextPhotoKey++}"
            change { it.copy(photos = it.photos + ComposePhoto(key)) }
            viewModelScope.launch { upload(key, uri) }
        }
    }

    fun onRemovePhoto(key: String) = change { it.copy(photos = it.photos.filterNot { p -> p.key == key }) }

    fun onShare() {
        val state = _uiState.value
        if (state.isBusy) return
        val issues = issuesOf(state)
        if (issues.isNotEmpty() || state.photosPending) {
            _uiState.update { it.copy(issues = issues, showIssues = true) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, failure = null) }
            when (val result = repository.createPost(draftOf(state))) {
                is ApiResult.Success -> {
                    state.audience?.let { placeMemory.rememberAudience(it) }
                    state.place?.let { placeMemory.rememberUsed(it) }
                    _uiState.update { it.copy(isBusy = false, sharedPostId = result.data.publicId) }
                }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(isBusy = false, failure = result, sessionExpired = result.isSessionExpired())
                }
            }
        }
    }

    fun onSharedHandled() = _uiState.update { it.copy(sharedPostId = null) }
    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private suspend fun upload(key: String, uri: Uri) {
        val jpeg = photoSource.prepare(uri)
        val result = if (jpeg == null) null else repository.uploadPhoto(jpeg)
        _uiState.update { state ->
            state.copy(photos = state.photos.map { p ->
                if (p.key != key) p
                else if (result is ApiResult.Success) p.copy(mediaId = result.data.mediaId)
                else p.copy(failed = true)
            }, sessionExpired = state.sessionExpired || (result as? ApiResult.Failure)?.isSessionExpired() == true)
        }
        recheck()
    }

    private fun draftOf(state: ComposeUiState) = PostDraft(
        type = state.type,
        body = state.body,
        tip = state.tip,
        category = state.category,
        place = state.place,
        precision = state.precision,
        city = state.city,
        month = if (state.type == PostType.EXPERIENCE) state.month else null,
        daypart = state.daypart,
        audience = state.audience,
        commentPolicy = ComposerPolicy.effectiveCommentPolicy(state.audience, state.commentPolicy),
        mediaIds = state.photos.mapNotNull { it.mediaId },
        approveFirst = state.approveFirst && state.canApproveFirst
    )

    private fun issuesOf(state: ComposeUiState) =
        ComposerPolicy.issues(draftOf(state), currentMonth, state.placeIsPrivate)

    private fun change(block: (ComposeUiState) -> ComposeUiState) {
        _uiState.update(block)
        recheck()
    }

    private fun recheck() = _uiState.update { it.copy(issues = issuesOf(it)) }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                ComposeViewModel(container.experienceRepository, container.placeMemory, container.photoPreparer,
                    Clock.systemDefaultZone())
            }
        }
    }
}
