package com.safesphere.app.core.location

import kotlinx.coroutines.flow.Flow

/**
 * The app's only way to read the device location. Production: [FusedLocationProvider]. Tests: a fake.
 * Keeping Google Play services behind this interface means the rest of the app (and its tests) never
 * depends on it.
 */
interface LocationProvider {

    /** What the person currently allows. Checked every time, because it can change in Settings. */
    fun access(): LocationAccess

    /** Whether the phone's location setting is on. */
    fun isLocationEnabled(): Boolean

    /**
     * Location updates for as long as the flow is collected. Never throws for platform problems: it emits
     * [LocationUpdate.Unavailable] instead (no permission, location off, no fix, Play services missing).
     * Collecting needs a foreground screen or a location foreground service; this app never asks for
     * background location.
     */
    fun updates(intervalMillis: Long): Flow<LocationUpdate>
}
