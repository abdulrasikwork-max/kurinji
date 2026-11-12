package com.safesphere.app.feature.social.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.social.domain.CommentKind
import com.safesphere.app.feature.social.domain.DiscoverRepository
import com.safesphere.app.feature.social.domain.ExperienceRepository
import com.safesphere.app.feature.social.domain.Perspective
import com.safesphere.app.feature.social.domain.Post
import com.safesphere.app.feature.social.domain.PostType
import com.safesphere.app.feature.social.domain.Precision
import com.safesphere.app.feature.social.domain.Visit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PostUiState(
    val isLoading: Boolean = true,
    val post: Post? = null,
    /** Missing, deleted, or not visible to the viewer: the same answer, on purpose. */
    val notFound: Boolean = false,
    val perspectives: List<Perspective> = emptyList(),
    /** The kind of the next top-level perspective (null = none chosen yet). */
    val kind: CommentKind? = null,
    val replyTo: Perspective? = null,
    val text: String = "",
    val isBusy: Boolean = false,
    /** One-shot: the post was deleted; the screen closes. */
    val closed: Boolean = false,
    /** Phase 17E: marks in flight ("react", "save"), so a double tap sends nothing twice. */
    val marking: Set<String> = emptySet(),
    /** Phase 17E, one-shot: a save was confirmed (the butterfly flies to Saved, TalkBack hears "Saved"). */
    val savedNow: Boolean = false,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false,
    /** Phase 13D: the viewer tapped "Show it" on a collapsed post (kept only on this screen). */
    val revealed: Boolean = false,
    /** Phase 13D: the collapsed perspectives the viewer chose to show. */
    val revealedPerspectives: Set<String> = emptySet(),
    /** Phase 13E (E20): the viewer chose "Collapse strong language" (their own setting, on this phone). */
    val strongLanguageFilter: Boolean = false,
    /** Phase 17G-2 (U5): the person's OWN visit (and rating) at this post's place, once they marked "I went". */
    val visit: Visit? = null,
    val visitBusy: Boolean = false,
    /** Phase 17G-2, one-shot: "I went" was just confirmed; the screen asks "How was it?". */
    val visitSheet: Boolean = false
) {
    /**
     * Phase 13D: someone else's post that stays behind a tap until the viewer chooses to see it. 13E: also when it has
     * strong language and the viewer asked for that.
     */
    val showCollapsed: Boolean
        get() = post?.let { !it.own && (it.collapsed != null || (strongLanguageFilter && it.strongLanguage)) } == true &&
            !revealed

    /** Phase 13E: whether one perspective stays behind a tap (moderation, or strong language with the setting on). */
    fun perspectiveCollapsed(p: Perspective): Boolean =
        !p.own && (p.collapsed != null || (strongLanguageFilter && p.strongLanguage)) && p.publicId !in revealedPerspectives

    /** The top-level kinds that fit the post: Been here too / Question on an Experience, Answer / Question on an Ask. */
    val kinds: List<CommentKind>
        get() = if (post?.type == PostType.ASK) listOf(CommentKind.ANSWER, CommentKind.QUESTION)
        else listOf(CommentKind.BEEN_HERE, CommentKind.QUESTION)

    val canSend: Boolean
        get() = !isBusy && post?.canComment == true && text.isNotBlank() && (replyTo != null || kind != null)

    /** Phase 17E (D-3): the butterfly goes on someone else's Experience only. */
    val canReact: Boolean
        get() = post?.let { it.type == PostType.EXPERIENCE && !it.own } == true

    /** Phase 17E (D-3): anything of someone else's can be saved. */
    val canSave: Boolean
        get() = post?.let { !it.own } == true
}

/**
 * Phase 13B: one post and its Perspectives. Add a perspective of a kind that fits the post, or a reply under one (one
 * level); the post's author hides or shows again any perspective (its writer still sees "Hidden by the author") and, on
 * an Ask, marks answers "This helped"; writers delete their own; the author deletes the post.
 */
