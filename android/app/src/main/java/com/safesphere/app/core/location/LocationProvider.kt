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

    /**
     * Phase 16: ONE position, at most [maxAgeMillis] old (a recent one comes back at once), or the reason there is
     * none, within [timeoutMillis]. Screens use [oneFix] on top of it. The default reads [updates] once; the fused
     * provider asks the platform for a single current location instead (faster, less battery).
     */
    suspend fun currentFix(maxAgeMillis: Long, timeoutMillis: Long): LocationUpdate = firstUpdate(timeoutMillis)
}
