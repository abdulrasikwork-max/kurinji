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
import com.safesphere.app.feature.social.domain.ExperienceRepository
import com.safesphere.app.feature.social.domain.Perspective
import com.safesphere.app.feature.social.domain.Post
import com.safesphere.app.feature.social.domain.PostType
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
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
) {
    /** The top-level kinds that fit the post: Been here too / Question on an Experience, Answer / Question on an Ask. */
    val kinds: List<CommentKind>
        get() = if (post?.type == PostType.ASK) listOf(CommentKind.ANSWER, CommentKind.QUESTION)
        else listOf(CommentKind.BEEN_HERE, CommentKind.QUESTION)

    val canSend: Boolean
        get() = !isBusy && post?.canComment == true && text.isNotBlank() && (replyTo != null || kind != null)
}

/**
 * Phase 13B: one post and its Perspectives. Add a perspective of a kind that fits the post, or a reply under one (one
 * level); the post's author hides or shows again any perspective (its writer still sees "Hidden by the author") and, on
 * an Ask, marks answers "This helped"; writers delete their own; the author deletes the post.
 */
class PostViewModel(
    private val repository: ExperienceRepository,
    private val postId: String
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
                    _uiState.update { it.copy(post = result.data, notFound = false, failure = null) }
                    loadPerspectives()
                    _uiState.update { it.copy(isLoading = false) }
                }
                is ApiResult.Failure -> if (result.error == ApiError.NOT_FOUND) {
                    _uiState.update { it.copy(isLoading = false, post = null, notFound = true) }
                } else fail(result)
            }
        }
    }

    fun onKind(kind: CommentKind) = _uiState.update { it.copy(kind = kind, replyTo = null) }
    fun onText(text: String) = _uiState.update { it.copy(text = text) }
    fun onReplyTo(perspective: Perspective) = _uiState.update { it.copy(replyTo = perspective) }
    fun onCancelReply() = _uiState.update { it.copy(replyTo = null) }

    fun onSend() {
        val state = _uiState.value
        if (!state.canSend) return
        val replyTo = state.replyTo
        val kind = if (replyTo != null) CommentKind.REPLY else state.kind ?: return
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

    fun onDeletePostConfirmed() {
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            when (val result = repository.deletePost(postId)) {
                is ApiResult.Success -> _uiState.update { it.copy(isBusy = false, closed = true) }
                is ApiResult.Failure -> fail(result)
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
            if (failure != null) fail(failure) else _uiState.update { it.copy(isBusy = false) }
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

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                PostViewModel(container.experienceRepository, createSavedStateHandle().get<String>(ARG_POST_ID).orEmpty())
            }
        }
    }
}
