package com.safesphere.app.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.time.Instant
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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

    private fun Location.toFix() = LocationFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        source = locationSourceForProvider(provider),
        recordedAt = Instant.ofEpochMilli(time)
    )
}
