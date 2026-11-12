package com.safesphere.app.feature.social.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.social.data.MediaPick
import com.safesphere.app.feature.social.data.MediaRefusal
import com.safesphere.app.feature.social.data.MediaSource
import com.safesphere.app.feature.social.data.PlaceMemory
import com.safesphere.app.feature.social.data.PreparedVideo
import com.safesphere.app.feature.social.data.VideoOutcome
import com.safesphere.app.feature.social.domain.Audience
import com.safesphere.app.feature.social.domain.City
import com.safesphere.app.feature.social.domain.CommentPolicy
import com.safesphere.app.feature.social.domain.ComposerIssue
import com.safesphere.app.feature.social.domain.ComposerPolicy
import com.safesphere.app.feature.social.domain.Daypart
import com.safesphere.app.feature.social.domain.ExperienceRepository
import com.safesphere.app.feature.social.domain.ExploreFacets
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.MediaKind
import com.safesphere.app.feature.social.domain.Place
import com.safesphere.app.feature.social.domain.PostDraft
import com.safesphere.app.feature.social.domain.PostType
import com.safesphere.app.feature.social.domain.Precision
import com.safesphere.app.feature.social.domain.PrivatePlacePolicy
import java.time.Clock
import java.time.YearMonth
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Phase 17C: where one picked item is on its way to the server. */
enum class MediaStage {
    /** Being made ready on this phone (a photo re-encoded, a video re-packed). */
    PREPARING,
    /** Being sent ([ComposeMedia.progress]). */
    UPLOADING,
    /** A video's poster frame is being sent. */
    FINISHING,
    READY,
    /** The connection or the server failed: Retry or Remove. */
    FAILED,
    /** It cannot be shared as it is ([ComposeMedia.refusal]): Remove only. */
    REFUSED
}

/**
 * One picked photo or (Phase 17C) video. [mediaId] is the server's id once the main upload is done (a video then still
 * needs its poster before it is READY). [preview] is shown until then: the picked photo, a video's poster frame.
 */
data class ComposeMedia(
    val key: String,
    val kind: MediaKind = MediaKind.PHOTO,
    val stage: MediaStage = MediaStage.PREPARING,
    val mediaId: String? = null,
    /** 0..1 while UPLOADING. */
    val progress: Float = 0f,
    val refusal: MediaRefusal? = null,
    /** FAILED because the connection was too slow (a timeout), not because it was lost or refused: "try again". */
    val timedOut: Boolean = false,
    val durationMs: Long? = null,
    val preview: Any? = null
) {
    val isVideo: Boolean get() = kind == MediaKind.VIDEO
    val ready: Boolean get() = stage == MediaStage.READY
    val pending: Boolean get() = stage == MediaStage.PREPARING || stage == MediaStage.UPLOADING ||
        stage == MediaStage.FINISHING
    /** Stops sharing until it is tried again or removed (17A: never left out silently). */
    val problem: Boolean get() = stage == MediaStage.FAILED || stage == MediaStage.REFUSED
}

