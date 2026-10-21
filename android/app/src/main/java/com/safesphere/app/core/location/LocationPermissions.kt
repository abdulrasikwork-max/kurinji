package com.safesphere.app.core.location

import android.Manifest

/** The outcome of asking for location, as the UI needs it. */
enum class LocationPermissionStatus {
    GRANTED_PRECISE,
    GRANTED_APPROXIMATE,

    /** Denied, but Android will still show the dialog if we ask again. */
    DENIED,

    /** Denied with "Don't ask again" (or twice on Android 11+): only the app's Settings page can change it. */
    PERMANENTLY_DENIED
}

object LocationPermissions {

    /**
     * Both are requested together: since Android 12 the person then chooses between precise and
     * approximate. Background location is deliberately NOT requested (see docs/PLATFORM_CONSTRAINTS.md).
     */
    val REQUESTED = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * Interprets the result of a permission request. Pure, so it is unit-tested.
     * After a denial, Android stops showing the rationale exactly when it will no longer show the dialog,
     * which is how a permanent denial is recognised.
     */
    fun statusAfterRequest(
        fineGranted: Boolean,
        coarseGranted: Boolean,
        shouldShowRationale: Boolean
    ): LocationPermissionStatus = when {
        fineGranted -> LocationPermissionStatus.GRANTED_PRECISE
        coarseGranted -> LocationPermissionStatus.GRANTED_APPROXIMATE
        shouldShowRationale -> LocationPermissionStatus.DENIED
        else -> LocationPermissionStatus.PERMANENTLY_DENIED
    }
}
