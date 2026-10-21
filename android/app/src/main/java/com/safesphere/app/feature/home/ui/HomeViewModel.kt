package com.safesphere.app.feature.home.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.feature.account.domain.AccountPolicy
import com.safesphere.app.feature.account.domain.DeletionNotice
import java.time.Instant
import com.safesphere.app.feature.auth.domain.AuthError
import com.safesphere.app.feature.auth.domain.AuthRepository
import com.safesphere.app.feature.auth.domain.AuthResult
import com.safesphere.app.feature.emergency.domain.ActiveEmergencyStore
import com.safesphere.app.feature.emergency.location.LocationSharingController
import com.safesphere.app.feature.emergency.location.LocationSharingState
import com.safesphere.app.feature.safewalk.domain.CheckInPhase
import com.safesphere.app.feature.safewalk.session.SafeWalkController
import com.safesphere.app.feature.safewalk.session.SafeWalkState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** An emergency or Safe Walk that is still running, so Home can lead the person back to it. */
sealed interface ActiveSessionBanner {
    data object Emergency : ActiveSessionBanner
    data class SafeWalk(val phase: CheckInPhase?) : ActiveSessionBanner {
        /** The "Are you OK?" prompt is open (Level 1) or was not answered (Level 2). */
        val overdue: Boolean get() = phase == CheckInPhase.MISSED || phase == CheckInPhase.NO_RESPONSE
    }
}

/**
 * What the Home status card states. Only facts this phone knows; never a judgement that a place or a person is
 * safe (docs/PHASE8_DESIGN.md §6.4). The words are in [HomeStatusCard].
 */
enum class HomeStatus {
    /** The phone has not finished looking for an SOS or a walk yet, so it cannot say "nothing is running". */
    CHECKING,
    NOTHING_RUNNING,
    SAFE_WALK,

    /** A check-in was missed or not answered: amber, with a warning icon. */
    CHECK_IN_MISSED,

    /** An SOS is active: the card is hidden and the red banner is the one emergency element. */
    EMERGENCY;

    companion object {
        /** A running SOS or walk is shown as soon as it is known; "nothing" only once the phone has [checked]. */
        fun from(banner: ActiveSessionBanner?, checked: Boolean): HomeStatus = when (banner) {
            ActiveSessionBanner.Emergency -> EMERGENCY
            is ActiveSessionBanner.SafeWalk -> if (banner.overdue) CHECK_IN_MISSED else SAFE_WALK
            null -> if (checked) NOTHING_RUNNING else CHECKING
        }
    }
}

class HomeViewModel(
    private val authRepository: AuthRepository,
    private val safeWalk: SafeWalkController,
    locationSharing: LocationSharingController,
    private val activeEmergencyStore: ActiveEmergencyStore
) : ViewModel() {

    /** The name to greet, or null when only a generic greeting can be shown. */
    val displayName: Flow<String?> = authRepository.currentUser.map { it?.name }

    /**
     * Phase 10A.3: a pending account deletion (null = none). Shown on Home for the whole grace period, so a request
     * made by someone else who knows the password, or one the person was forced to make, cannot go unnoticed.
     */
    val deletion: Flow<DeletionNotice?> = authRepository.currentUser.map { AccountPolicy.deletionNotice(it, Instant.now()) }

    private val _sessionExpired = MutableStateFlow(false)

    /** True when the server rejected the saved token, so the person must log in again. */
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    /** An emergency remembered on this phone (for example after the app was killed during one); null = not read yet. */
    private val storedEmergency = MutableStateFlow<Boolean?>(null)

    /** True once the walk recovery (phone record + server) has finished. */
    private val walkRecovered = MutableStateFlow(false)

    /**
     * Phase 4B: emergency first (it matters most), then an active walk. After a restart, location saving
     * resumes only when the SOS / Safe Walk screen is opened (the foreground service must be started from
     * a visible screen), so Home shows this banner instead of silently forgetting the session.
     */
    val activeSession: StateFlow<ActiveSessionBanner?> =
        combine(locationSharing.state, safeWalk.state, storedEmergency) { sharing, walk, stored ->
            bannerFor(sharing, walk, stored == true)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The status card: the same sources and the same rule ([bannerFor]) as [activeSession], plus "has the phone looked yet". */
    val status: StateFlow<HomeStatus> =
        combine(locationSharing.state, safeWalk.state, storedEmergency, walkRecovered) { sharing, walk, stored, recovered ->
            HomeStatus.from(bannerFor(sharing, walk, stored == true), checked = stored != null && recovered)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeStatus.CHECKING)

    init {
        refreshProfile()
        viewModelScope.launch {
            safeWalk.recover()
            walkRecovered.value = true
        }
        onScreenVisible()
    }

    private fun bannerFor(sharing: LocationSharingState, walk: SafeWalkState, stored: Boolean): ActiveSessionBanner? =
        when {
            (sharing.emergencyId != null && !sharing.signedOut) || stored -> ActiveSessionBanner.Emergency
            walk.isActive -> ActiveSessionBanner.SafeWalk(walk.phase)
            else -> null
        }

    /** Re-read the phone's emergency record (the SOS screen clears it once the emergency has ended). */
    fun onScreenVisible() {
        viewModelScope.launch {
            val userId = authRepository.currentUser.first()?.id
            val record = activeEmergencyStore.load()
            storedEmergency.value = record != null && record.userId == userId
        }
    }

    /**
     * Asks the backend (GET /api/users/me) who we are, which also proves the saved token is still valid.
     * If the network is down we keep the cached name and say nothing: being offline is not an error here.
     */
    private fun refreshProfile() {
        viewModelScope.launch {
            val result = authRepository.refreshProfile()
            if (result is AuthResult.Failure && result.error == AuthError.UNAUTHORIZED) {
                _sessionExpired.value = true
            }
        }
    }

    fun onSessionExpiredHandled() {
        _sessionExpired.value = false
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                HomeViewModel(container.authRepository, container.safeWalk, container.locationSharing, container.activeEmergencyStore)
            }
        }
    }
}
