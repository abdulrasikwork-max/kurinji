package com.safesphere.app.feature.safewalk

import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.feature.safewalk.domain.WalkLineGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 17F: where the start, the destination, the band and "you" go on the Safe Walk line. */
class WalkLineGeometryTest {

    // About 1.1 km due east of the start (at 12.97 N one degree of longitude is about 108 km).
    private val start = GeoPoint(12.9700, 77.5900)
    private val east = GeoPoint(12.9700, 77.6000)
    private val north = GeoPoint(12.9800, 77.5900)

    @Test
    fun `north is up and east is right`() {
        val layout = WalkLineGeometry.layout(start, east, null, 250.0)!!
        assertTrue("destination is to the right of the start", layout.destination.x > layout.start.x)
        assertEquals("same height", layout.start.y, layout.destination.y, 0.001f)

        val up = WalkLineGeometry.layout(start, north, null, 250.0)!!
        assertTrue("a destination to the north is higher on the screen (smaller y)", up.destination.y < up.start.y)
        assertEquals(up.start.x, up.destination.x, 0.001f)
    }

    @Test
    fun `everything stays inside the square and the line is centred`() {
        val layout = WalkLineGeometry.layout(start, east, GeoPoint(12.9702, 77.5950), 250.0)!!
        listOf(layout.start, layout.destination, layout.position!!).forEach {
            assertTrue(it.x in 0f..1f && it.y in 0f..1f)
        }
        assertEquals(0.5f, (layout.start.x + layout.destination.x) / 2, 0.01f)
    }

    @Test
    fun `the scale is the same in both directions so shapes are not stretched`() {
        // A walk one third as far north as it is east must look one third as tall as wide.
        val dest = GeoPoint(12.9700 + 0.0033, 77.6000) // ~367 m north, ~1.08 km east
        val layout = WalkLineGeometry.layout(start, dest, null, 100.0)!!
        val width = layout.destination.x - layout.start.x
        val height = layout.start.y - layout.destination.y
        assertEquals(0.34f, height / width, 0.03f)
    }

    @Test
    fun `how far along the line you are, 0 at the start and 1 at the destination, clamped`() {
        val half = WalkLineGeometry.layout(start, east, GeoPoint(12.9700, 77.5950), 250.0)!!
        assertEquals(0.5f, half.alongFraction!!, 0.02f)
        val behind = WalkLineGeometry.layout(start, east, GeoPoint(12.9700, 77.5800), 250.0)!!
        assertEquals(0f, behind.alongFraction!!, 0.0001f)
        val past = WalkLineGeometry.layout(start, east, GeoPoint(12.9700, 77.6200), 250.0)!!
        assertEquals(1f, past.alongFraction!!, 0.0001f)
    }

    @Test
    fun `the band is drawn at the bubble's half-width and never cut off by the edge`() {
        val layout = WalkLineGeometry.layout(start, east, null, 250.0)!!
        val metersPerUnit = 250.0 / layout.bandHalfWidth
        // The 1.1 km line (about 1090 m) spans the usable width, 1 - 2 * margin, of the square.
        assertEquals(1090.0 / metersPerUnit, (layout.destination.x - layout.start.x).toDouble(), 0.03)
        assertTrue("the band fits inside the square", layout.start.y - layout.bandHalfWidth >= 0f)
        assertTrue(layout.start.y + layout.bandHalfWidth <= 1f)
    }

    @Test
    fun `a very wide band on a short walk still fits`() {
        val near = GeoPoint(12.9700, 77.5918) // ~195 m
        val layout = WalkLineGeometry.layout(start, near, null, 250.0)!!
        assertTrue(layout.start.y - layout.bandHalfWidth >= -0.0001f)
        assertTrue(layout.start.y + layout.bandHalfWidth <= 1.0001f)
    }

    @Test
    fun `a walk shorter than the arrival radius draws no line at all`() {
        assertNull(WalkLineGeometry.layout(start, GeoPoint(12.9700, 77.5905), null, 250.0)) // ~54 m
        assertNull(WalkLineGeometry.layout(start, GeoPoint(12.9700, 77.5908), null, 250.0)) // ~87 m
        assertNotNull(WalkLineGeometry.layout(start, GeoPoint(12.9700, 77.5910), null, 250.0)) // ~108 m
    }

    @Test
    fun `no line without a start, without a destination, or when they are the same place`() {
        assertNull(WalkLineGeometry.layout(null, east, null, 250.0))
        assertNull(WalkLineGeometry.layout(start, null, null, 250.0))
        assertNull(WalkLineGeometry.layout(start, start, GeoPoint(12.97, 77.59), 250.0))
        assertNull(WalkLineGeometry.layout(start, GeoPoint(12.970001, 77.590001), null, 250.0)) // under a metre apart
    }

    @Test
    fun `without a position there is no you, and a far position makes room instead of falling off`() {
        assertNull(WalkLineGeometry.layout(start, east, null, 250.0)!!.position)
        val far = WalkLineGeometry.layout(start, east, GeoPoint(13.0500, 77.5950), 250.0)!!
        assertNotNull(far.position)
        assertTrue(far.position!!.x in 0f..1f && far.position!!.y in 0f..1f)
    }
}
