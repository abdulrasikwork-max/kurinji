package com.safesphere.app.feature.social.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.location.GeoHash
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.location.LocationProvider
import com.safesphere.app.core.location.LocationUnavailableReason
import com.safesphere.app.core.location.LocationUpdate
import com.safesphere.app.feature.social.data.PlaceMemory
import com.safesphere.app.feature.social.domain.PrivatePlace
import com.safesphere.app.feature.social.domain.PrivatePlaceKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class PrivatePlacesNotice { ADDED, NEEDS_PERMISSION, LOCATION_UNAVAILABLE, NEEDS_PRECISE, NOT_SAVED }

data class PrivatePlacesUiState(
    val isLoading: Boolean = true,
    val places: List<PrivatePlace> = emptyList(),
    /** The encrypted file exists but cannot be read: every place counts as private until the list is started again. */
    val unreadable: Boolean = false,
    val isBusy: Boolean = false,
    /** One-shot message. */
    val notice: PrivatePlacesNotice? = null
)

/**
 * Phase 13B (design §4.2, Q9): mark home, hostel or work from ONE fresh fix; only the ~150 m cell and the kind are
 * kept, encrypted on this phone, never sent. Removing one deletes it at once. Signing out removes them all.
 */
class PrivatePlacesViewModel(
    private val placeMemory: PlaceMemory,
    private val locationProvider: LocationProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(PrivatePlacesUiState())
    val uiState: StateFlow<PrivatePlacesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { refresh() }
    }

    fun onMarkHere(kind: PrivatePlaceKind) {
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val notice = when (val update = awaitFix()) {
                is LocationUpdate.Unavailable -> if (update.reason == LocationUnavailableReason.PERMISSION_DENIED)
                    PrivatePlacesNotice.NEEDS_PERMISSION else PrivatePlacesNotice.LOCATION_UNAVAILABLE
                is LocationUpdate.Fix -> {
                    val fix = update.fix
                    val good = locationProvider.access() == LocationAccess.PRECISE &&
                        (fix.accuracyMeters ?: Float.MAX_VALUE) <= PlacePickerViewModel.MAX_ADD_ACCURACY_METERS
                    if (!good) PrivatePlacesNotice.NEEDS_PRECISE
                    else if (placeMemory.addPrivate(kind, GeoHash.encode(fix.latitude, fix.longitude, GeoHash.BLOCK)))
                        PrivatePlacesNotice.ADDED
                    else PrivatePlacesNotice.NOT_SAVED
                }
            }
            refresh()
            _uiState.update { it.copy(isBusy = false, notice = notice) }
        }
    }

    fun onPermissionResult(kind: PrivatePlaceKind) = onMarkHere(kind)

    fun onRemove(place: PrivatePlace) {
        viewModelScope.launch {
            placeMemory.removePrivate(place.id)
            refresh()
        }
    }

    /** Starts an unreadable list again (the old one cannot be opened by anyone). */
    fun onReset() {
        viewModelScope.launch {
            placeMemory.reset()
            refresh()
        }
    }

    fun onNoticeShown() = _uiState.update { it.copy(notice = null) }

    private suspend fun refresh() {
        val snapshot = placeMemory.snapshot()
        _uiState.update {
            it.copy(isLoading = false, places = snapshot.data.privatePlaces, unreadable = snapshot.unreadable)
        }
    }

    private suspend fun awaitFix(): LocationUpdate {
        if (locationProvider.access() == LocationAccess.NONE) {
            return LocationUpdate.Unavailable(LocationUnavailableReason.PERMISSION_DENIED)
        }
        return withTimeoutOrNull(PlacePickerViewModel.FIX_TIMEOUT_MILLIS) {
            locationProvider.updates(PlacePickerViewModel.FIX_INTERVAL_MILLIS).first { update ->
                update is LocationUpdate.Fix ||
                    (update is LocationUpdate.Unavailable && update.reason != LocationUnavailableReason.NO_FIX)
            }
        } ?: LocationUpdate.Unavailable(LocationUnavailableReason.NO_FIX)
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { PrivatePlacesViewModel(container.placeMemory, container.screenLocationProvider) }
        }
    }
}
