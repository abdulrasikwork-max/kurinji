package com.safesphere.app.core.location

import kotlin.math.cos
import kotlin.math.hypot

/** A place (no time, no accuracy), e.g. a Safe Walk destination. Never logged. */
data class GeoPoint(val latitude: Double, val longitude: Double) {
    override fun toString(): String = "GeoPoint(<redacted>)"

    companion object {
        fun of(fix: LocationFix) = GeoPoint(fix.latitude, fix.longitude)
    }
}

/**
 * Plane geometry for walking distances. Pure Kotlin, unit-tested.
 *
 * Points are projected onto a flat plane around the segment's start (equirectangular projection). Over a few
 * tens of kilometres the error is well under 1 %, far less than GPS noise and the Safety Bubble's tolerance.
 */
object RouteGeometry {

    private const val METERS_PER_DEGREE = 6_371_000.0 * Math.PI / 180.0

    /** Shortest distance in metres from [point] to the segment [start]-[end] (not the infinite line). */
    fun distanceToSegmentMeters(point: GeoPoint, start: GeoPoint, end: GeoPoint): Double {
        val scaleX = cos(Math.toRadians(start.latitude)) * METERS_PER_DEGREE
        val px = (point.longitude - start.longitude) * scaleX
        val py = (point.latitude - start.latitude) * METERS_PER_DEGREE
        val ex = (end.longitude - start.longitude) * scaleX
        val ey = (end.latitude - start.latitude) * METERS_PER_DEGREE
        val lengthSquared = ex * ex + ey * ey
        if (lengthSquared == 0.0) return hypot(px, py)
        // Position of the closest point along the segment, clamped to its ends.
        val t = ((px * ex + py * ey) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(px - t * ex, py - t * ey)
    }

    fun distanceMeters(from: GeoPoint, to: GeoPoint): Double =
        GeoDistance.meters(from.latitude, from.longitude, to.latitude, to.longitude)
}
