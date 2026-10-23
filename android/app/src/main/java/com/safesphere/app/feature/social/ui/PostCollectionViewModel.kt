package com.safesphere.app.feature.social.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.social.domain.City
import com.safesphere.app.feature.social.domain.DiscoverRepository
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.Place
import com.safesphere.app.feature.social.domain.Post
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a collection shows: a place page or a category page (optionally in one city). */
sealed interface PostCollection {
    data class OfPlace(val placeId: String) : PostCollection
    data class OfCategory(val category: Interest, val city: City?) : PostCollection
}

data class PostCollectionUiState(
    val collection: PostCollection? = null,
    /** A place page: the place (name, category, city); null until loaded and for category pages. */
    val place: Place? = null,
    val isLoading: Boolean = false,
    val items: List<Post> = emptyList(),
    val hasMore: Boolean = false,
    val loaded: Boolean = false,
    val notFound: Boolean = false,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
)

/**
 * Phase 13C (C10, C11): a place page (its exact-place Everyone experiences and Asks) or a category page. Newest first,
 * 20 at a time, more only on "Show more". No ratings, no rankings, no numbers.
 */
class PostCollectionViewModel(private val repository: DiscoverRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(PostCollectionUiState())
    val uiState: StateFlow<PostCollectionUiState> = _uiState.asStateFlow()

    private var nextCursor: String? = null

    fun show(collection: PostCollection) {
        if (_uiState.value.collection == collection && _uiState.value.loaded) return
        _uiState.value = PostCollectionUiState(collection = collection)
        reload()
    }

    fun reload() {
        nextCursor = null
        _uiState.update { it.copy(items = emptyList(), hasMore = false, loaded = false, notFound = false) }
        load()
    }

    fun onShowMore() {
        if (_uiState.value.hasMore) load()
    }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun load() {
        val collection = _uiState.value.collection ?: return
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, failure = null) }
            val result = when (collection) {
                is PostCollection.OfPlace -> when (val r = repository.placePage(collection.placeId, nextCursor)) {
                    is ApiResult.Success -> {
                        _uiState.update { it.copy(place = r.data.place) }
                        ApiResult.Success(r.data.items to r.data.nextCursor)
                    }
                    is ApiResult.Failure -> r
                }
                is PostCollection.OfCategory -> when (val r = repository.categoryPage(collection.category, collection.city, nextCursor)) {
                    is ApiResult.Success -> ApiResult.Success(r.data.items to r.data.nextCursor)
                    is ApiResult.Failure -> r
                }
            }
            when (result) {
                is ApiResult.Success -> {
                    nextCursor = result.data.second
                    _uiState.update {
                        it.copy(isLoading = false, loaded = true, items = it.items + result.data.first,
                            hasMore = result.data.second != null)
                    }
                }
                is ApiResult.Failure -> _uiState.update {
                    if (result.error == ApiError.NOT_FOUND) it.copy(isLoading = false, loaded = true, notFound = true)
                    else it.copy(isLoading = false, failure = result, sessionExpired = result.isSessionExpired())
                }
            }
        }
    }

    companion object {
        const val ARG_PLACE_ID = "placeId"
        const val ARG_CATEGORY = "category"
        const val ARG_CITY = "city"

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { PostCollectionViewModel(container.discoverRepository) }
        }
    }
}
