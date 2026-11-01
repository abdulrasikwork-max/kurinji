package com.safesphere.app.feature.safewalk.ui

import androidx.annotation.StringRes
import com.safesphere.app.R
import com.safesphere.app.core.device.BatteryLevel
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.location.LocationNeed
import com.safesphere.app.core.location.LocationNeeds
import com.safesphere.app.core.location.LocationStreamStatus
import com.safesphere.app.feature.safewalk.domain.GpsQuality
import com.safesphere.app.feature.safewalk.session.DestinationSearch
import com.safesphere.app.feature.safewalk.session.SafeWalkState
import kotlin.math.roundToInt

/** Honest explanations shown under an active walk. Pure mapping, unit-tested. */
enum class WalkHint(@StringRes val text: Int) {
    LOCATION_NOT_SHARED(R.string.safe_walk_hint_no_location),
    KEEP_APP_OPEN(R.string.safe_walk_hint_keep_open),
    NOTIFICATIONS_OFF(R.string.safe_walk_hint_notifications_off),
    OFFLINE_PENDING(R.string.safe_walk_hint_offline),
    RESTORED_OFFLINE(R.string.safe_walk_hint_restored_offline),

    /** Phase 17D: the phone's location switch is off during the walk. */
    LOCATION_OFF(R.string.safe_walk_hint_location_off),

    /** Phase 17D: location is on but no position for a while (indoors, weak signal). */
    NO_SIGNAL(R.string.safe_walk_hint_no_signal),
    LOCATION_UNSUPPORTED(R.string.safe_walk_hint_location_unsupported)
}

fun walkHintsFor(state: SafeWalkState, serviceRunning: Boolean, notificationsGranted: Boolean): List<WalkHint> = buildList {
    if (!state.isActive) return@buildList
    if (state.access == LocationAccess.NONE) add(WalkHint.LOCATION_NOT_SHARED)
    // Phase 17D: why no position arrives, said once, in the same words as the SOS screen. Check-ins are unaffected.
    if (state.access != LocationAccess.NONE) when (state.locationStatus) {
        LocationStreamStatus.LOCATION_OFF -> add(WalkHint.LOCATION_OFF)
        LocationStreamStatus.NO_SIGNAL -> add(WalkHint.NO_SIGNAL)
        LocationStreamStatus.SERVICES_UNAVAILABLE -> add(WalkHint.LOCATION_UNSUPPORTED)
        else -> Unit
    }
    // Without the foreground service (no location permission, or Android refused) the timer only runs
    // while the app process is alive.
    if (!serviceRunning) add(WalkHint.KEEP_APP_OPEN)
    if (!notificationsGranted) add(WalkHint.NOTIFICATIONS_OFF)
    if (state.pendingSync > 0 && !state.isOnline) add(WalkHint.OFFLINE_PENDING)
    if (state.restoredOffline) add(WalkHint.RESTORED_OFFLINE)
}

/**
 * Phase 17D: the one location action to offer on the Safe Walk screen (pure). Check-ins never need location, so this is
 * only about the optional parts: sharing the last position and the stop / route checks (which need precise location).
 * [preciseDeclined] / [permanentlyDenied] come from [com.safesphere.app.core.ui.LocationAsk].
 */
fun walkLocationNeed(
    state: SafeWalkState,
    movementChecksWanted: Boolean = state.movementChecks,
    permanentlyDenied: Boolean = false,
    preciseDeclined: Boolean = false
): LocationNeed = LocationNeeds.of(
    access = state.access,
    locationEnabled = state.locationStatus != LocationStreamStatus.LOCATION_OFF,
    wantsPrecise = movementChecksWanted,
    permanentlyDenied = permanentlyDenied,
    preciseDeclined = preciseDeclined
)

/** Phase 17D: what the set-up panel says about location before "Start" (facts, never a verdict). */
enum class SetupLocation { PRECISE, APPROXIMATE, OFF, NOT_ALLOWED }

