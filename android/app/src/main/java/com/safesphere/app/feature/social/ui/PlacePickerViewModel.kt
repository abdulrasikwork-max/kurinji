package com.safesphere.app.feature.social.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.location.GeoHash
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.location.LocationFix
import com.safesphere.app.core.location.LocationProvider
import com.safesphere.app.core.location.OneFix
import com.safesphere.app.core.location.OneFixes
import com.safesphere.app.core.location.PreciseFix
import com.safesphere.app.core.location.oneFix
import com.safesphere.app.core.location.preciseFix
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.social.data.PlaceMemory
import com.safesphere.app.feature.social.domain.DiscoverRepository
import com.safesphere.app.feature.social.domain.ExperienceRepository
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.Place
import com.safesphere.app.feature.social.domain.PrivatePlacePolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A place in a list, with whether it is near one of the person's private places. */
data class PlaceRow(val place: Place, val nearPrivate: Boolean)

/**
 * Phase 16 location audit: each problem has its own words and its own way out (allow, open settings, turn location on,
 * try again, or pick "Only the city" / "No place"). Phase 17D: NEEDS_PRECISE is "approximate only" (upgrade) and
 * POSITION_ROUGH is "precise, but the signal is weak" (move outside or wait, then try again).
 */
enum class PickerNotice { NEEDS_PERMISSION, LOCATION_OFF, LOCATION_UNAVAILABLE, NEEDS_PRECISE, POSITION_ROUGH, NEAR_PRIVATE }

/** Phase 17D: a search that did not work never blocks the other two ways to choose a place. */
enum class SearchProblem { TOO_MANY, FAILED }

data class PlacePickerUiState(
    val recent: List<PlaceRow> = emptyList(),
    val nearby: List<PlaceRow>? = null,
    /** The ~150 m cell of the precise position found for "Use where I am" (memory only, to add a place there). */
    val hereCell: String? = null,
    val hereIsPrivate: Boolean = false,
    /** Adding a place needs a precise fix with a good signal (like reporting in Community). */
    val canAddHere: Boolean = false,
    /** Phase 17D: the search for that precise position is running; [preciseAccuracyMeters] = the best so far. */
    val findingPrecise: Boolean = false,
    val preciseAccuracyMeters: Float? = null,
    val notice: PickerNotice? = null,
    val isBusy: Boolean = false,
    /** Phase 17D: place-name search. Nothing but the typed words is sent. */
    val query: String = "",
    val searchResults: List<PlaceRow>? = null,
    val searching: Boolean = false,
    val searchProblem: SearchProblem? = null,
    /** One-shot: the chosen place; the screen returns it to the composer. */
    val chosen: Place? = null,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
)

/**
 * Phase 13B, reworked in 17D: choose where an experience happened, three ways: "Use where I am", "Search" (place names
 * only), "Recent". "Use where I am" takes a position only when tapped, the permission asked in context: first a quick
 * Wi-Fi / cell / recent position lists the places of the ~1 km area (only the 6-character area is sent); then, with
 * precise permission, a short visible search looks for a position good enough to ADD a place (only the 7-character
 * cell is sent). A position rougher than 100 m never creates a place. Places near a private place are marked and
 * cannot be added as public places. The search stops when the person leaves the screen.
 */
