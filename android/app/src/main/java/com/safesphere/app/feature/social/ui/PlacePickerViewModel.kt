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
import com.safesphere.app.core.location.LocationUnavailableReason
import com.safesphere.app.core.location.LocationUpdate
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.social.data.PlaceMemory
import com.safesphere.app.feature.social.domain.ExperienceRepository
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.Place
import com.safesphere.app.feature.social.domain.PrivatePlacePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A place in a list, with whether it is near one of the person's private places. */
data class PlaceRow(val place: Place, val nearPrivate: Boolean)

enum class PickerNotice { NEEDS_PERMISSION, LOCATION_UNAVAILABLE, NEEDS_PRECISE, NEAR_PRIVATE }

data class PlacePickerUiState(
    val recent: List<PlaceRow> = emptyList(),
    val nearby: List<PlaceRow>? = null,
    /** The ~150 m cell of the one fix taken for "Near me" (kept in memory only, to add a place there). */
    val hereCell: String? = null,
    val hereIsPrivate: Boolean = false,
    /** Adding a place needs a precise fix with a good signal (like reporting in Community). */
    val canAddHere: Boolean = false,
    val notice: PickerNotice? = null,
    val isBusy: Boolean = false,
    /** One-shot: the chosen place; the screen returns it to the composer. */
    val chosen: Place? = null,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
)

/**
 * Phase 13B: choose where an experience happened. "Near me" takes ONE fresh fix (only when tapped, the location
 * permission asked in context), then stops; only its 6-character area goes to the server to list places, and only the
 * 7-character cell to add one. Places near a private place are marked and cannot be added as public places.
 */
class PlacePickerViewModel(
    private val repository: ExperienceRepository,
    private val placeMemory: PlaceMemory,
    private val locationProvider: LocationProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlacePickerUiState())
    val uiState: StateFlow<PlacePickerUiState> = _uiState.asStateFlow()

    private var privateCells: List<String> = emptyList()
    private var privateUnknown = false

    init {
        viewModelScope.launch {
            val snapshot = placeMemory.snapshot()
            privateUnknown = snapshot.privatePlacesUnknown
            privateCells = snapshot.data.privatePlaces.map { it.cell }
            _uiState.update { it.copy(recent = snapshot.data.recent.map(::row)) }
        }
    }

    fun onNearMe() {
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, notice = null, failure = null) }
            when (val update = awaitFix()) {
                is LocationUpdate.Unavailable -> _uiState.update {
                    it.copy(isBusy = false, notice = if (update.reason == LocationUnavailableReason.PERMISSION_DENIED)
                        PickerNotice.NEEDS_PERMISSION else PickerNotice.LOCATION_UNAVAILABLE)
                }
                is LocationUpdate.Fix -> showNearby(update.fix)
            }
        }
    }

    fun onPermissionResult() = onNearMe()

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
            _uiState.update { it.copy(notice = PickerNotice.NEEDS_PRECISE) }
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

    private suspend fun showNearby(fix: LocationFix) {
        val cell = GeoHash.encode(fix.latitude, fix.longitude, GeoHash.BLOCK)
        val precise = locationProvider.access() == LocationAccess.PRECISE &&
            (fix.accuracyMeters ?: Float.MAX_VALUE) <= MAX_ADD_ACCURACY_METERS
        val herePrivate = privateUnknown || PrivatePlacePolicy.isPrivate(cell, privateCells)
        _uiState.update {
            it.copy(hereCell = cell, hereIsPrivate = herePrivate, canAddHere = precise && !herePrivate,
                notice = if (herePrivate) PickerNotice.NEAR_PRIVATE else null)
        }
        when (val result = repository.nearbyPlaces(cell.take(GeoHash.AREA))) {
            is ApiResult.Success -> _uiState.update { it.copy(isBusy = false, nearby = result.data.map(::row)) }
            is ApiResult.Failure -> fail(result)
        }
    }

    private fun row(place: Place) =
        PlaceRow(place, privateUnknown || PrivatePlacePolicy.isPrivate(place.cell, privateCells))

    /** The first usable answer from the provider, or "no fix" after a while; collection then stops. */
    private suspend fun awaitFix(): LocationUpdate {
        if (locationProvider.access() == LocationAccess.NONE) {
            return LocationUpdate.Unavailable(LocationUnavailableReason.PERMISSION_DENIED)
        }
        return withTimeoutOrNull(FIX_TIMEOUT_MILLIS) {
            locationProvider.updates(FIX_INTERVAL_MILLIS).first { update ->
                update is LocationUpdate.Fix ||
                    (update is LocationUpdate.Unavailable && update.reason != LocationUnavailableReason.NO_FIX)
            }
        } ?: LocationUpdate.Unavailable(LocationUnavailableReason.NO_FIX)
    }

    private fun fail(failure: ApiResult.Failure) = _uiState.update {
        it.copy(isBusy = false, failure = failure, sessionExpired = failure.isSessionExpired())
    }

    companion object {
        const val MAX_ADD_ACCURACY_METERS = 100f
        const val FIX_TIMEOUT_MILLIS = 20_000L
        const val FIX_INTERVAL_MILLIS = 2_000L

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                PlacePickerViewModel(container.experienceRepository, container.placeMemory, container.screenLocationProvider)
            }
        }
    }
}
