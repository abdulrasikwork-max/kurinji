package com.safesphere.app.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.time.Instant
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [LocationProvider] backed by Google Play services' fused location provider (GPS + Wi-Fi + cell).
 * Precise permission -> high accuracy; approximate permission -> balanced power (city-block level).
 * Nothing here logs a position.
 */
class FusedLocationProvider(context: Context) : LocationProvider {

    private val appContext = context.applicationContext
    private val client by lazy { LocationServices.getFusedLocationProviderClient(appContext) }
    private val locationManager: LocationManager? = appContext.getSystemService(LocationManager::class.java)

    override fun access(): LocationAccess = when {
        isGranted(Manifest.permission.ACCESS_FINE_LOCATION) -> LocationAccess.PRECISE
        isGranted(Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationAccess.APPROXIMATE
        else -> LocationAccess.NONE
    }

    override fun isLocationEnabled(): Boolean =
        locationManager?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false

    // The permission is checked by access() just before the request; a revocation race is still caught below.
    @SuppressLint("MissingPermission")
    override fun updates(intervalMillis: Long): Flow<LocationUpdate> = callbackFlow {
        if (!isPlayServicesAvailable()) {
            trySend(LocationUpdate.Unavailable(LocationUnavailableReason.SERVICES_UNAVAILABLE))
            close()
            return@callbackFlow
        }
        val access = access()
        if (access == LocationAccess.NONE) {
            trySend(LocationUpdate.Unavailable(LocationUnavailableReason.PERMISSION_DENIED))
            close()
            return@callbackFlow
        }
        if (!isLocationEnabled()) {
            // Keep the request running: fixes start arriving as soon as the person turns location on.
            trySend(LocationUpdate.Unavailable(LocationUnavailableReason.LOCATION_DISABLED))
        }

        val priority = if (access == LocationAccess.PRECISE) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val request = LocationRequest.Builder(priority, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis / 2)
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { trySend(LocationUpdate.Fix(it.toFix())) }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    val reason = if (isLocationEnabled()) {
                        LocationUnavailableReason.NO_FIX
                    } else {
                        LocationUnavailableReason.LOCATION_DISABLED
                    }
                    trySend(LocationUpdate.Unavailable(reason))
                }
            }
        }

        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener { error -> trySend(LocationUpdate.Unavailable(reasonFor(error))) }
        } catch (e: SecurityException) {
            trySend(LocationUpdate.Unavailable(LocationUnavailableReason.PERMISSION_DENIED))
            close()
        }

        awaitClose { client.removeLocationUpdates(callback) }
    }

    /**
     * Phase 16: one current position from the fused provider ("getCurrentLocation": a recent cached fix comes back at
     * once, otherwise one fresh fix, then it stops by itself). Without Play services the platform's own location
     * manager is asked instead, so phones without Google services still work.
     */
    @SuppressLint("MissingPermission")
    override suspend fun currentFix(maxAgeMillis: Long, timeoutMillis: Long, areaOnly: Boolean): LocationUpdate {
        val access = access()
        if (access == LocationAccess.NONE) return LocationUpdate.Unavailable(LocationUnavailableReason.PERMISSION_DENIED)
        if (!isLocationEnabled()) return LocationUpdate.Unavailable(LocationUnavailableReason.LOCATION_DISABLED)
        // A safety net over the platform's own duration: never wait longer than asked (plus a little).
        return withTimeoutOrNull(timeoutMillis + TIMEOUT_GRACE_MILLIS) {
            try {
                if (isPlayServicesAvailable()) fusedFix(access, maxAgeMillis, timeoutMillis, areaOnly)
                else platformFix(access, maxAgeMillis)
            } catch (e: SecurityException) {
                LocationUpdate.Unavailable(LocationUnavailableReason.PERMISSION_DENIED)
            }
        } ?: LocationUpdate.Unavailable(LocationUnavailableReason.NO_FIX)
    }

    @SuppressLint("MissingPermission")
    private suspend fun fusedFix(
        access: LocationAccess,
        maxAgeMillis: Long,
        timeoutMillis: Long,
        areaOnly: Boolean
    ): LocationUpdate {
        // Phase 17B: GPS only when the caller needs the block (adding a place); an area reading uses Wi-Fi / cell.
        val request = CurrentLocationRequest.Builder()
            .setPriority(if (access == LocationAccess.PRECISE && !areaOnly) Priority.PRIORITY_HIGH_ACCURACY
                else Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setMaxUpdateAgeMillis(maxAgeMillis)
            .setDurationMillis(timeoutMillis)
            .build()
        val cancel = CancellationTokenSource()
        val current: LocationUpdate = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel.cancel() }
            client.getCurrentLocation(request, cancel.token)
                .addOnSuccessListener { location ->
                    if (continuation.isActive) continuation.resume(location?.let { LocationUpdate.Fix(it.toFix()) }
                        ?: LocationUpdate.Unavailable(LocationUnavailableReason.NO_FIX))
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resume(LocationUpdate.Unavailable(reasonFor(error)))
                }
        }
        if (current !is LocationUpdate.Unavailable || current.reason != LocationUnavailableReason.NO_FIX) return current
        // Phase 17B: nothing new in time (indoors, GPS cold): the platform's last position, if recent enough.
        return lastKnownWithin(maxAgeMillis)?.let { LocationUpdate.Fix(it.toFix()) } ?: current
    }

    @SuppressLint("MissingPermission")
    private suspend fun lastKnownWithin(maxAgeMillis: Long): Location? = suspendCancellableCoroutine { continuation ->
        client.lastLocation
            .addOnSuccessListener { location ->
                val fresh = location?.takeIf {
                    (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 <= maxAgeMillis
                }
                if (continuation.isActive) continuation.resume(fresh)
            }
            .addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
    }

    /** No Play services: the platform's last known position if recent enough, else one current position. */
    @SuppressLint("MissingPermission")
    private suspend fun platformFix(access: LocationAccess, maxAgeMillis: Long): LocationUpdate {
        val manager = locationManager
            ?: return LocationUpdate.Unavailable(LocationUnavailableReason.SERVICES_UNAVAILABLE)
        val providers = buildList {
            if (access == LocationAccess.PRECISE && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }
        if (providers.isEmpty()) return LocationUpdate.Unavailable(LocationUnavailableReason.SERVICES_UNAVAILABLE)
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        providers.mapNotNull { manager.getLastKnownLocation(it) }
            .filter { (nowNanos - it.elapsedRealtimeNanos) / 1_000_000 <= maxAgeMillis }
            .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
            ?.let { return LocationUpdate.Fix(it.toFix()) }
        val signal = androidx.core.os.CancellationSignal()
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { signal.cancel() }
            LocationManagerCompat.getCurrentLocation(manager, providers.first(), signal,
                ContextCompat.getMainExecutor(appContext)) { location ->
                if (continuation.isActive) continuation.resume(location?.let { LocationUpdate.Fix(it.toFix()) }
                    ?: LocationUpdate.Unavailable(LocationUnavailableReason.NO_FIX))
            }
        }
    }

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun isPlayServicesAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS

    private fun reasonFor(error: Exception): LocationUnavailableReason = when {
        error is SecurityException -> LocationUnavailableReason.PERMISSION_DENIED
        error is ApiException && error.statusCode == CommonStatusCodes.API_NOT_CONNECTED ->
            LocationUnavailableReason.SERVICES_UNAVAILABLE
        else -> LocationUnavailableReason.NO_FIX
    }

    private companion object {
        const val TIMEOUT_GRACE_MILLIS = 2_000L
    }

    private fun Location.toFix() = LocationFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        source = locationSourceForProvider(provider),
        recordedAt = Instant.ofEpochMilli(time)
    )
}
