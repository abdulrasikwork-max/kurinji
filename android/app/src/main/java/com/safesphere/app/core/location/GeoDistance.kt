package com.safesphere.app.core.location

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle distance (haversine formula). Pure Kotlin, so it works in unit tests without Android. */
object GeoDistance {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun meters(fromLatitude: Double, fromLongitude: Double, toLatitude: Double, toLongitude: Double): Double {
        val dLat = Math.toRadians(toLatitude - fromLatitude)
        val dLon = Math.toRadians(toLongitude - fromLongitude)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(fromLatitude)) * cos(Math.toRadians(toLatitude)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(a)))
    }

    fun meters(from: LocationFix, to: LocationFix): Double =
        meters(from.latitude, from.longitude, to.latitude, to.longitude)
}
