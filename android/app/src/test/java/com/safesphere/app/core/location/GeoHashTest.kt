package com.safesphere.app.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoHashTest {

    /** Same file as the backend's src/test/resources/geohash-vectors.csv: phone and server agree on every cell. */
    @Test
    fun `encodes the vectors shared with the backend`() {
        val stream = checkNotNull(javaClass.getResourceAsStream("/geohash-vectors.csv")) { "vectors missing" }
        val lines = stream.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(lines.size >= 10)
        lines.forEach { line ->
            val (lat, lng, expected) = line.split(",")
            assertEquals(line, expected, GeoHash.encode(lat.toDouble(), lng.toDouble(), GeoHash.BLOCK))
            assertEquals(line, expected.take(6), GeoHash.encode(lat.toDouble(), lng.toDouble(), GeoHash.AREA))
        }
    }

    @Test
    fun `two points a few metres apart usually share a block, a kilometre apart they do not`() {
        val a = GeoHash.encode(12.97160, 77.59460, GeoHash.BLOCK)
        val b = GeoHash.encode(12.97165, 77.59465, GeoHash.BLOCK) // ~7 m away
        assertEquals(a, b)
        val far = GeoHash.encode(12.98160, 77.59460, GeoHash.BLOCK) // ~1.1 km north
        assertTrue(a != far)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid coordinates are refused`() {
        GeoHash.encode(91.0, 0.0, GeoHash.BLOCK)
    }
}
