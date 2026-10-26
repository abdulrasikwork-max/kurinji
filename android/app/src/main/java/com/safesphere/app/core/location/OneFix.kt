package com.safesphere.app.core.location

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phase 16 location audit: the answer to "where am I, once?" for screens that need ONE position (Places near me,
 * Discover's "Near me", private places). Every outcome the person can act on is its own case, so a screen can say
 * exactly what to do instead of a generic "unavailable" after a long wait.
 */
sealed interface OneFix {
    data class Got(val fix: LocationFix, val access: LocationAccess) : OneFix

    /** No location permission: ask for it (or, if it was refused for good, open the app's settings). */
    data object NeedsPermission : OneFix

    /** The phone's location setting is off: open the location settings. */
    data object LocationOff : OneFix

    /** Location is on but no position came in time (indoors, emulator without a position...). Try again. */
    data object NotFound : OneFix

    /** Neither Google Play services nor the platform could give a position. */
    data object Unavailable : OneFix
}

object OneFixes {
    /** A position up to 2 minutes old is good enough to list places in a ~1 km area: no waiting for GPS. */
    const val AREA_MAX_AGE_MILLIS = 120_000L

    /** Adding a place needs a fresh, precise position (the ~150 m cell). */
    const val FRESH_MAX_AGE_MILLIS = 15_000L

    const val TIMEOUT_MILLIS = 15_000L
}

/** One position from this provider ([LocationProvider.currentFix]), read once; collection stops at once. */
suspend fun LocationProvider.oneFix(
    maxAgeMillis: Long = OneFixes.AREA_MAX_AGE_MILLIS,
    timeoutMillis: Long = OneFixes.TIMEOUT_MILLIS
): OneFix {
    val access = access()
    if (access == LocationAccess.NONE) return OneFix.NeedsPermission
    if (!isLocationEnabled()) return OneFix.LocationOff
    return when (val update = currentFix(maxAgeMillis, timeoutMillis)) {
        is LocationUpdate.Fix -> OneFix.Got(update.fix, access())
        is LocationUpdate.Unavailable -> when (update.reason) {
            LocationUnavailableReason.PERMISSION_DENIED -> OneFix.NeedsPermission
            LocationUnavailableReason.LOCATION_DISABLED -> OneFix.LocationOff
            LocationUnavailableReason.NO_FIX -> OneFix.NotFound
            LocationUnavailableReason.SERVICES_UNAVAILABLE -> OneFix.Unavailable
        }
    }
}

/**
 * The default one-shot read for any [LocationProvider]: the first fix from [LocationProvider.updates], or the first
 * lasting problem, or NO_FIX after [timeoutMillis]. (A short "no fix yet" is not an answer: the next fix may follow.)
 */
internal suspend fun LocationProvider.firstUpdate(timeoutMillis: Long): LocationUpdate =
    withTimeoutOrNull(timeoutMillis) {
        updates(ONE_FIX_INTERVAL_MILLIS).first { update ->
            update is LocationUpdate.Fix ||
                (update is LocationUpdate.Unavailable && update.reason != LocationUnavailableReason.NO_FIX)
        }
    } ?: LocationUpdate.Unavailable(LocationUnavailableReason.NO_FIX)

internal const val ONE_FIX_INTERVAL_MILLIS = 2_000L
