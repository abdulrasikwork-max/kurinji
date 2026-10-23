package com.safesphere.app.feature.social.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.social.domain.ExperienceRepository
import com.safesphere.app.feature.social.domain.Post
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PostListUiState(
    val isLoading: Boolean = false,
    val items: List<Post> = emptyList(),
    /** More exist; they load only when the person taps "Show more" (no auto-loading scroll). */
    val hasMore: Boolean = false,
    val loaded: Boolean = false,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
)

/**
 * Phase 13B: a list of posts, newest first, 20 at a time: the person's own ("Your experiences", [profileId] null) or
 * the ones of someone's profile the viewer may see. More only on an explicit tap.
 */
class PostListViewModel(private val repository: ExperienceRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(PostListUiState())
    val uiState: StateFlow<PostListUiState> = _uiState.asStateFlow()

    private var profileId: String? = null
    private var own = false
    private var nextCursor: String? = null

    fun showOwn() {
        own = true
        profileId = null
        reload()
    }

    /** Loads [id]'s posts once (again only when the profile changes or on [reload]). */
    fun showProfile(id: String) {
        if (!own && profileId == id && _uiState.value.loaded) return
        own = false
        profileId = id
        reload()
    }

    fun reload() {
        nextCursor = null
        _uiState.update { it.copy(items = emptyList(), hasMore = false, loaded = false) }
        load()
    }

    fun onShowMore() {
        if (_uiState.value.hasMore) load()
    }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun load() {
        if (_uiState.value.isLoading) return
        val id = profileId
        if (!own && id == null) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, failure = null) }
            val result = if (own) repository.myPosts(nextCursor) else repository.profilePosts(id!!, nextCursor)
            when (result) {
                is ApiResult.Success -> {
                    nextCursor = result.data.nextCursor
                    _uiState.update {
                        it.copy(isLoading = false, loaded = true, items = it.items + result.data.items,
                            hasMore = result.data.nextCursor != null)
                    }
                }
                is ApiResult.Failure -> _uiState.update {
                    // A profile the viewer may not see has no list (404): show it as empty, never as an error.
                    if (result.error == ApiError.NOT_FOUND) it.copy(isLoading = false, loaded = true, items = emptyList())
                    else it.copy(isLoading = false, failure = result, sessionExpired = result.isSessionExpired())
                }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { PostListViewModel(container.experienceRepository) }
        }
    }
}