fun setupLocationFor(access: LocationAccess, locationEnabled: Boolean): SetupLocation = when {
    access == LocationAccess.NONE -> SetupLocation.NOT_ALLOWED
    !locationEnabled -> SetupLocation.OFF
    access == LocationAccess.APPROXIMATE -> SetupLocation.APPROXIMATE
    else -> SetupLocation.PRECISE
}

/** A distance for the screen: metres (rounded to 10) below 1 km, else kilometres with one decimal. */
data class DistanceText(val kilometres: Boolean, val value: Double)

fun distanceText(meters: Double): DistanceText =
    if (meters < 1_000) DistanceText(false, ((meters / 10).roundToInt() * 10).toDouble())
    else DistanceText(true, (meters / 100).roundToInt() / 10.0)

/** What the stop / route checks (Phase 4C) and the battery policy are doing, said plainly. Pure, unit-tested. */
sealed interface MovementLine {
    data object Off : MovementLine
    data object NeedsLocation : MovementLine
    data object WaitingForGps : MovementLine
    data object WeakGps : MovementLine

    /** Phase 17D: approximate permission only: every position is too rough for the checks, so they are paused. */
    data object NeedsPreciseLocation : MovementLine

    /** Phase 17D: the phone's location switch is off: the checks are paused, check-ins go on. */
    data object PausedLocationOff : MovementLine
    /** The address line the map service found, so the person can see whether it is the right place. */
    data class Destination(val description: String) : MovementLine
    data class RouteOn(val distance: DistanceText?) : MovementLine
    data object StopsOnly : MovementLine
    data object DestinationNotFound : MovementLine
    data object LookupUnavailable : MovementLine
    data object RouteResumesOnline : MovementLine
    data class BatteryLow(val percent: Int?) : MovementLine
    data class BatteryCritical(val percent: Int?) : MovementLine
}

fun movementLinesFor(state: SafeWalkState, gps: GpsQuality): List<MovementLine> = buildList {
    if (!state.isActive) return@buildList
    when {
        !state.movementChecks -> add(MovementLine.Off)
        state.access == LocationAccess.NONE -> add(MovementLine.NeedsLocation)
        else -> {
            when {
                state.journey?.destination != null -> {
                    state.destinationDescription?.let { add(MovementLine.Destination(it)) }
                    add(MovementLine.RouteOn(state.movement.metersToDestination?.let(::distanceText)))
                }
                state.destinationSearch == DestinationSearch.NOT_FOUND -> add(MovementLine.DestinationNotFound)
                state.destinationSearch == DestinationSearch.UNAVAILABLE -> add(MovementLine.LookupUnavailable)
                state.restoredOffline -> add(MovementLine.RouteResumesOnline)
                else -> add(MovementLine.StopsOnly)
            }
            // Never guess from a bad signal: say the checks are paused instead (and say WHY when the reason is known).
            when {
                // Phase 17D: with approximate permission every fix is rougher than the checks accept, forever.
                state.access == LocationAccess.APPROXIMATE -> add(MovementLine.NeedsPreciseLocation)
                state.locationStatus == LocationStreamStatus.LOCATION_OFF -> add(MovementLine.PausedLocationOff)
                else -> when (gps) {
                    GpsQuality.WAITING -> add(MovementLine.WaitingForGps)
                    GpsQuality.WEAK -> add(MovementLine.WeakGps)
                    GpsQuality.GOOD -> Unit
                }
            }
        }
    }
    when (state.battery.level) {
        BatteryLevel.LOW -> add(MovementLine.BatteryLow(state.battery.percent))
        BatteryLevel.CRITICAL -> add(MovementLine.BatteryCritical(state.battery.percent))
        BatteryLevel.NORMAL, BatteryLevel.UNKNOWN -> Unit
    }
}

/** "07:42" for the countdown; negative durations (overdue) show how late, e.g. "+01:05". */
fun formatCountdown(totalSeconds: Long): String {
    val overdue = totalSeconds < 0
    val seconds = kotlin.math.abs(totalSeconds)
    val text = "%02d:%02d".format(seconds / 60, seconds % 60)
    return if (overdue) "+$text" else text
}
