package com.safesphere.app.feature.emergency.location

import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.location.LocationFix
import java.time.Instant

/** The location side of an emergency, as the screen and the notification need it. */
enum class LocationSharingStatus {
    /** No emergency is using location. */
    OFF,

    /** No location permission. Nothing is collected until the person allows it. */
    PERMISSION_NEEDED,

    /** Permission is fine but the phone's location setting is off. */
    LOCATION_DISABLED,

    /** Waiting for the first fix. */
    SEARCHING,

    /** Fixes are arriving. */
    AVAILABLE,

    /** Location is on but no fix right now (indoors, no signal). Usually temporary. */
    UNAVAILABLE,

    /** Google Play services missing or outdated: location cannot work on this phone. */
    SERVICES_UNAVAILABLE
}

/**
 * Shared by the emergency screen and the tracking foreground service (one holder, two readers).
 * Locations are saved only to the person's OWN emergency record; nobody is sent anything.
 *
 * @param pendingCount points recorded on the phone but not yet saved on the server (kept in memory only)
 * @param lastSavedAt  when the server last confirmed a save (phone time)
 * @param rejectedCount points the server refused as invalid (for example the phone's clock is far off)
 * @param uploadsStopped the server no longer accepts points for this emergency (limit reached, or ended);
 *                       [uploadsStoppedMessage] is the server's own explanation when it gave one
 * @param signedOut    the session ended (logout or expired token): sharing stopped
 */
data class LocationSharingState(
    val emergencyId: Long? = null,
    val status: LocationSharingStatus = LocationSharingStatus.OFF,
    val access: LocationAccess = LocationAccess.NONE,
    val lastFix: LocationFix? = null,
    val pendingCount: Int = 0,
    val lastSavedAt: Instant? = null,
    val isOnline: Boolean = true,
    val rejectedCount: Int = 0,
    val uploadsStopped: Boolean = false,
    val uploadsStoppedMessage: String? = null,
    val signedOut: Boolean = false
) {
    /** True while location updates are being requested, which is when the foreground service must run. */
    val isTracking: Boolean
        get() = emergencyId != null && !signedOut && status in TRACKING_STATUSES

    val isApproximate: Boolean get() = access == LocationAccess.APPROXIMATE

    private companion object {
        val TRACKING_STATUSES = setOf(
            LocationSharingStatus.LOCATION_DISABLED,
            LocationSharingStatus.SEARCHING,
            LocationSharingStatus.AVAILABLE,
            LocationSharingStatus.UNAVAILABLE
        )
    }
}
