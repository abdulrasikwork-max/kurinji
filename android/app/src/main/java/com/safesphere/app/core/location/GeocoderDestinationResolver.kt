package com.safesphere.app.core.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.math.cos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [DestinationResolver] using the platform [Geocoder]. Known to be unreliable (no backend on some phones, no
 * network, odd matches), so every failure becomes [DestinationLookup.Unavailable] / [DestinationLookup.NotFound]
 * and the Safe Walk simply runs without route checks. The typed text is sent to the phone's map service; the
 * privacy policy says so. Nothing is logged.
 */
class GeocoderDestinationResolver(context: Context) : DestinationResolver {

    private val appContext = context.applicationContext

    override suspend fun resolve(query: String, near: GeoPoint?): DestinationLookup {
        if (query.isBlank() || !Geocoder.isPresent()) return DestinationLookup.Unavailable
        val addresses = withTimeoutOrNull(TIMEOUT_MILLIS) { lookup(Geocoder(appContext), query.trim(), near) }
            ?: return DestinationLookup.Unavailable
        val address = addresses.firstOrNull { it.hasLatitude() && it.hasLongitude() } ?: return DestinationLookup.NotFound
        return DestinationLookup.Found(
            GeoPoint(address.latitude, address.longitude),
            address.getAddressLine(0)?.take(MAX_DESCRIPTION_LENGTH)
        )
    }

    /** Null = the service failed (as opposed to an empty list = nothing found). */
    private suspend fun lookup(geocoder: Geocoder, query: String, near: GeoPoint?): List<Address>? {
        val box = near?.let(::searchBox)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (continuation.isActive) continuation.resume(addresses)
                    }

                    override fun onError(errorMessage: String?) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                try {
                    if (box == null) {
                        geocoder.getFromLocationName(query, MAX_RESULTS, listener)
                    } else {
                        geocoder.getFromLocationName(query, MAX_RESULTS, box[0], box[1], box[2], box[3], listener)
                    }
                } catch (e: IllegalArgumentException) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        } else {
            // Before Android 13 the call blocks (network), so it runs off the main thread.
            withContext(Dispatchers.IO) {
                try {
                    @Suppress("DEPRECATION")
                    if (box == null) {
                        geocoder.getFromLocationName(query, MAX_RESULTS)
                    } else {
                        geocoder.getFromLocationName(query, MAX_RESULTS, box[0], box[1], box[2], box[3])
                    }
                } catch (e: IOException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
        }
    }

    /** lowerLeftLat, lowerLeftLng, upperRightLat, upperRightLng: about [SEARCH_RADIUS_KM] around [center]. */
    private fun searchBox(center: GeoPoint): DoubleArray {
        val dLat = SEARCH_RADIUS_KM / 111.0
        val dLng = SEARCH_RADIUS_KM / (111.0 * cos(Math.toRadians(center.latitude)).coerceAtLeast(0.01))
        return doubleArrayOf(
            (center.latitude - dLat).coerceIn(-90.0, 90.0),
            (center.longitude - dLng).coerceIn(-180.0, 180.0),
            (center.latitude + dLat).coerceIn(-90.0, 90.0),
            (center.longitude + dLng).coerceIn(-180.0, 180.0)
        )
    }

    private companion object {
        const val MAX_RESULTS = 3
        const val TIMEOUT_MILLIS = 6_000L
        /** A Safe Walk destination is expected within this distance; it also avoids far-away namesakes. */
        const val SEARCH_RADIUS_KM = 50.0
        const val MAX_DESCRIPTION_LENGTH = 120
    }
}
