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
import com.safesphere.app.feature.social.domain.Visit
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
    val sessionExpired: Boolean = false,
    /** Phase 17G-2 (U5): the place's one public rating as text ("4.3"), or null. Orders nothing. */
    val rating: String? = null,
    /** Phase 17G-2: the person's OWN visit (and rating) at this place, or null. */
    val visit: Visit? = null,
    /** Phase 17G-2: a visit call is running. */
    val visitBusy: Boolean = false,
    /** Phase 17G-2, one-shot: "I went" was just confirmed; the screen asks "How was it?". */
    val visitSheet: Boolean = false
)

/**
 * Phase 13C (C10, C11): a place page (its exact-place Everyone experiences and Asks) or a category page. Newest first,
 * 20 at a time, more only on "Show more". No rankings, no counts. (17G-2, U5: the place page may carry one guarded average rating.)
 */
class PostCollectionViewModel(private val repository: DiscoverRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(PostCollectionUiState())
    val uiState: StateFlow<PostCollectionUiState> = _uiState.asStateFlow()

    private var nextCursor: String? = null

    /** Phase 17E: the butterfly and Save from the list. */
    val marks = PostMarks(repository, viewModelScope, { id, f ->
        _uiState.update { s -> s.copy(items = s.items.map { if (it.publicId == id) f(it) else it }) }
    }) { failure -> _uiState.update { it.copy(failure = failure, sessionExpired = failure.isSessionExpired()) } }

    /** Phase 17G-2 (U5): "I went" and the person's own rating for the place this page is about. */
    private val visits = PlaceVisits(repository, viewModelScope,
        onVisit = { v -> _uiState.update { it.copy(visit = v) } },
        onWent = { _uiState.update { it.copy(visitSheet = true) } },
        onBusy = { b -> _uiState.update { it.copy(visitBusy = b) } },
        onFailure = { failure -> _uiState.update { it.copy(failure = failure, sessionExpired = failure.isSessionExpired()) } })

    private fun placeId(): String? = _uiState.value.place?.publicId

    /** The "I went here" button: mark, then the screen asks how it was (and offers the composer). */
    fun onWent() {
        placeId()?.let { visits.went(it) }
    }

    /** Opens the same sheet again for a place already marked (to rate, change or share). */
    fun onOpenVisitSheet() {
        if (_uiState.value.visit != null) _uiState.update { it.copy(visitSheet = true) }
    }

    fun onRate(rating: Int) {
        placeId()?.let { visits.rate(it, rating) }
    }

    fun onClearRating() {
        placeId()?.let { visits.clearRating(it) }
    }

    fun onNotWent() {
        placeId()?.let { visits.notWent(it) }
    }

    fun onVisitSheetHandled() = _uiState.update { it.copy(visitSheet = false) }

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
                        _uiState.update { it.copy(place = r.data.place, rating = r.data.rating, visit = r.data.yourVisit) }
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
