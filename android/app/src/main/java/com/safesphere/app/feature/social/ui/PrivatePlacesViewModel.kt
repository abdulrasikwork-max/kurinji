package com.safesphere.app.feature.social.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.location.GeoHash
import com.safesphere.app.core.location.LocationProvider
import com.safesphere.app.core.location.PreciseFix
import com.safesphere.app.core.location.preciseFix
import com.safesphere.app.feature.social.data.PlaceMemory
import com.safesphere.app.feature.social.domain.PrivatePlace
import com.safesphere.app.feature.social.domain.PrivatePlaceKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PrivatePlacesNotice { ADDED, NEEDS_PERMISSION, LOCATION_OFF, LOCATION_UNAVAILABLE, NEEDS_PRECISE, POSITION_ROUGH, NOT_SAVED }

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

    private var markJob: Job? = null

    /**
     * Phase 17D: a private place is the ~150 m block, so it needs a precise position. A short, visible search waits for
     * one instead of giving up on the first rough Wi-Fi / cell reading (found on the A23 indoors); a position rougher
     * than 100 m never marks anything. Approximate-only is its own notice (upgrade), a weak signal another (move, retry).
     */
    fun onMarkHere(kind: PrivatePlaceKind) {
        if (_uiState.value.isBusy) return
        markJob = viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            var notice: PrivatePlacesNotice? = null
            try {
                notice = when (val result = locationProvider.preciseFix()) {
                    PreciseFix.NeedsPermission -> PrivatePlacesNotice.NEEDS_PERMISSION
                    PreciseFix.LocationOff -> PrivatePlacesNotice.LOCATION_OFF
                    PreciseFix.Unavailable -> PrivatePlacesNotice.LOCATION_UNAVAILABLE
                    PreciseFix.NeedsPrecisePermission -> PrivatePlacesNotice.NEEDS_PRECISE
                    PreciseFix.NotFound, is PreciseFix.Rough -> PrivatePlacesNotice.POSITION_ROUGH
                    is PreciseFix.Good -> {
                        val fix = result.fix
                        if (placeMemory.addPrivate(kind, GeoHash.encode(fix.latitude, fix.longitude, GeoHash.BLOCK)))
                            PrivatePlacesNotice.ADDED
                        else PrivatePlacesNotice.NOT_SAVED
                    }
                }
            } finally {
                // Also when the screen was left meanwhile: the busy flag must never stay on.
                withContext(NonCancellable) {
                    refresh()
                    _uiState.update { it.copy(isBusy = false, notice = notice) }
                }
            }
        }
    }

    /** The screen is no longer visible: location is used only while it is open. */
    fun onScreenHidden() {
        markJob?.cancel()
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
