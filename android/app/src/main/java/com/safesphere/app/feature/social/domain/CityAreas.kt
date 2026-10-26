package com.safesphere.app.feature.social.domain

import com.safesphere.app.core.location.GeoHash

/**
 * Phase 16: the phone's copy of the server's launch-city boxes (backend `social/policy/CityAreas`, same numbers), so an
 * area post can say "Around Chennai" instead of a grid code nobody can read. It uses only the 6-character area the post
 * already shows, and runs on the phone: nothing is sent. Pure.
 */
object CityAreas {

    /** {latMin, latMax, lngMin, lngMax}, identical to the server's boxes. */
    private val BOXES: Map<City, DoubleArray> = linkedMapOf(
        City.CHENNAI to doubleArrayOf(12.75, 13.35, 79.95, 80.35),
        City.COIMBATORE to doubleArrayOf(10.85, 11.20, 76.80, 77.15),
        City.MADURAI to doubleArrayOf(9.80, 10.05, 77.98, 78.25),
        City.TIRUCHIRAPPALLI to doubleArrayOf(10.70, 10.92, 78.58, 78.82),
        City.SALEM to doubleArrayOf(11.55, 11.78, 78.05, 78.25),
        City.TIRUNELVELI to doubleArrayOf(8.62, 8.80, 77.62, 77.82),
        City.VELLORE to doubleArrayOf(12.85, 13.00, 79.05, 79.22),
        City.ERODE to doubleArrayOf(11.27, 11.42, 77.65, 77.80),
        City.THOOTHUKUDI to doubleArrayOf(8.70, 8.85, 78.05, 78.20),
        City.THANJAVUR to doubleArrayOf(10.72, 10.86, 79.05, 79.22),
        City.PUDUCHERRY to doubleArrayOf(11.85, 12.05, 79.70, 79.90),
        City.BENGALURU to doubleArrayOf(12.75, 13.20, 77.35, 77.85)
    )

    /** The launch city whose box holds the centre of [cell], or null (also for an invalid cell). */
    fun cityOf(cell: String?): City? {
        if (cell.isNullOrEmpty()) return null
        val centre = runCatching { GeoHash.centre(cell) }.getOrNull() ?: return null
        return BOXES.entries.firstOrNull { (_, box) ->
            centre.latitude in box[0]..box[1] && centre.longitude in box[2]..box[3]
        }?.key
    }
}