class PlacePickerViewModel(
    private val repository: ExperienceRepository,
    private val placeMemory: PlaceMemory,
    private val locationProvider: LocationProvider,
    private val discover: DiscoverRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlacePickerUiState())
    val uiState: StateFlow<PlacePickerUiState> = _uiState.asStateFlow()

    private var privateCells: List<String> = emptyList()
    private var privateUnknown = false
    private var searchJob: Job? = null
    private var preciseJob: Job? = null

    init {
        viewModelScope.launch {
            val snapshot = placeMemory.snapshot()
            privateUnknown = snapshot.privatePlacesUnknown
            privateCells = snapshot.data.privatePlaces.map { it.cell }
            _uiState.update { it.copy(recent = snapshot.data.recent.map(::row)) }
        }
    }

    // ---- Use where I am ---------------------------------------------------------------------------------------------

    fun onNearMe() {
        if (_uiState.value.isBusy) return
        preciseJob?.cancel()
        preciseJob = viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, notice = null, failure = null, findingPrecise = false) }
            try {
                // Listing the area needs only ~1 km: a recent or Wi-Fi / cell position is enough, so it works indoors.
                when (val result = locationProvider.oneFix(OneFixes.AREA_MAX_AGE_MILLIS, FIX_TIMEOUT_MILLIS, areaOnly = true)) {
                    is OneFix.Got -> {
                        listArea(result.fix)
                        _uiState.update { it.copy(isBusy = false) } // the list is usable while the precise search goes on
                        findPrecisePosition(result.access)
                    }
                    OneFix.NeedsPermission -> notice(PickerNotice.NEEDS_PERMISSION)
                    OneFix.LocationOff -> notice(PickerNotice.LOCATION_OFF)
                    OneFix.NotFound, OneFix.Unavailable -> notice(PickerNotice.LOCATION_UNAVAILABLE)
                }
            } finally {
                _uiState.update { it.copy(isBusy = false) } // also when the screen was left meanwhile
            }
        }
    }

    fun onPermissionResult() = onNearMe()

    /** "Try again" after a weak signal: only the search for the precise position, the list stays. */
    fun onFindPrecisePosition() {
        if (_uiState.value.findingPrecise) return
        preciseJob?.cancel()
        preciseJob = viewModelScope.launch { findPrecisePosition(locationProvider.access()) }
    }

    /** The screen is no longer visible: location is used only while it is open. */
    fun onScreenHidden() {
        preciseJob?.cancel()
        _uiState.update { it.copy(findingPrecise = false) }
    }

    private suspend fun findPrecisePosition(access: LocationAccess) {
        if (access != LocationAccess.PRECISE) {
            // Viewing works with an approximate permission, adding a place does not: offer the upgrade.
            _uiState.update { it.copy(notice = it.notice ?: PickerNotice.NEEDS_PRECISE, canAddHere = false) }
            return
        }
        _uiState.update { it.copy(findingPrecise = true, preciseAccuracyMeters = null, notice = null) }
        try {
            val result = locationProvider.preciseFix { accuracy ->
                _uiState.update { it.copy(preciseAccuracyMeters = accuracy) }
            }
            when (result) {
                is PreciseFix.Good -> placeHere(result.fix)
                is PreciseFix.Rough, PreciseFix.NotFound -> weakSignal()
                PreciseFix.NeedsPrecisePermission -> weakSignal(PickerNotice.NEEDS_PRECISE)
                PreciseFix.NeedsPermission -> weakSignal(PickerNotice.NEEDS_PERMISSION)
                PreciseFix.LocationOff -> weakSignal(PickerNotice.LOCATION_OFF)
                PreciseFix.Unavailable -> weakSignal(PickerNotice.LOCATION_UNAVAILABLE)
            }
        } finally {
            _uiState.update { it.copy(findingPrecise = false) }
        }
    }

    private fun weakSignal(notice: PickerNotice = PickerNotice.POSITION_ROUGH) =
        _uiState.update { it.copy(notice = notice, canAddHere = false, hereCell = null) }

    /** A position good enough to name a place: only its ~150 m cell is kept (memory only). */
    private suspend fun placeHere(fix: LocationFix) {
        val cell = GeoHash.encode(fix.latitude, fix.longitude, GeoHash.BLOCK)
        val herePrivate = privateUnknown || PrivatePlacePolicy.isPrivate(cell, privateCells)
        val newArea = cell.take(GeoHash.AREA)
        val listedArea = listedArea
        _uiState.update {
            it.copy(hereCell = cell, hereIsPrivate = herePrivate, canAddHere = !herePrivate,
                notice = if (herePrivate) PickerNotice.NEAR_PRIVATE else null)
        }
        if (listedArea != null && listedArea != newArea) listPlaces(newArea) // the precise position is in another area
    }

    private var listedArea: String? = null

    private suspend fun listArea(fix: LocationFix) {
        val cell = GeoHash.encode(fix.latitude, fix.longitude, GeoHash.BLOCK)
        val herePrivate = privateUnknown || PrivatePlacePolicy.isPrivate(cell, privateCells)
        _uiState.update {
            // The area fix is not good enough to ADD a place (that waits for the precise position below).
            it.copy(hereCell = null, hereIsPrivate = herePrivate, canAddHere = false,
                notice = if (herePrivate) PickerNotice.NEAR_PRIVATE else null)
        }
        listPlaces(cell.take(GeoHash.AREA))
    }

    private suspend fun listPlaces(area: String) {
        listedArea = area
        when (val result = repository.nearbyPlaces(area)) {
            is ApiResult.Success -> _uiState.update { it.copy(isBusy = false, nearby = result.data.map(::row)) }
            is ApiResult.Failure -> fail(result)
        }
    }

    // ---- Search -----------------------------------------------------------------------------------------------------

    /** Typing: after a short pause, the words (never a position) are looked up as place names. */
    fun onQuery(text: String) {
        searchJob?.cancel()
        val trimmed = text.trim()
        _uiState.update {
            it.copy(query = text, searchProblem = null, searching = false,
                searchResults = if (!isSearchable(trimmed)) null else it.searchResults)
        }
        if (!isSearchable(trimmed)) return
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            _uiState.update { it.copy(searching = true) }
            when (val result = discover.searchPlaces(trimmed, null)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(searching = false, searchResults = result.data.map(::row), searchProblem = null)
                }
                // The server refused the words themselves (400): that is "no place has that name", not a broken search.
                is ApiResult.Failure -> if (result.error == ApiError.VALIDATION) _uiState.update {
                    it.copy(searching = false, searchResults = emptyList(), searchProblem = null)
                } else _uiState.update {
                    it.copy(searching = false, searchResults = null,
                        searchProblem = if (result.error == ApiError.TOO_MANY_ATTEMPTS) SearchProblem.TOO_MANY else SearchProblem.FAILED,
                        sessionExpired = it.sessionExpired || result.isSessionExpired())
                }
            }
        }
    }

    // ---- Choose / add -----------------------------------------------------------------------------------------------

    fun onPick(place: Place) = _uiState.update { it.copy(chosen = place) }

    fun onAddPlace(name: String, category: Interest) {
        val state = _uiState.value
        val cell = state.hereCell ?: return
        if (state.isBusy) return
        if (state.hereIsPrivate) {
            _uiState.update { it.copy(notice = PickerNotice.NEAR_PRIVATE) }
            return
        }
        if (!state.canAddHere) {
            _uiState.update { it.copy(notice = PickerNotice.POSITION_ROUGH) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, failure = null) }
            when (val result = repository.createPlace(name, category, cell)) {
                is ApiResult.Success -> _uiState.update { it.copy(isBusy = false, chosen = result.data) }
                is ApiResult.Failure -> fail(result)
            }
        }
    }

    fun onChosenHandled() = _uiState.update { it.copy(chosen = null) }
    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun notice(notice: PickerNotice) = _uiState.update { it.copy(isBusy = false, notice = notice) }

    private fun row(place: Place) =
        PlaceRow(place, privateUnknown || PrivatePlacePolicy.isPrivate(place.cell, privateCells))

    private fun fail(failure: ApiResult.Failure) = _uiState.update {
        it.copy(isBusy = false, failure = failure, sessionExpired = failure.isSessionExpired())
    }

    companion object {
        const val MAX_ADD_ACCURACY_METERS = 100f
        /** Phase 16: 15 s instead of 20 s; the fused provider answers a one-time request much sooner. */
        const val FIX_TIMEOUT_MILLIS = OneFixes.TIMEOUT_MILLIS
        /** A fix up to 30 s old is "here" for adding a place (the ~150 m cell). */
        const val FRESH_FIX_MAX_AGE_MILLIS = 30_000L
        /** The server needs 2 to 40 characters (PlaceService); shorter text is never sent. */
        const val SEARCH_MIN_LENGTH = 2
        const val SEARCH_DEBOUNCE_MILLIS = 300L

        /** Mirrors the server (PlaceService.search): 2 to 40 characters with at least one letter or digit. */
        fun isSearchable(trimmed: String): Boolean =
            trimmed.codePointCount(0, trimmed.length) in SEARCH_MIN_LENGTH..SEARCH_MAX_LENGTH && trimmed.any { it.isLetterOrDigit() }

        const val SEARCH_MAX_LENGTH = 40

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                PlacePickerViewModel(container.experienceRepository, container.placeMemory, container.screenLocationProvider,
                    container.discoverRepository)
            }
        }
    }
}