class PostViewModel(
    private val repository: ExperienceRepository,
    private val discover: DiscoverRepository,
    private val postId: String,
    private val freshness: com.safesphere.app.feature.social.domain.FeedFreshness =
        com.safesphere.app.feature.social.domain.FeedFreshness()
) : ViewModel() {

    private val _uiState = MutableStateFlow(PostUiState())
    val uiState: StateFlow<PostUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val result = repository.post(postId)) {
                is ApiResult.Success -> {
                    // Phase 16: a kind is chosen from the start (the first that fits: "Been here too" on an
                    // Experience, "Answer" on a question), so typing and sending just works; it can be changed.
                    _uiState.update {
                        val loaded = it.copy(post = result.data, notFound = false, failure = null)
                        loaded.copy(kind = it.kind ?: loaded.kinds.first())
                    }
                    loadPerspectives()
                    _uiState.update { it.copy(isLoading = false) }
                }
                is ApiResult.Failure -> if (result.error == ApiError.NOT_FOUND) {
                    _uiState.update { it.copy(isLoading = false, post = null, notFound = true) }
                } else fail(result)
            }
        }
    }

    fun onKind(kind: CommentKind) {
        kindChosen = true
        _uiState.update { it.copy(kind = kind, replyTo = null) }
    }

    /** A kind picked through [onKind] wins; the screen no longer offers that choice (R1). */
    private var kindChosen = false

    /**
     * IRL redesign (R1): a comment is just a comment. The server still files each one under a kind, so the app picks
     * it from the words: a question mark makes it a Question, otherwise the first kind that fits the post (which the
     * screen no longer prints, so "nice!" is not announced as "Been here too").
     */
    private fun kindFor(state: PostUiState): CommentKind = when {
        kindChosen && state.kind != null -> state.kind
        ('?' in state.text || '？' in state.text) &&CommentKind.QUESTION in state.kinds -> CommentKind.QUESTION
        else -> state.kinds.first()
    }

    fun onText(text: String) = _uiState.update { it.copy(text = text) }
    fun onReplyTo(perspective: Perspective) = _uiState.update { it.copy(replyTo = perspective) }
    fun onCancelReply() = _uiState.update { it.copy(replyTo = null) }

    fun onSend() {
        val state = _uiState.value
        if (!state.canSend) return
        val replyTo = state.replyTo
        val kind = if (replyTo != null) CommentKind.REPLY else kindFor(state)
        act {
            when (val result = repository.addPerspective(postId, kind, state.text, replyTo?.publicId)) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(text = "", replyTo = null) }
                    null
                }
                is ApiResult.Failure -> result
            }
        }
    }

    fun onHide(p: Perspective, hidden: Boolean) = act { (repository.setHidden(p.publicId, hidden) as? ApiResult.Failure) }
    fun onHelped(p: Perspective, helped: Boolean) = act { (repository.setHelped(p.publicId, helped) as? ApiResult.Failure) }
    fun onDeletePerspective(p: Perspective) = act { (repository.deletePerspective(p.publicId) as? ApiResult.Failure) }

    /** Phase 13D (Q6): the post's author lets a waiting perspective appear. */
    fun onApprove(p: Perspective) {
        if (!p.canApprove) return
        act { (repository.approvePerspective(p.publicId) as? ApiResult.Failure) }
    }

    /** Phase 13E: the viewer's "Collapse strong language" setting (read from this phone by the screen). */
    fun onStrongLanguageFilter(on: Boolean) = _uiState.update { it.copy(strongLanguageFilter = on) }

    /** Phase 13D: "Show it" on a collapsed post or perspective; only this screen remembers it. */
    fun onReveal() = _uiState.update { it.copy(revealed = true) }
    fun onRevealPerspective(p: Perspective) = _uiState.update { it.copy(revealedPerspectives = it.revealedPerspectives + p.publicId) }

    fun onDeletePostConfirmed() {
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            when (val result = repository.deletePost(postId)) {
                is ApiResult.Success -> {
                    freshness.changed()
                    _uiState.update { it.copy(isBusy = false, closed = true) }
                }
                is ApiResult.Failure -> fail(result)
            }
        }
    }

    /**
     * Phase 17E (D-3): the butterfly (a reaction) given or taken back. Shown at once, put back if the server says no
     * (idempotent on the server, so a double tap or a lost answer is harmless).
     */
    fun onReact() {
        val post = _uiState.value.post ?: return
        if (!_uiState.value.canReact || "react" in _uiState.value.marking) return
        toggle("react", { it.copy(youReacted = !post.youReacted) }, { it.copy(youReacted = post.youReacted) }) {
            if (post.youReacted) discover.unreact(postId) else discover.react(postId)
        }
    }

    /** Phase 17E (D-3): Save (a private bookmark), or take it out of Saved again. */
    fun onSave() {
        val post = _uiState.value.post ?: return
        if (!_uiState.value.canSave || "save" in _uiState.value.marking) return
        toggle("save", { it.copy(saved = !post.saved) }, { it.copy(saved = post.saved) },
            confirmed = { if (!post.saved) _uiState.update { it.copy(savedNow = true) } }) {
            if (post.saved) discover.unsave(postId) else discover.save(postId)
        }
    }

    fun onSavedShown() = _uiState.update { it.copy(savedNow = false) }

    /** Phase 17G-2 (U5): "I went" and the person's own rating for the place this post names (only an EXACT place). */
    private val visits = PlaceVisits(discover, viewModelScope,
        onVisit = { v -> _uiState.update { it.copy(visit = v) } },
        onWent = { _uiState.update { it.copy(visitSheet = true) } },
        onBusy = { b -> _uiState.update { it.copy(visitBusy = b) } },
        onFailure = { failure -> fail(failure) })

    /** The place a visit can be marked for: the post's own, when it names an exact place; otherwise none. */
    private fun visitPlaceId(): String? = _uiState.value.post?.let { if (it.precision == Precision.EXACT) it.placeId else null }

    /**
     * "I went". Returns true when a visit is being marked (the screen then waits for the "How was it?" sheet); false when the post
     * names no exact place, so there is nothing to mark and the screen goes straight to the composer, as before 17G-2.
     */
    fun onWent(): Boolean {
        val placeId = visitPlaceId() ?: return false
        visits.went(placeId)
        return true
    }

    fun onRate(rating: Int) {
        visitPlaceId()?.let { visits.rate(it, rating) }
    }

    fun onClearRating() {
        visitPlaceId()?.let { visits.clearRating(it) }
    }

    fun onNotWent() {
        visitPlaceId()?.let { visits.notWent(it) }
    }

    fun onVisitSheetHandled() = _uiState.update { it.copy(visitSheet = false) }

    private fun toggle(key: String, apply: (Post) -> Post, undo: (Post) -> Post, confirmed: () -> Unit = {},
                       call: suspend () -> ApiResult<Unit>) {
        _uiState.update { it.copy(post = it.post?.let(apply), marking = it.marking + key, failure = null) }
        viewModelScope.launch {
            when (val result = call()) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(marking = it.marking - key) }
                    freshness.changed() // R7: Home shows the same mark when the person goes back
                    confirmed()
                }
                is ApiResult.Failure -> {
                    _uiState.update { it.copy(post = it.post?.let(undo), marking = it.marking - key) }
                    if (result.error == ApiError.NOT_FOUND) {
                        _uiState.update { it.copy(post = null, notFound = true) }
                    } else fail(result)
                }
            }
        }
    }

    fun onClosedHandled() = _uiState.update { it.copy(closed = false) }
    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    /** Runs an action, then reloads the perspectives so the screen matches the server. */
    private fun act(call: suspend () -> ApiResult.Failure?) {
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, failure = null) }
            val failure = call()
            if (failure != null) fail(failure) else {
                // R7: a comment added, deleted, hidden or approved changes what the feed card says ("2 comments").
                freshness.changed()
                _uiState.update { it.copy(isBusy = false) }
            }
            loadPerspectives()
        }
    }

    private suspend fun loadPerspectives() {
        when (val result = repository.perspectives(postId)) {
            is ApiResult.Success -> _uiState.update { it.copy(perspectives = result.data) }
            is ApiResult.Failure -> if (result.error == ApiError.NOT_FOUND) {
                _uiState.update { it.copy(post = null, notFound = true, perspectives = emptyList()) }
            } else fail(result)
        }
    }

    private fun fail(failure: ApiResult.Failure) = _uiState.update {
        it.copy(isLoading = false, isBusy = false, failure = failure, sessionExpired = failure.isSessionExpired())
    }

    companion object {
        const val ARG_POST_ID = "postId"

        /** Phase 16: opened from a card's comment button: scroll to the comments. */
        const val ARG_TO_COMMENTS = "toComments"

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                PostViewModel(container.experienceRepository, container.discoverRepository,
                    createSavedStateHandle().get<String>(ARG_POST_ID).orEmpty(), container.feedFreshness)
            }
        }
    }
}
