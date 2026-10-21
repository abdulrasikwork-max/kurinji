package com.safesphere.app.feature.safewalk.session

import com.safesphere.app.core.device.BatteryStatus
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.location.LocationFix
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.safewalk.domain.CheckInPhase
import com.safesphere.app.feature.safewalk.domain.CheckInStatus
import com.safesphere.app.feature.safewalk.domain.Concern
import com.safesphere.app.feature.safewalk.domain.Journey
import com.safesphere.app.feature.safewalk.domain.JourneyStatus
import com.safesphere.app.feature.safewalk.domain.MovementState
import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

/** What happened when the destination text was looked up at the start (Phase 4C). */
enum class DestinationSearch {
    /** Not looked up: no destination typed, or movement checks off. */
    NOT_ASKED,
    FOUND,
    NOT_FOUND,

    /** No map service, no network, or too slow. */
    UNAVAILABLE
}

/**
 * The Safe Walk as the screen, Home and the tracking service see it (one holder, several readers).
 *
 * @param localDueAt     next check-in deadline in PHONE time
 * @param endedStatus    set when this walk ended on this phone (the screen shows it once, then acknowledges)
 * @param pendingSync    changes not yet confirmed by the server (check-ins, escalations, location, end)
 * @param restoredOffline restored from the phone's own record because the server could not be reached
 * @param movementChecks stop / route checks switched on for this walk (Phase 4C)
 * @param destinationSearch / [destinationDescription] the start-time lookup result; the description (address
 *        line from the map service) is kept in memory only, so the person can check the right place was found
 * @param movement       the movement checks' state (memory only)
 * @param concern        an open "Are you OK?" prompt raised by a movement check
 */
data class SafeWalkState(
    val journey: Journey? = null,
    val localDueAt: Instant? = null,
    val checkIn: CheckInStatus? = null,
    val endedStatus: JourneyStatus? = null,
    val access: LocationAccess = LocationAccess.NONE,
    val lastFix: LocationFix? = null,
    val lastLocationSavedAt: Instant? = null,
    val pendingSync: Int = 0,
    val isOnline: Boolean = true,
    val restoredOffline: Boolean = false,
    val signedOut: Boolean = false,
    val movementChecks: Boolean = false,
    val destinationSearch: DestinationSearch = DestinationSearch.NOT_ASKED,
    val destinationDescription: String? = null,
    val movement: MovementState = MovementState(),
    val concern: Concern? = null,
    val battery: BatteryStatus = BatteryStatus()
) {
    val isActive: Boolean get() = journey != null && endedStatus == null && !signedOut

    /** Location updates (and therefore the location foreground service) need permission. */
    val isTracking: Boolean get() = isActive && access != LocationAccess.NONE

    val phase: CheckInPhase? get() = if (isActive) checkIn?.phase else null

    /** The arrival hint shows only while nothing else needs an answer. */
    val looksArrived: Boolean
        get() = isActive && movementChecks && movement.arrival.arrived &&
            (phase == CheckInPhase.ON_TRACK || phase == CheckInPhase.DUE_SOON)

    override fun toString(): String =
        "SafeWalkState(journey=${journey?.id}, phase=$phase, ended=$endedStatus, concern=${concern?.kind}, location=<redacted>)"
}

/** What screens need from the Safe Walk holder. Production: [SafeWalkSession]. */
interface SafeWalkController {
    val state: StateFlow<SafeWalkState>

    /**
     * Starts a walk on the server (the server computes the first deadline). With [movementChecks] on and a
     * destination typed, the destination is looked up first so the Safety Bubble can run (Phase 4C).
     */
    suspend fun start(destinationLabel: String?, checkInIntervalMinutes: Int, movementChecks: Boolean = true): ApiResult<Journey>

    /** After a restart: resumes an ACTIVE walk from the server, or from the phone's record when offline. */
    suspend fun recover()

    /** "I'm OK". Works offline: the local timer restarts at once and the server is told when possible. */
    fun checkIn()

    /** "I've arrived". Ends the walk on the phone at once; the server is told when possible. */
    fun complete()

    fun cancel()

    /** Re-checks location permission (after a permission dialog or Settings). */
    fun refreshLocationAccess()

    /** Switches stop / route checks on or off during the walk (e.g. the wrong destination was found). */
    fun setMovementChecks(enabled: Boolean)

    /** An SOS was started: the server already ended the walk as EMERGENCY; stop everything here. */
    fun onEmergencyStarted()

    /** The screen has shown the "walk ended" state. */
    fun acknowledgeEnded()
}

/** Safe Walk notifications. Production: [AndroidSafeWalkAlerts]. Tests: a fake. */
interface SafeWalkAlerts {
    /** The check-in / "Are you OK?" alert. [status] says the phase and, for an early prompt, the concern. */
    fun show(status: CheckInStatus)
    fun clear()

    /** One warning when the battery becomes critical during a walk (Phase 4C). */
    fun showBatteryCritical()
    fun clearBatteryCritical()
}
