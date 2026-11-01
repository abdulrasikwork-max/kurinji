package com.safesphere.app.core.location

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phase 17D: the answer to "where exactly am I, right now?" for the few things that need the ~150 m block: a Nearby
 * report, answering a block-precision signal, adding a place, marking a private place.
 *
 * Unlike [oneFix] (one read, never better), this keeps listening for a short window and returns as soon as a position
 * is good enough, so a first rough Wi-Fi / cell position does not end the attempt (Phase 6 R4: "too rough" with no way
 * forward). It never lowers the bar: a position that stayed rougher than the target comes back as [Rough] and is NEVER
 * used to place anything; the person is told to move outside or wait for a better signal and tries again.
 */
sealed interface PreciseFix {
    /** Accuracy known and within the target. */
    data class Good(val fix: LocationFix) : PreciseFix

    /** The best position seen in the window was rougher than the target (or its accuracy was not stated). */
    data class Rough(val fix: LocationFix) : PreciseFix

    /** No location permission at all: ask for it. */
    data object NeedsPermission : PreciseFix

    /** Only an approximate permission: it can never reach the target; offer the upgrade to precise. */
    data object NeedsPrecisePermission : PreciseFix

    /** The phone's location setting is off. */
    data object LocationOff : PreciseFix

    /** Nothing came in the window (indoors, no signal). */
    data object NotFound : PreciseFix

    /** Neither Google Play services nor the platform can give a position. */
    data object Unavailable : PreciseFix
}

object PreciseFixes {
    /** The report / place block is ~150 m; a fix must be at least this good (Phase 6). */
    const val TARGET_METERS = 100f

    /** How long a search for a good position goes on before the person is asked to move. */
    const val WINDOW_MILLIS = 25_000L

    const val INTERVAL_MILLIS = 1_000L
}

/**
 * Listens to [LocationProvider.freshUpdates] for up to [windowMillis]. [onProgress] is told the best accuracy seen so
 * far (metres, null until one position with a stated accuracy arrived), so the screen can say "about 180 m, looking for
 * a better signal". Cancelling the caller stops the location request at once.
 */
suspend fun LocationProvider.preciseFix(
    targetMeters: Float = PreciseFixes.TARGET_METERS,
    windowMillis: Long = PreciseFixes.WINDOW_MILLIS,
    onProgress: (Float?) -> Unit = {}
): PreciseFix {
    when (access()) {
        LocationAccess.NONE -> return PreciseFix.NeedsPermission
        LocationAccess.APPROXIMATE -> return PreciseFix.NeedsPrecisePermission
        LocationAccess.PRECISE -> Unit
    }
    if (!isLocationEnabled()) return PreciseFix.LocationOff

    var best: LocationFix? = null
    var problem: LocationUnavailableReason? = null
    val decisive = withTimeoutOrNull(windowMillis) {
        freshUpdates(PreciseFixes.INTERVAL_MILLIS).first { update ->
            when (update) {
                is LocationUpdate.Fix -> {
                    val fix = update.fix
                    if (isBetter(fix, best)) {
                        best = fix
                        onProgress(fix.accuracyMeters?.takeIf { !it.isNaN() && it >= 0f })
                    }
                    fix.accuracyMeters.isWithin(targetMeters)
                }
                is LocationUpdate.Unavailable -> {
                    // "No position right now" is routine while GPS warms up: keep waiting. Anything else is lasting.
                    if (update.reason == LocationUnavailableReason.NO_FIX) false
                    else {
                        problem = update.reason
                        true
                    }
                }
            }
        }
    }
    problem?.let { reason ->
        return when (reason) {
            LocationUnavailableReason.PERMISSION_DENIED -> PreciseFix.NeedsPermission
            LocationUnavailableReason.LOCATION_DISABLED -> PreciseFix.LocationOff
            LocationUnavailableReason.SERVICES_UNAVAILABLE -> PreciseFix.Unavailable
            LocationUnavailableReason.NO_FIX -> PreciseFix.NotFound
        }
    }
    if (decisive is LocationUpdate.Fix) return PreciseFix.Good(decisive.fix)
    return best?.let { PreciseFix.Rough(it) } ?: PreciseFix.NotFound
}

