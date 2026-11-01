package com.safesphere.app.feature.intelligence.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.location.DestinationLookup
import com.safesphere.app.core.location.DestinationResolver
import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.core.location.LocationProvider
import com.safesphere.app.core.location.LocationUnavailableReason
import com.safesphere.app.core.location.OneFix
import com.safesphere.app.core.location.OneFixes
import com.safesphere.app.core.location.oneFix
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.intelligence.domain.CorridorBriefPolicy
import com.safesphere.app.feature.intelligence.domain.CorridorBriefPolicy.CorridorBrief
import com.safesphere.app.feature.intelligence.domain.IntelligencePolicy
import com.safesphere.app.feature.intelligence.domain.IntelligenceRepository
import com.safesphere.app.feature.intelligence.domain.RegionPack
import com.safesphere.app.feature.safewalk.domain.MovementPolicy
import java.time.LocalTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the "Check the way" part of the Safe Walk set-up screen shows. */
sealed interface WayCheckState {
    data object Idle : WayCheckState
    data object Checking : WayCheckState
    /** [destinationDescription] = what the map service found, so the person can see it is the right place. */
    data class Ready(val brief: CorridorBrief, val destinationDescription: String?) : WayCheckState
    data object NeedsDestination : WayCheckState
    data object DestinationNotFound : WayCheckState
    data object DestinationUnavailable : WayCheckState
    data class NoLocation(val reason: LocationUnavailableReason) : WayCheckState
    data object TooLong : WayCheckState
    data object Incomplete : WayCheckState
    data class Failed(val failure: ApiResult.Failure) : WayCheckState
}

/**
 * "Check the way" (Phase 8D). Only when the person taps it: one fix, the destination looked up on the phone, the
 * ~5 km regions around the straight line downloaded, and the brief computed HERE. Only region cells are sent.
 *
 * Deliberately separate from SafeWalkViewModel and the walk itself: nothing here can start, delay, change or end a
 * walk, its check-ins, its movement checks or any alert (IntelligenceIsolationTest). Everything stays in memory.
 */
class WayCheckViewModel(
    private val locationProvider: LocationProvider,
    private val resolver: DestinationResolver,
    private val repository: IntelligenceRepository,
    private val movementPolicy: MovementPolicy = MovementPolicy(),
    private val localHour: () -> Int = { LocalTime.now().hour }
) : ViewModel() {

    private val _state = MutableStateFlow<WayCheckState>(WayCheckState.Idle)
    val state: StateFlow<WayCheckState> = _state.asStateFlow()

    private var job: Job? = null

    fun onCheck(destinationLabel: String?) {
        val label = destinationLabel?.trim().orEmpty()
        if (label.isEmpty()) {
            _state.value = WayCheckState.NeedsDestination
            return
        }
        if (_state.value == WayCheckState.Checking) return
        job?.cancel()
        _state.value = WayCheckState.Checking
        job = viewModelScope.launch { _state.value = check(label) }
    }

    /** The destination text changed: an old brief would describe another way. */
    fun onDestinationChanged() {
        job?.cancel()
        _state.value = WayCheckState.Idle
    }

    private suspend fun check(label: String): WayCheckState {
        val start = when (val got = awaitFix()) {
            is OneFix.Got -> GeoPoint.of(got.fix)
            OneFix.NeedsPermission -> return WayCheckState.NoLocation(LocationUnavailableReason.PERMISSION_DENIED)
            OneFix.LocationOff -> return WayCheckState.NoLocation(LocationUnavailableReason.LOCATION_DISABLED)
            OneFix.NotFound -> return WayCheckState.NoLocation(LocationUnavailableReason.NO_FIX)
            OneFix.Unavailable -> return WayCheckState.NoLocation(LocationUnavailableReason.SERVICES_UNAVAILABLE)
        }
        val found = when (val lookup = resolver.resolve(label, start)) {
            is DestinationLookup.Found -> lookup
            DestinationLookup.NotFound -> return WayCheckState.DestinationNotFound
            DestinationLookup.Unavailable -> return WayCheckState.DestinationUnavailable
        }
        val corridor = CorridorBriefPolicy.corridor(start, found.point,
            movementPolicy.corridorHalfWidthMeters(start, found.point))
        val regions = when (val plan = CorridorBriefPolicy.regionsFor(corridor)) {
            is CorridorBriefPolicy.Plan.Regions -> plan.cells
            CorridorBriefPolicy.Plan.TooLong -> return WayCheckState.TooLong
        }
        val packs = mutableListOf<RegionPack>()
        for (region in regions) {
            when (val result = repository.region(region)) {
                is ApiResult.Success -> packs += result.data
                is ApiResult.Failure -> return WayCheckState.Failed(result)
            }
        }
        return when (val brief = CorridorBriefPolicy.brief(corridor, packs, IntelligencePolicy.isNight(localHour()))) {
            is CorridorBriefPolicy.Result.Ready -> WayCheckState.Ready(brief.brief, found.description)
            CorridorBriefPolicy.Result.Incomplete -> WayCheckState.Incomplete
        }
    }

    /**
     * Phase 17D: switched off / not allowed is known at once; a recent position is used as it is. The way is a ~500 m
     * wide strip, so when GPS has nothing in time (indoors, where people usually set a walk up) a Wi-Fi / cell position
     * is still a fair start for the brief.
     */
    private suspend fun awaitFix(): OneFix {
        val precise = locationProvider.oneFix(RECENT_MAX_AGE_MILLIS, FIX_TIMEOUT_MILLIS)
        if (precise != OneFix.NotFound) return precise
        return locationProvider.oneFix(OneFixes.AREA_MAX_AGE_MILLIS, FALLBACK_TIMEOUT_MILLIS, areaOnly = true)
    }

    companion object {
        const val FIX_TIMEOUT_MILLIS = OneFixes.TIMEOUT_MILLIS
        const val FALLBACK_TIMEOUT_MILLIS = 10_000L
        private const val RECENT_MAX_AGE_MILLIS = 60_000L

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                WayCheckViewModel(container.screenLocationProvider, container.destinationResolver,
                    container.intelligenceRepository)
            }
        }
    }
}
