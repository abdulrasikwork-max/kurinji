package com.safesphere.app.feature.social.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.location.GeoHash
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.location.LocationProvider
import com.safesphere.app.core.location.OneFix
import com.safesphere.app.core.location.oneFix
import com.safesphere.app.feature.social.data.PlaceMemory
import com.safesphere.app.feature.social.domain.PrivatePlace
import com.safesphere.app.feature.social.domain.PrivatePlaceKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PrivatePlacesNotice { ADDED, NEEDS_PERMISSION, LOCATION_OFF, LOCATION_UNAVAILABLE, NEEDS_PRECISE, NOT_SAVED }

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
            val notice = when (val result = locationProvider.oneFix(PlacePickerViewModel.FRESH_FIX_MAX_AGE_MILLIS,
                PlacePickerViewModel.FIX_TIMEOUT_MILLIS)) {
                OneFix.NeedsPermission -> PrivatePlacesNotice.NEEDS_PERMISSION
                OneFix.LocationOff -> PrivatePlacesNotice.LOCATION_OFF
                OneFix.NotFound, OneFix.Unavailable -> PrivatePlacesNotice.LOCATION_UNAVAILABLE
                is OneFix.Got -> {
                    val fix = result.fix
                    val good = result.access == LocationAccess.PRECISE &&
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


    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { PrivatePlacesViewModel(container.placeMemory, container.screenLocationProvider) }
        }
    }
}
