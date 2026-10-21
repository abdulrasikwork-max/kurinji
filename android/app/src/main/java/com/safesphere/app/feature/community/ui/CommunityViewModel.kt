package com.safesphere.app.feature.community.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.location.GeoHash
import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.location.LocationFix
import com.safesphere.app.core.location.LocationProvider
import com.safesphere.app.core.location.LocationUnavailableReason
import com.safesphere.app.core.location.LocationUpdate
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.community.domain.AreaSignal
import com.safesphere.app.feature.community.domain.CommunityOutbox
import com.safesphere.app.feature.community.domain.CommunityPolicy
import com.safesphere.app.feature.community.domain.CommunityPolicy.AreaBrief
import com.safesphere.app.feature.community.domain.CommunityPolicy.PlacedSignal
import com.safesphere.app.feature.community.domain.CommunityPolicy.ReportReadiness
import com.safesphere.app.feature.community.domain.CommunityRepository
import com.safesphere.app.feature.community.domain.CommunityStanding
import com.safesphere.app.feature.community.domain.ContributionKind
import com.safesphere.app.feature.community.domain.FlagReason
import com.safesphere.app.feature.community.domain.MyContribution
import com.safesphere.app.feature.community.domain.PendingReport
import com.safesphere.app.feature.community.domain.SignalCategory
import com.safesphere.app.feature.intelligence.domain.AreaPicture
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the area part of the Community screen shows. */
sealed interface AreaState {
    /** No location permission: explain why and offer to ask (never asked at launch). */
    data object NeedsPermission : AreaState
    data object Locating : AreaState
    data class LocationProblem(val reason: LocationUnavailableReason) : AreaState
    data object Loading : AreaState

    /**
     * [origin] stays in memory only, to draw the radar; it is never stored or sent. [picture] (Phase 8) is the area
     * picture for the same answer, in memory only (null = the server sent none: the card is simply not shown).
     */
    data class Ready(
        val placed: List<PlacedSignal>,
        val brief: AreaBrief,
        val origin: GeoPoint,
        val approximateLocation: Boolean,
        val picture: AreaPicture? = null
    ) : AreaState

    data class Failed(val failure: ApiResult.Failure) : AreaState
}

/** One-time messages (shown once, then cleared). */
sealed interface CommunityEvent {
    data class Recorded(val appearsWithinMinutes: Int) : CommunityEvent
    data object SavedOffline : CommunityEvent
    data class OutboxSent(val count: Int) : CommunityEvent
    data object Withdrawn : CommunityEvent
    data class ReportNotReady(val readiness: ReportReadiness) : CommunityEvent
    data class NoLocation(val reason: LocationUnavailableReason) : CommunityEvent
    data class Failed(val failure: ApiResult.Failure) : CommunityEvent
    /** Phase 7: the flag was received (the same answer whatever it caused). */
    data object Flagged : CommunityEvent
    data object AppealSent : CommunityEvent
    /** Phase 7: a moderator paused this person's contributions; looking still works. */
    data object ContributionsPaused : CommunityEvent
}

data class CommunityUiState(
    val area: AreaState = AreaState.Locating,
    val mine: List<MyContribution> = emptyList(),
    val pendingReports: Int = 0,
    val busy: Boolean = false,
    val event: CommunityEvent? = null,
    val sessionExpired: Boolean = false,
    val standing: CommunityStanding = CommunityStanding.ACTIVE
)

/**
 * Community Safety (Phase 6). Gets ONE fresh fix when the screen opens (and before each report or answer), turns it
 * into cells on the phone, and talks to the server in cells only. Location is used only while the screen is open;
 * nothing here runs in the background.
 */