/** Phase 17C: one-shot notes about what was picked. */
enum class ComposeNotice { ONE_VIDEO_ONLY, TOO_MANY_ITEMS }

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
    /** Phase 17G-1: Explore tags ("Good for..."), an Experience only, at most four, codes in the list's order. */
    val facets: List<String> = emptyList(),
    /** Never preselected on the first post (D14); afterwards the last choice. */
    val audience: Audience? = null,
    val commentPolicy: CommentPolicy = CommentPolicy.EVERYONE,
    /** Phase 13D (Q6): approve perspectives from people the author is not connected with first. */
    val approveFirst: Boolean = false,
    val media: List<ComposeMedia> = emptyList(),
    /** One-shot (Phase 17C): some picked items were not added. */
    val notice: ComposeNotice? = null,
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
    val mediaPending: Boolean get() = media.any { it.pending }

    /** Phase 17A: a photo that could not be added stops sharing until it is tried again or removed (it used to be left out silently). Phase 17C: videos too. */
    val mediaFailed: Boolean get() = media.any { it.problem }
    /** IRL redesign (R1): there is something to lose, so leaving asks first. */
    val hasDraft: Boolean get() = body.isNotBlank() || tip.isNotBlank() || media.isNotEmpty()

    val canAddMedia: Boolean get() = media.size < ComposerPolicy.MAX_PHOTOS
    val canAddVideo: Boolean get() = canAddMedia && media.count { it.isVideo } < ComposerPolicy.MAX_VIDEOS


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
    private val mediaSource: MediaSource,
    private val clock: Clock,
    private val freshness: com.safesphere.app.feature.social.domain.FeedFreshness =
        com.safesphere.app.feature.social.domain.FeedFreshness(),
    /** IRL redesign (R2): the city of the person's profile, when it names a catalog city (null = unknown). */
    private val homeCity: suspend () -> City? = { null }
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComposeUiState())
    val uiState: StateFlow<ComposeUiState> = _uiState.asStateFlow()

    private var privateCells: List<String> = emptyList()
    private var nextMediaKey = 0

    /** Phase 17A: the picked item behind each key, for "try again" (memory only, never saved). */
    private val picks = HashMap<String, MediaPick>()

    /** Phase 17C: re-packed videos (work files in the app's cache), kept for a retry; deleted on Remove/Share/close. */
    private val preparedVideos = HashMap<String, PreparedVideo>()

    /** Phase 17C: the running preparation / upload of each item (Remove cancels it). */
    private val jobs = HashMap<String, Job>()

    val currentMonth: YearMonth get() = YearMonth.now(clock)

    /** The person chose something about the place themselves: the starting suggestion no longer applies. */
    private var placeTouched = false

    init {
        // R2: a post needs no place decision to be shared. It starts as "no place" and, once the profile's city is
        // known, as "only the city" (so it still shows in that city); choosing a place is one optional tap.
        _uiState.update { it.copy(month = currentMonth, precision = Precision.NONE) }
        viewModelScope.launch {
            val snapshot = placeMemory.snapshot()
            privateCells = snapshot.data.privatePlaces.map { it.cell }
            _uiState.update {
                it.copy(audience = it.audience ?: snapshot.data.lastAudience,
                    privatePlacesUnknown = snapshot.privatePlacesUnknown)
            }
            recheck()
            val city = homeCity()
            if (city != null && !city.isOther && !placeTouched) change {
                if (it.place == null && it.precision == Precision.NONE) it.copy(precision = Precision.CITY, city = city) else it
            }
        }
    }

    fun onType(type: PostType) = change { it.copy(type = type) }
    fun onBody(text: String) = change { it.copy(body = text) }
    fun onTip(text: String) = change { it.copy(tip = text) }
    fun onCategory(category: Interest?) = change { it.copy(category = category) }
    fun onMonth(month: YearMonth) = change { it.copy(month = month) }
    fun onDaypart(daypart: Daypart?) = change { it.copy(daypart = daypart) }

    /** Phase 17G-1: add or take off one tag; a fifth is not added (the chips are disabled at four). */
    fun onToggleFacet(code: String) = change { s ->
        when {
            !ExploreFacets.isValid(code) -> s
            code in s.facets -> s.copy(facets = s.facets - code)
            s.facets.size >= ExploreFacets.MAX_PER_POST -> s
            else -> s.copy(facets = ExploreFacets.cleaned(s.facets + code))
        }
    }
    fun onAudience(audience: Audience) = change { it.copy(audience = audience) }
    fun onCommentPolicy(policy: CommentPolicy) = change { it.copy(commentPolicy = policy) }
    fun onCity(city: City) {
        placeTouched = true
        change { it.copy(city = city) }
    }
    fun onApproveFirst(on: Boolean) = change { it.copy(approveFirst = on) }

    fun onPrecision(precision: Precision) = change {
        if (precision in it.allowedPrecisions) it.copy(precision = precision) else it
    }

    /** A place from the picker. Public posts default to the area (design §4.2); near a private place, to the city. */
    fun onPlaceChosen(place: Place) = change {
        placeTouched = true
        val private = it.privatePlacesUnknown || PrivatePlacePolicy.isPrivate(place.cell, privateCells)
        it.copy(place = place, placeIsPrivate = private, precision = if (private) Precision.CITY else Precision.AREA)
    }

    private var prefilled = false

    /**
     * Phase 17E ("I went", plan §4): the composer opened from a post the person went to. The place is filled in when
     * the post named an exact place the person could see (read again from the server: it may have been merged); the
     * category too. Nothing is posted or stored by itself; a place that cannot be read is simply left out.
     */
    fun prefill(placeId: String?, category: Interest?) {
        if (prefilled) return
        prefilled = true
        if (placeId == null && category == null) return
        change { it.copy(type = PostType.EXPERIENCE, category = category ?: it.category) }
        if (placeId == null) return
        viewModelScope.launch {
            when (val result = repository.place(placeId)) {
                is ApiResult.Success -> if (_uiState.value.place == null) onPlaceChosen(result.data)
                is ApiResult.Failure -> Unit
            }
        }
    }

    fun onCityOnly() {
        placeTouched = true
        change { it.copy(place = null, placeIsPrivate = false, precision = Precision.CITY) }
    }

    fun onNoPlace() {
        placeTouched = true
        change { it.copy(place = null, placeIsPrivate = false, precision = Precision.NONE, city = null) }
    }

    /**
     * Phase 17C: photos and videos from the picker, in the order picked. At most 6 items and one video per post: the
     * rest is not added, and [ComposeUiState.notice] says why.
     */
    fun onMediaPicked(items: List<MediaPick>) {
        var notice: ComposeNotice? = null
        for (pick in items) {
            val state = _uiState.value
            if (!state.canAddMedia) {
                notice = notice ?: ComposeNotice.TOO_MANY_ITEMS
                continue
            }
            if (pick.kind == MediaKind.VIDEO && !state.canAddVideo) {
                notice = ComposeNotice.ONE_VIDEO_ONLY
                continue
            }
            val key = "media-${nextMediaKey++}"
            picks[key] = pick
            change { it.copy(media = it.media + ComposeMedia(key, pick.kind, preview = pick.preview)) }
            start(key)
        }
        if (notice != null) _uiState.update { it.copy(notice = notice) }
    }

    fun onNoticeHandled() = _uiState.update { it.copy(notice = null) }

    /** Phase 17A: one more attempt for an item that could not be added (from the step that failed). */
    fun onRetryMedia(key: String) {
        if (picks[key] == null) return
        if (_uiState.value.media.none { it.key == key && it.stage == MediaStage.FAILED }) return
        start(key)
    }

    /** Stops its upload, forgets it, deletes its work files, and (best effort) the server's copy if there is one. */
    fun onRemoveMedia(key: String) {
        jobs.remove(key)?.cancel()
        picks.remove(key)
        preparedVideos.remove(key)?.let(mediaSource::discard)
        val mediaId = _uiState.value.media.firstOrNull { it.key == key }?.mediaId
        change { it.copy(media = it.media.filterNot { m -> m.key == key }) }
        if (mediaId != null) viewModelScope.launch { repository.deleteUpload(mediaId) } // else the 24 h clean-up
    }

    /**
     * IRL redesign (R1): the person chose to throw the draft away. Running uploads stop, work files are deleted and the
     * server's copies are removed (best effort), so nothing is left behind.
     */
    fun onDiscard() {
        _uiState.value.media.map { it.key }.forEach(::onRemoveMedia)
    }

    override fun onCleared() {
        preparedVideos.values.forEach(mediaSource::discard)
        preparedVideos.clear()
    }

    fun onShare() {
        val state = _uiState.value
        if (state.isBusy) return
        val issues = issuesOf(state)
        if (issues.isNotEmpty() || state.mediaPending || state.mediaFailed) {
            _uiState.update { it.copy(issues = issues, showIssues = true) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, failure = null) }
            when (val result = repository.createPost(draftOf(state))) {
                is ApiResult.Success -> {
                    state.audience?.let { placeMemory.rememberAudience(it) }
                    state.place?.let { placeMemory.rememberUsed(it) }
                    preparedVideos.values.forEach(mediaSource::discard)
                    preparedVideos.clear()
                    freshness.changed()
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

    private fun start(key: String) {
        jobs.remove(key)?.cancel()
        jobs[key] = viewModelScope.launch {
            val pick = picks[key] ?: return@launch
            if (pick.kind == MediaKind.VIDEO) uploadVideo(key, pick) else uploadPhoto(key, pick)
        }
    }

    private suspend fun uploadPhoto(key: String, pick: MediaPick) {
        set(key) { it.copy(stage = MediaStage.PREPARING, progress = 0f) }
        val jpeg = mediaSource.preparePhoto(pick)
        if (jpeg == null) {
            set(key) { it.copy(stage = MediaStage.REFUSED, refusal = MediaRefusal.UNREADABLE) }
            return
        }
        set(key) { it.copy(stage = MediaStage.UPLOADING) }
        when (val result = repository.uploadPhoto(jpeg) { share -> progress(key, share) }) {
            is ApiResult.Success -> set(key) {
                it.copy(stage = MediaStage.READY, mediaId = result.data.mediaId, progress = 1f)
            }
            is ApiResult.Failure -> failed(key, result)
        }
    }

    /** Re-pack (once), upload the video (unless the server already has it), then its poster. */
    private suspend fun uploadVideo(key: String, pick: MediaPick) {
        var video = preparedVideos[key]
        if (video == null) {
            set(key) { it.copy(stage = MediaStage.PREPARING, progress = 0f) }
            when (val outcome = mediaSource.prepareVideo(pick)) {
                is VideoOutcome.Refused -> {
                    set(key) { it.copy(stage = MediaStage.REFUSED, refusal = outcome.reason) }
                    return
                }
                is VideoOutcome.Ready -> {
                    if (picks[key] == null) { // removed while it was being prepared
                        mediaSource.discard(outcome.video)
                        return
                    }
                    video = outcome.video
                    preparedVideos[key] = outcome.video
                    set(key) { it.copy(durationMs = outcome.video.durationMs, preview = outcome.video.posterFile) }
                }
            }
        }
        var mediaId = _uiState.value.media.firstOrNull { it.key == key }?.mediaId
        if (mediaId == null) {
            set(key) { it.copy(stage = MediaStage.UPLOADING, progress = 0f) }
            when (val result = repository.uploadVideo(video.file) { share -> progress(key, share) }) {
                is ApiResult.Success -> mediaId = result.data.mediaId
                is ApiResult.Failure -> {
                    failed(key, result)
                    return
                }
            }
            set(key) { it.copy(mediaId = mediaId) }
        }
        set(key) { it.copy(stage = MediaStage.FINISHING, progress = 1f) }
        when (val result = repository.uploadPoster(mediaId, video.poster)) {
            is ApiResult.Success -> set(key) { it.copy(stage = MediaStage.READY) }
            is ApiResult.Failure -> failed(key, result)
        }
    }

    /** The server refusing the item itself (400) or its daily limit (429) is final; anything else can be retried. */
    private fun failed(key: String, failure: ApiResult.Failure) {
        val refusal = when (failure.error) {
            ApiError.VALIDATION -> MediaRefusal.NOT_ACCEPTED
            ApiError.TOO_MANY_ATTEMPTS -> MediaRefusal.DAILY_LIMIT
            else -> null
        }
        set(key) {
            it.copy(stage = if (refusal != null) MediaStage.REFUSED else MediaStage.FAILED, refusal = refusal,
                timedOut = refusal == null && failure.timedOut)
        }
        if (failure.isSessionExpired()) _uiState.update { it.copy(sessionExpired = true) }
    }

    /** Upload progress arrives on the network thread; only whole percents arrive (ProgressRequestBody). */
    private fun progress(key: String, share: Float) = _uiState.update { s ->
        s.copy(media = s.media.map { if (it.key == key && it.stage == MediaStage.UPLOADING) it.copy(progress = share) else it })
    }

    private fun set(key: String, block: (ComposeMedia) -> ComposeMedia) {
        _uiState.update { s -> s.copy(media = s.media.map { if (it.key == key) block(it) else it }) }
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
        mediaIds = state.media.filter { it.ready }.mapNotNull { it.mediaId },
        approveFirst = state.approveFirst && state.canApproveFirst,
        facets = if (state.type == PostType.EXPERIENCE) state.facets else emptyList()
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
                ComposeViewModel(container.experienceRepository, container.placeMemory, container.mediaSource,
                    Clock.systemDefaultZone(), container.feedFreshness) {
                    (container.socialRepository.ownProfile() as? ApiResult.Success)?.data?.city
                        ?.let(com.safesphere.app.feature.social.domain.Cities::match)
                }

            }
        }
    }
}
