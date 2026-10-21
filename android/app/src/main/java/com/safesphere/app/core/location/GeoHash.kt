package com.safesphere.app.core.location

/**
 * Geohash cells (Phase 6). Turns a position into the name of a rectangle that contains it, so the phone can share
 * "somewhere in this ~150 m block" instead of the exact location. The exact fix never leaves the phone.
 *
 * Same algorithm as the backend's GeoCell; both are tested against the same vectors (geohash-vectors.csv).
 *   [BLOCK] = 7 characters, ~153 m x 153 m (where a report is placed)
 *   [AREA]  = 6 characters, ~1.2 km x 0.6 km (what is looked up; what others see of a single report)
 */
object GeoHash {

    const val BLOCK = 7
    const val AREA = 6

    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    fun encode(latitude: Double, longitude: Double, precision: Int): String {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0 && precision in 1..12) { "Invalid coordinate" }
        var latLo = -90.0
        var latHi = 90.0
        var lngLo = -180.0
        var lngHi = 180.0
        val hash = StringBuilder(precision)
        var evenBit = true // bits alternate, starting with longitude
        var bit = 0
        var value = 0
        while (hash.length < precision) {
            if (evenBit) {
                val mid = (lngLo + lngHi) / 2
                if (longitude >= mid) { value = (value shl 1) or 1; lngLo = mid } else { value = value shl 1; lngHi = mid }
            } else {
                val mid = (latLo + latHi) / 2
                if (latitude >= mid) { value = (value shl 1) or 1; latLo = mid } else { value = value shl 1; latHi = mid }
            }
            evenBit = !evenBit
            if (++bit == 5) {
                hash.append(BASE32[value])
                bit = 0
                value = 0
            }
        }
        return hash.toString()
    }

    fun encode(point: GeoPoint, precision: Int): String = encode(point.latitude, point.longitude, precision)

    /** Phase 8: [REGION] = 5 characters, ~4.9 km x 4.9 km (what "Check the way" downloads). */
    const val REGION = 5

    private val VALID = Regex("^[0-9b-hjkmnp-z]+$")

    /** True for a well-formed lower-case geohash of exactly [precision] characters. */
    fun isValid(cell: String?, precision: Int): Boolean =
        cell != null && cell.length == precision && VALID.matches(cell)

    /** Phase 8: the rectangle a cell covers (same decoding as the backend's GeoCell.bounds). */
    fun bounds(cell: String): CellBounds {
        var latLo = -90.0
        var latHi = 90.0
        var lngLo = -180.0
        var lngHi = 180.0
        var evenBit = true
        for (char in cell) {
            val value = BASE32.indexOf(char)
            require(value >= 0) { "Invalid geohash" }
            for (b in 4 downTo 0) {
                val one = (value shr b) and 1 == 1
                if (evenBit) {
                    val mid = (lngLo + lngHi) / 2
                    if (one) lngLo = mid else lngHi = mid
                } else {
                    val mid = (latLo + latHi) / 2
                    if (one) latLo = mid else latHi = mid
                }
                evenBit = !evenBit
            }
        }
        return CellBounds(latLo, latHi, lngLo, lngHi)
    }

    fun centre(cell: String): GeoPoint = bounds(cell).centre
}

/** A geohash rectangle in degrees. */
data class CellBounds(val latMin: Double, val latMax: Double, val lngMin: Double, val lngMax: Double) {
    val centre: GeoPoint get() = GeoPoint((latMin + latMax) / 2, (lngMin + lngMax) / 2)

    override fun toString(): String = "CellBounds(<redacted>)"
}