private fun Float?.isWithin(target: Float): Boolean = this != null && !isNaN() && this >= 0f && this <= target

/** A position with a stated accuracy beats one without; a smaller number beats a bigger one. */
private fun isBetter(candidate: LocationFix, current: LocationFix?): Boolean {
    if (current == null) return true
    val a = candidate.accuracyMeters?.takeIf { !it.isNaN() && it >= 0f } ?: return false
    val b = current.accuracyMeters?.takeIf { !it.isNaN() && it >= 0f } ?: return true
    return a < b
}

/**
 * Phase 17D: what a CONTINUOUS location stream (Safe Walk) is doing, in the same words as the SOS screen (17A): a
 * short "no position right now" is normal while GPS warms up and indoors, so it is not reported until
 * [LocationStreamPolicy.graceMillis] passed without any position. Pure and memory-only.
 */
enum class LocationStreamStatus {
    /** Nothing is being requested (no walk, or not started yet). */
    IDLE,
    NEEDS_PERMISSION,
    LOCATION_OFF,

    /** Waiting for the first position. */
    SEARCHING,
    AVAILABLE,

    /** Location is on, but no position for longer than the grace period (indoors, weak signal). */
    NO_SIGNAL,
    SERVICES_UNAVAILABLE
}

object LocationStreamPolicy {
    const val NO_SIGNAL_GRACE_MILLIS = 60_000L

    /** The silence that counts as "no signal": at least a minute, and never shorter than 3 requested intervals. */
    fun graceMillis(intervalMillis: Long): Long = maxOf(NO_SIGNAL_GRACE_MILLIS, intervalMillis * 3)

    /**
     * The status after one update, or null when the update changes nothing yet (a plain "no position right now": the
     * silence watchdog decides). Switching location back on moves OFF to SEARCHING.
     */
    fun after(current: LocationStreamStatus, update: LocationUpdate): LocationStreamStatus? = when (update) {
        is LocationUpdate.Fix -> LocationStreamStatus.AVAILABLE
        is LocationUpdate.Unavailable -> when (update.reason) {
            LocationUnavailableReason.PERMISSION_DENIED -> LocationStreamStatus.NEEDS_PERMISSION
            LocationUnavailableReason.LOCATION_DISABLED -> LocationStreamStatus.LOCATION_OFF
            LocationUnavailableReason.SERVICES_UNAVAILABLE -> LocationStreamStatus.SERVICES_UNAVAILABLE
            LocationUnavailableReason.NO_FIX ->
                if (current == LocationStreamStatus.LOCATION_OFF) LocationStreamStatus.SEARCHING else null
        }
    }

    /** How often a running stream looks at the phone's location switch (the platform callback alone is not reliable). */
    const val SWITCH_CHECK_MILLIS = 5_000L

    /**
     * The status after looking at the phone's location switch. Found on the emulator: the fused provider does not
     * always say "no longer available" when the switch is turned off, so a running stream asks the platform itself.
     * Off outranks searching / available / no signal; back on starts searching again.
     */
    fun afterSwitch(current: LocationStreamStatus, locationEnabled: Boolean): LocationStreamStatus = when {
        !locationEnabled && (current == LocationStreamStatus.SEARCHING || current == LocationStreamStatus.AVAILABLE ||
            current == LocationStreamStatus.NO_SIGNAL) -> LocationStreamStatus.LOCATION_OFF
        locationEnabled && current == LocationStreamStatus.LOCATION_OFF -> LocationStreamStatus.SEARCHING
        else -> current
    }

    /** After the grace period without any position: searching or "available" (but silent) becomes NO_SIGNAL. */
    fun afterSilence(current: LocationStreamStatus): LocationStreamStatus =
        if (current == LocationStreamStatus.SEARCHING || current == LocationStreamStatus.AVAILABLE) {
            LocationStreamStatus.NO_SIGNAL
        } else current

    /** What a stream starts as for this permission. */
    fun initial(access: LocationAccess): LocationStreamStatus =
        if (access == LocationAccess.NONE) LocationStreamStatus.NEEDS_PERMISSION else LocationStreamStatus.SEARCHING
}
