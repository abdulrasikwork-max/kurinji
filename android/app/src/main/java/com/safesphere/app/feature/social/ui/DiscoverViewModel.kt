package com.safesphere.app.feature.social.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.location.GeoHash
import com.safesphere.app.core.location.LocationProvider
import com.safesphere.app.core.location.OneFix
import com.safesphere.app.core.location.oneFix
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.social.domain.ButterflyState
import com.safesphere.app.feature.social.domain.City
import com.safesphere.app.feature.social.domain.CityMatch
import com.safesphere.app.feature.social.domain.DiscoverItem
import com.safesphere.app.feature.social.domain.DiscoverRepository
import com.safesphere.app.feature.social.domain.Edition
import com.safesphere.app.feature.social.domain.ExperienceRepository
import com.safesphere.app.feature.social.domain.Place
import com.safesphere.app.feature.social.domain.Post
import com.safesphere.app.feature.social.domain.SocialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Phase 16: what "Near me" found, or why it found nothing. */
enum class NearNotice { NEEDS_PERMISSION, LOCATION_OFF, NOT_FOUND }

data class DiscoverUiState(
    /** The city of the feed; null until the profile was read and while the person has not chosen one. */
    val city: City? = null,
    /** The profile named no launch city: the person picks one (kept on this screen only). */
    val needsCity: Boolean = false,
    val edition: Edition? = null,
    val editionLoading: Boolean = false,
    val connections: List<DiscoverItem> = emptyList(),
    val connectionsHasMore: Boolean = false,
    val connectionsLoaded: Boolean = false,
    val connectionsLoading: Boolean = false,
    val query: String = "",
    val places: List<Place> = emptyList(),
    val searching: Boolean = false,
    val searched: Boolean = false,
    /** Phase 16: places around the person's ~1 km area ("Near me"); null = not asked. */
    val nearby: List<Place>? = null,
    val nearbyBusy: Boolean = false,
    val nearNotice: NearNotice? = null,
    /** Phase 16: saves in flight (post ids), so a double tap sends nothing twice. */
    val saving: Set<String> = emptySet(),
    /** One-shot: a save was confirmed by the server (the screen says "Saved" once). */
    val savedNow: Boolean = false,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
)

/**
 * Phase 13C, rebuilt in Phase 16 (docs/PHASE16.md): Discover is one feed (DiscoverFeed): the city's week (with "Talking
 * now", U2) and posts from people you know, which load the next page as the person nears the end and then END (U3).
 * Saving with the butterfly works from the feed. "Near me" lists places around the person's ~1 km area from ONE fix;
 * only the 6-character area leaves the phone. Nothing is stored on the phone, not even the chosen city.
 */
