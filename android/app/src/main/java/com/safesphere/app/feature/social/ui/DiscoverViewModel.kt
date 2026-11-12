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
import com.safesphere.app.feature.social.domain.Cities
import com.safesphere.app.feature.social.domain.City
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
    /** Phase 17E: marks in flight ("react:<id>", "save:<id>"), so a double tap sends nothing twice. */
    val marking: Set<String> = emptySet(),
    /** One-shot: a save was confirmed by the server (the screen says "Saved" once and the butterfly flies). */
    val savedNow: String? = null,
    /** IRL redesign (R1), one-shot: a butterfly or Save could not be sent. Said in a short message; the feed stays. */
    val markFailure: ApiResult.Failure? = null,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
)

/**
 * Phase 13C, rebuilt in Phase 16 (docs/PHASE16.md): Discover is one feed (DiscoverFeed): the city's week (with "Talking
 * now", U2) and posts from people you know, which load the next page as the person nears the end and then END (U3).
 * Saving with the butterfly works from the feed. "Near me" lists places around the person's ~1 km area from ONE fix;
 * only the 6-character area leaves the phone. Nothing about other people is stored on the phone; since R6 the person's
 * own Home city is remembered per account ([HomeCityChoice]), so Home does not ask again at every start.
 */
class DiscoverViewModel(
    private val repository: DiscoverRepository,
    private val social: SocialRepository,
    private val experiences: ExperienceRepository,
    private val locationProvider: LocationProvider,
    private val freshness: com.safesphere.app.feature.social.domain.FeedFreshness =
        com.safesphere.app.feature.social.domain.FeedFreshness(),
    private val homeCity: com.safesphere.app.feature.social.domain.HomeCityChoice? = null,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    private var started = false
    private var connectionsCursor: String? = null
    private var loadedAtMs = 0L
    private var seenVersion = 0

    /** Reads the remembered city (R6), else the profile's city, once; loads posts from people you know in parallel. */
    fun start() {
        if (started) return
        started = true
        stamp()
        reloadConnections()
        viewModelScope.launch {
            val remembered = homeCity?.get()
            if (remembered != null) {
                show(remembered)
                return@launch
            }
            val city = when (val profile = social.ownProfile()) {
                is ApiResult.Success -> Cities.match(profile.data?.city)
                is ApiResult.Failure -> {
                    fail(profile)
                    null
                }
            }
            if (city == null) _uiState.update { it.copy(needsCity = true) } else show(city)
        }
    }

    /** The person chose a city on Home: shown, and remembered for this account on this phone (R6). */
    fun onCity(city: City) {
        if (city.isOther) return
        show(city)
        viewModelScope.launch { homeCity?.set(city) }
    }

    private fun show(city: City) {
        freshness.cityCode = city.code
        _uiState.update { it.copy(city = city, needsCity = false, edition = null, nearby = null, nearNotice = null) }
        loadEdition()
    }

    /** Pull again (tab re-tapped, "Try again"): the city's week and the first page of people you know. */
    fun reload() {
        _uiState.update { it.copy(failure = null) }
        if (_uiState.value.city != null) loadEdition()
        reloadConnections()
    }

    /**
     * IRL redesign (R1): reads the feed again WITHOUT emptying it first (the tab was tapped again, the person came back
     * after sharing, or the feed is old). The posts on the screen stay until the new ones arrive.
     */
    fun refresh() {
        if (!started) return
        stamp()
        _uiState.update { it.copy(failure = null) }
        if (_uiState.value.city != null) loadEdition()
        if (!_uiState.value.connectionsLoading) {
            connectionsCursor = null
            loadConnections(replace = true)
        }
    }

    /** The screen is shown again: read again when the person's own posts changed or the feed is older than [STALE_MS]. */
    fun onShown() {
        if (!started) return
        if (freshness.version != seenVersion || nowMs() - loadedAtMs > STALE_MS) refresh()
    }

    fun onMarkFailureShown() = _uiState.update { it.copy(markFailure = null) }

    private fun stamp() {
        loadedAtMs = nowMs()
        seenVersion = freshness.version
    }

    /** U3: the next page of posts from people you know, when the person nears the end (never past the last page). */
    fun onNearEnd() {
        val state = _uiState.value
        if (state.connectionsHasMore && !state.connectionsLoading && state.failure == null) loadConnections()
    }

    /**
     * Phase 17E (D-3): the butterfly (a reaction) on a feed card, given or taken back. Shown at once everywhere the post
     * appears; put back if the server says no (the server is idempotent, so a lost answer is harmless).
     */
    fun onReact(post: Post) = mark("react:" + post.publicId, post.publicId, { it.copy(youReacted = !post.youReacted) },
        { it.copy(youReacted = post.youReacted) }) {
        if (post.youReacted) repository.unreact(post.publicId) else repository.react(post.publicId)
    }

    /** Phase 17E (D-3): Save (a private bookmark) from a feed card, or take it out of Saved again. */
    fun onSave(post: Post) = mark("save:" + post.publicId, post.publicId, { it.copy(saved = !post.saved) },
        { it.copy(saved = post.saved) }, confirmed = { if (!post.saved) _uiState.update { s -> s.copy(savedNow = post.publicId) } }) {
        if (post.saved) repository.unsave(post.publicId) else repository.save(post.publicId)
    }

    private fun mark(key: String, postId: String, apply: (Post) -> Post, undo: (Post) -> Post,
                     confirmed: () -> Unit = {}, call: suspend () -> ApiResult<Unit>) {
        if (key in _uiState.value.marking) return
        _uiState.update { it.withPost(postId, apply).copy(marking = it.marking + key) }
        viewModelScope.launch {
            when (val result = call()) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(marking = it.marking - key) }
                    confirmed()
                }
                is ApiResult.Failure -> {
                    // R1: one mark that did not go through is said once; it must not make the whole feed look broken.
                    _uiState.update {
                        it.withPost(postId, undo).copy(marking = it.marking - key, markFailure = result,
                            sessionExpired = it.sessionExpired || result.isSessionExpired())
                    }
                }
            }
        }
    }

    fun onSavedShown() = _uiState.update { it.copy(savedNow = null) }

    /** Phase 16: places around the person's ~1 km area from ONE fix. */
    fun onNearMe() {
        if (_uiState.value.nearbyBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(nearbyBusy = true, nearNotice = null) }
            // Phase 17B: only the ~1 km area is needed, so Wi-Fi / cell is enough (works indoors without GPS).
            when (val fix = locationProvider.oneFix(areaOnly = true)) {
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

    private fun loadConnections(replace: Boolean = false) {
        if (_uiState.value.connectionsLoading) return
        _uiState.update { it.copy(connectionsLoading = true) }
        viewModelScope.launch {
            when (val result = repository.fromConnections(connectionsCursor)) {
                is ApiResult.Success -> {
                    connectionsCursor = result.data.nextCursor
                    _uiState.update {
                        it.copy(connectionsLoading = false, connectionsLoaded = true,
                            connections = if (replace) result.data.items else it.connections + result.data.items,
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

        /** A feed older than this is read again when it is shown (R1). */
        const val STALE_MS = 5 * 60 * 1000L

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                DiscoverViewModel(container.discoverRepository, container.socialRepository,
                    container.experienceRepository, container.screenLocationProvider, container.feedFreshness, homeCity = container.homeCityChoice)
            }
        }
    }
}

/** The same post everywhere in the feed shows the viewer's new marks. */
internal fun DiscoverUiState.withPost(postId: String, change: (Post) -> Post): DiscoverUiState {
    fun DiscoverItem.mark() = if (post.publicId == postId) copy(post = change(post)) else this
    return copy(
        edition = edition?.let { e -> e.copy(sections = e.sections.map { s -> s.copy(items = s.items.map { it.mark() }) }) },
        connections = connections.map { it.mark() }
    )
}