class CommunityViewModel(
    private val repository: CommunityRepository,
    private val outbox: CommunityOutbox,
    private val locationProvider: LocationProvider,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : ViewModel() {

    private val _ui = MutableStateFlow(CommunityUiState())
    val ui: StateFlow<CommunityUiState> = _ui.asStateFlow()

    private var refreshJob: Job? = null

    /** Called from onStart: the permission may have changed in Settings. */
    fun onScreenVisible() {
        if (locationProvider.access() == LocationAccess.NONE) {
            refreshJob?.cancel()
            _ui.update { it.copy(area = AreaState.NeedsPermission) }
            loadMineAndOutbox()
        } else {
            refresh()
        }
    }

    fun onPermissionResult() = onScreenVisible()

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _ui.update { it.copy(area = AreaState.Locating) }
            val fix = when (val update = awaitFix()) {
                is LocationUpdate.Fix -> update.fix
                is LocationUpdate.Unavailable -> {
                    _ui.update { it.copy(area = locationState(update.reason)) }
                    loadMineAndOutbox()
                    return@launch
                }
            }
            _ui.update { it.copy(area = AreaState.Loading) }
            flushOutbox()
            val origin = GeoPoint.of(fix)
            when (val result = repository.area(GeoHash.encode(origin, GeoHash.AREA))) {
                is ApiResult.Success -> {
                    val placed = CommunityPolicy.place(result.data.signals, origin)
                    _ui.update {
                        it.copy(area = AreaState.Ready(placed, CommunityPolicy.brief(placed), origin,
                            approximateLocation = locationProvider.access() != LocationAccess.PRECISE,
                            picture = result.data.picture))
                    }
                }
                is ApiResult.Failure -> _ui.update {
                    it.copy(area = AreaState.Failed(result), sessionExpired = it.sessionExpired || result.isSessionExpired())
                }
            }
            loadMine()
        }
    }

    /** A new report "here". Needs a fresh, precise fix; without a connection it waits in the outbox. */
    fun onReport(category: SignalCategory) = runBusy {
        if (paused()) return@runBusy
        val fix = freshFixOrEvent() ?: return@runBusy
        val readiness = CommunityPolicy.reportReadiness(locationProvider.access(), fix.accuracyMeters)
        if (readiness != ReportReadiness.READY) {
            emit(CommunityEvent.ReportNotReady(readiness))
            return@runBusy
        }
        val cell = GeoHash.encode(fix.latitude, fix.longitude, GeoHash.BLOCK)
        when (val result = repository.report(category, cell)) {
            is ApiResult.Success -> {
                emit(CommunityEvent.Recorded(result.data.appearsWithinMinutes))
                refresh() // area + "your reports" (the area shows it only once its delay is over)
            }
            is ApiResult.Failure -> if (result.error == ApiError.NO_CONNECTION) {
                val updated = CommunityPolicy.addToOutbox(outbox.load(), PendingReport(category, cell, nowMillis()))
                outbox.save(updated)
                _ui.update { it.copy(pendingReports = updated.size) }
                emit(CommunityEvent.SavedOffline)
            } else {
                fail(result)
            }
        }
    }

    /** "I saw this too" / "No longer true". The server checks the person's current cell is near the signal. */
    fun onAnswer(signal: AreaSignal, kind: ContributionKind) = runBusy {
        if (!CommunityPolicy.canAnswer(signal) || kind == ContributionKind.REPORT) return@runBusy
        if (paused()) return@runBusy
        val fix = freshFixOrEvent() ?: return@runBusy
        val near = GeoHash.encode(fix.latitude, fix.longitude, GeoHash.BLOCK)
        when (val result = repository.contribute(signal.id, kind, near)) {
            is ApiResult.Success -> {
                emit(CommunityEvent.Recorded(result.data.appearsWithinMinutes))
                refresh()
            }
            is ApiResult.Failure -> fail(result)
        }
    }

    /** Called only after the person confirmed the dialog. */
    fun onWithdrawConfirmed(contribution: MyContribution) = runBusy {
        when (val result = repository.withdraw(contribution.signalId)) {
            is ApiResult.Success -> {
                emit(CommunityEvent.Withdrawn)
                refresh()
            }
            is ApiResult.Failure -> fail(result)
        }
    }

    /**
     * Phase 7: "something is wrong with this signal". Like an answer, it needs a fresh fix: the server only accepts
     * flags from people near the signal (checked against the person's current cell, which is never stored).
     */
    fun onFlag(signal: AreaSignal, reason: FlagReason) = runBusy {
        if (!CommunityPolicy.canFlag(signal)) return@runBusy
        if (paused()) return@runBusy
        val fix = freshFixOrEvent() ?: return@runBusy
        val near = GeoHash.encode(fix.latitude, fix.longitude, GeoHash.BLOCK)
        when (val result = repository.flag(signal.id, reason, near)) {
            is ApiResult.Success -> {
                emit(CommunityEvent.Flagged)
                refresh()
            }
            is ApiResult.Failure -> fail(result)
        }
    }

    /** Phase 7: asks a different moderator to look at the restriction again. */
    fun onAppeal() = runBusy {
        if (!_ui.value.standing.canAppeal) return@runBusy
        when (val result = repository.appeal()) {
            is ApiResult.Success -> {
                emit(CommunityEvent.AppealSent)
                loadStanding()
            }
            is ApiResult.Failure -> fail(result)
        }
    }

    fun onEventShown() = _ui.update { it.copy(event = null) }

    fun onSessionExpiredHandled() = _ui.update { it.copy(sessionExpired = false) }

    // ---- Helpers ------------------------------------------------------------------------------------------------

    /** Sends waiting reports. Refused ones (limits, invalid) are dropped: retrying cannot fix them. */
    private suspend fun flushOutbox() {
        val waiting = CommunityPolicy.sendable(outbox.load(), nowMillis())
        val remaining = mutableListOf<PendingReport>()
        var sent = 0
        var offline = false
        for (report in waiting) {
            if (offline) {
                remaining += report
                continue
            }
            when (val result = repository.report(report.category, report.blockCell)) {
                is ApiResult.Success -> sent++
                is ApiResult.Failure -> when {
                    result.error == ApiError.NO_CONNECTION -> {
                        offline = true
                        remaining += report
                    }
                    result.isSessionExpired() -> {
                        _ui.update { it.copy(sessionExpired = true) }
                        return
                    }
                    else -> Unit // dropped
                }
            }
        }
        outbox.save(remaining)
        _ui.update { it.copy(pendingReports = remaining.size) }
        if (sent > 0) emit(CommunityEvent.OutboxSent(sent))
    }

    private fun loadMineAndOutbox() {
        viewModelScope.launch {
            val count = CommunityPolicy.sendable(outbox.load(), nowMillis()).size
            _ui.update { it.copy(pendingReports = count) }
            loadMine()
        }
    }

    private suspend fun loadMine() {
        when (val result = repository.mine()) {
            is ApiResult.Success -> _ui.update { it.copy(mine = result.data) }
            is ApiResult.Failure -> if (result.isSessionExpired()) _ui.update { it.copy(sessionExpired = true) }
        }
        loadStanding()
    }

    /** Phase 7. On failure the last known standing stays; the server decides on every write anyway. */
    private suspend fun loadStanding() {
        when (val result = repository.standing()) {
            is ApiResult.Success -> _ui.update { it.copy(standing = result.data) }
            is ApiResult.Failure -> if (result.isSessionExpired()) _ui.update { it.copy(sessionExpired = true) }
        }
    }

    /** True (and says so) while a moderator has paused the person's contributions. */
    private fun paused(): Boolean {
        if (!_ui.value.standing.contributionsPaused) return false
        emit(CommunityEvent.ContributionsPaused)
        return true
    }

    /** The first usable answer from the provider, or "no fix" after [FIX_TIMEOUT_MILLIS]. */
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

    private suspend fun freshFixOrEvent(): LocationFix? =
        when (val update = awaitFix()) {
            is LocationUpdate.Fix -> update.fix
            is LocationUpdate.Unavailable -> {
                emit(CommunityEvent.NoLocation(update.reason))
                null
            }
        }

    private fun locationState(reason: LocationUnavailableReason): AreaState =
        if (reason == LocationUnavailableReason.PERMISSION_DENIED) AreaState.NeedsPermission
        else AreaState.LocationProblem(reason)

    private fun runBusy(block: suspend () -> Unit) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            try {
                block()
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    private fun emit(event: CommunityEvent) = _ui.update { it.copy(event = event) }

    private fun fail(failure: ApiResult.Failure) {
        _ui.update { it.copy(event = CommunityEvent.Failed(failure), sessionExpired = it.sessionExpired || failure.isSessionExpired()) }
        // 403 = paused by a moderator since the screen last asked: show the banner (and the appeal) at once.
        if (failure.error == ApiError.FORBIDDEN) viewModelScope.launch { loadStanding() }
    }

    companion object {
        const val FIX_TIMEOUT_MILLIS = 20_000L
        private const val FIX_INTERVAL_MILLIS = 2_000L

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                CommunityViewModel(container.communityRepository, container.communityOutbox, container.screenLocationProvider)
            }
        }
    }
}