class DiscoverViewModel(
    private val repository: DiscoverRepository,
    private val social: SocialRepository,
    private val experiences: ExperienceRepository,
    private val locationProvider: LocationProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    private var started = false
    private var connectionsCursor: String? = null

    /** Reads the profile's city once, and loads posts from people you know in parallel. */
    fun start() {
        if (started) return
        started = true
        reloadConnections()
        viewModelScope.launch {
            val city = when (val profile = social.ownProfile()) {
                is ApiResult.Success -> CityMatch.of(profile.data?.city)
                is ApiResult.Failure -> {
                    fail(profile)
                    null
                }
            }
            if (city == null) _uiState.update { it.copy(needsCity = true) } else onCity(city)
        }
    }

    fun onCity(city: City) {
        if (city == City.OTHER) return
        _uiState.update { it.copy(city = city, needsCity = false, edition = null, nearby = null, nearNotice = null) }
        loadEdition()
    }

    /** Pull again (tab re-tapped, "Try again"): the city's week and the first page of people you know. */
    fun reload() {
        _uiState.update { it.copy(failure = null) }
        if (_uiState.value.city != null) loadEdition()
        reloadConnections()
    }

    /** U3: the next page of posts from people you know, when the person nears the end (never past the last page). */
    fun onNearEnd() {
        val state = _uiState.value
        if (state.connectionsHasMore && !state.connectionsLoading && state.failure == null) loadConnections()
    }

    /** The butterfly on a feed card: save (want to go), or take a saved post out again. */
    fun onSave(post: Post) {
        if (post.publicId in _uiState.value.saving) return
        _uiState.update { it.copy(saving = it.saving + post.publicId) }
        viewModelScope.launch {
            val result: ApiResult<ButterflyState?> = if (post.yourButterfly == null) {
                when (val r = repository.setButterfly(post.publicId, ButterflyState.WANT)) {
                    is ApiResult.Success -> ApiResult.Success(r.data)
                    is ApiResult.Failure -> r
                }
            } else {
                when (val r = repository.removeButterfly(post.publicId)) {
                    is ApiResult.Success -> ApiResult.Success(null)
                    is ApiResult.Failure -> r
                }
            }
            when (result) {
                is ApiResult.Success -> _uiState.update {
                    it.withButterfly(post.publicId, result.data).copy(saving = it.saving - post.publicId,
                        savedNow = result.data != null)
                }
                is ApiResult.Failure -> {
                    _uiState.update { it.copy(saving = it.saving - post.publicId) }
                    fail(result)
                }
            }
        }
    }

    fun onSavedShown() = _uiState.update { it.copy(savedNow = false) }

    /** Phase 16: places around the person's ~1 km area from ONE fix. */
    fun onNearMe() {
        if (_uiState.value.nearbyBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(nearbyBusy = true, nearNotice = null) }
            when (val fix = locationProvider.oneFix()) {
                is OneFix.Got -> {
                    val area = GeoHash.encode(fix.fix.latitude, fix.fix.longitude, GeoHash.AREA)
                    when (val result = experiences.nearbyPlaces(area)) {
                        is ApiResult.Success -> _uiState.update { it.copy(nearbyBusy = false, nearby = result.data) }
                        is ApiResult.Failure -> {
                            _uiState.update { it.copy(nearbyBusy = false) }
                            fail(result)
                        }
                    }
                }
                OneFix.NeedsPermission -> near(NearNotice.NEEDS_PERMISSION)
                OneFix.LocationOff -> near(NearNotice.LOCATION_OFF)
                OneFix.NotFound, OneFix.Unavailable -> near(NearNotice.NOT_FOUND)
            }
        }
    }

    fun onCloseNearby() = _uiState.update { it.copy(nearby = null, nearNotice = null) }

    fun onQuery(text: String) = _uiState.update { it.copy(query = text) }

    /** Searches place names (2 characters or more); categories are matched on the screen. */
    fun onSearch() {
        val query = _uiState.value.query.trim()
        if (query.length < SEARCH_MIN || _uiState.value.searching) return
        viewModelScope.launch {
            _uiState.update { it.copy(searching = true, failure = null) }
            when (val result = repository.searchPlaces(query, _uiState.value.city)) {
                is ApiResult.Success -> _uiState.update { it.copy(searching = false, searched = true, places = result.data) }
                is ApiResult.Failure -> {
                    _uiState.update { it.copy(searching = false) }
                    fail(result)
                }
            }
        }
    }

    fun onClearSearch() = _uiState.update { it.copy(query = "", places = emptyList(), searched = false) }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun near(notice: NearNotice) = _uiState.update { it.copy(nearbyBusy = false, nearNotice = notice) }

    private fun loadEdition() {
        val city = _uiState.value.city ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(editionLoading = true, failure = null) }
            when (val result = repository.edition(city)) {
                is ApiResult.Success -> if (_uiState.value.city == city) {
                    _uiState.update { it.copy(editionLoading = false, edition = result.data) }
                }
                is ApiResult.Failure -> {
                    _uiState.update { it.copy(editionLoading = false) }
                    fail(result)
                }
            }
        }
    }

    private fun reloadConnections() {
        connectionsCursor = null
        _uiState.update { it.copy(connections = emptyList(), connectionsHasMore = false, connectionsLoaded = false) }
        loadConnections()
    }

    private fun loadConnections() {
        if (_uiState.value.connectionsLoading) return
        _uiState.update { it.copy(connectionsLoading = true) }
        viewModelScope.launch {
            when (val result = repository.fromConnections(connectionsCursor)) {
                is ApiResult.Success -> {
                    connectionsCursor = result.data.nextCursor
                    _uiState.update {
                        it.copy(connectionsLoading = false, connectionsLoaded = true,
                            connections = it.connections + result.data.items,
                            connectionsHasMore = result.data.nextCursor != null)
                    }
                }
                is ApiResult.Failure -> {
                    // A failed page ends the list for now (no retry loop); "Try again" reloads.
                    _uiState.update { it.copy(connectionsLoading = false, connectionsHasMore = false) }
                    fail(result)
                }
            }
        }
    }

    private fun fail(failure: ApiResult.Failure) =
        _uiState.update { it.copy(failure = failure, sessionExpired = failure.isSessionExpired()) }

    companion object {
        const val SEARCH_MIN = 2

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                DiscoverViewModel(container.discoverRepository, container.socialRepository,
                    container.experienceRepository, container.screenLocationProvider)
            }
        }
    }
}

/** The same post everywhere in the feed shows the viewer's new save state. */
private fun DiscoverUiState.withButterfly(postId: String, state: ButterflyState?): DiscoverUiState {
    fun DiscoverItem.mark() = if (post.publicId == postId) copy(post = post.copy(yourButterfly = state)) else this
    return copy(
        edition = edition?.let { e -> e.copy(sections = e.sections.map { s -> s.copy(items = s.items.map { it.mark() }) }) },
        connections = connections.map { it.mark() }
    )
}
