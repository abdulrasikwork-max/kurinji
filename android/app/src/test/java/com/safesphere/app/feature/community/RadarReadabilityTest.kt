package com.safesphere.app.feature.community

import com.safesphere.app.feature.community.ui.RadarLayout
import com.safesphere.app.feature.community.ui.RadarLayout.Mark
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 17F-V7 (found on the A23 at 2.0x text): the person's ring never covers a mark or its number, and ring labels keep apart. */
class RadarReadabilityTest {

    private val cx = 160f
    private val cy = 160f

    @Test
    fun `a mark close to the person is drawn outside the person's ring, in the same direction`() {
        val near = Mark(cx + 12f, cy - 9f) // about 15 px from the centre, up and to the right
        val out = RadarLayout.spread(listOf(near), minGap = 20f, centerX = cx, centerY = cy, limit = 140f, keepClear = 28f).single()
        assertEquals("pushed out to the keep-clear radius", 28f, hypot(out.x - cx, out.y - cy), 0.05f)
        assertTrue("same side as before", out.x > cx && out.y < cy)
    }

    @Test
    fun `a mark exactly on the person goes up and to the right, not nowhere`() {
        val out = RadarLayout.spread(listOf(Mark(cx, cy)), 20f, cx, cy, 140f, keepClear = 28f).single()
        assertEquals(28f, hypot(out.x - cx, out.y - cy), 0.05f)
        assertTrue(out.x > cx && out.y < cy)
    }

    @Test
    fun `marks already clear of the person do not move, and no keep-clear means the old behaviour`() {
        val far = listOf(Mark(cx + 80f, cy), Mark(cx - 60f, cy + 70f))
        assertEquals(far, RadarLayout.spread(far, 20f, cx, cy, 140f, keepClear = 28f))
        val near = listOf(Mark(cx + 5f, cy))
        assertEquals("keepClear defaults to 0: unchanged", near, RadarLayout.spread(near, 20f, cx, cy, 140f))
    }

    @Test
    fun `several marks near the person end up apart from each other AND from the person`() {
        val marks = listOf(Mark(cx + 6f, cy), Mark(cx + 8f, cy - 3f), Mark(cx - 5f, cy + 4f))
        val out = RadarLayout.spread(marks, minGap = 22f, centerX = cx, centerY = cy, limit = 140f, keepClear = 28f)
        out.forEach { assertTrue("clear of the person", hypot(it.x - cx, it.y - cy) >= 28f - 0.5f) }
        for (i in out.indices) for (j in i + 1 until out.size) {
            assertTrue("marks $i and $j apart", hypot(out[i].x - out[j].x, out[i].y - out[j].y) >= 22f - 1f)
        }
        assertEquals("deterministic", out, RadarLayout.spread(marks, 22f, cx, cy, 140f, keepClear = 28f))
    }

    @Test
    fun `the number sits on the outside of its mark, never between the mark and the person`() {
        val mark = Mark(cx + 30f, cy - 40f)
        val number = RadarLayout.numberSpot(mark.x, mark.y, cx, cy, offset = 16f)
        assertEquals("a fixed step beyond the mark", 16f, hypot(number.x - mark.x, number.y - mark.y), 0.01f)
        assertTrue("farther from the person than the mark", hypot(number.x - cx, number.y - cy) > hypot(mark.x - cx, mark.y - cy))
        val onCentre = RadarLayout.numberSpot(cx, cy, cx, cy, offset = 16f)
        assertTrue("a mark on the centre: up and to the right", onCentre.x > cx && onCentre.y < cy)
    }

    /** The label boxes for the three rings (500 m, 1 km, 2 km) of a radar whose outer ring has radius [outer]. */
    private fun ringBoxes(outer: Float, textWidth: Float, textHeight: Float) =
        listOf(outer / 4f, outer / 2f, outer).mapIndexed { index, r ->
            RadarLayout.ringLabelBox(index, cx, cy, r, textWidth, textHeight, gap = 2f)
        }

    @Test
    fun `the three ring labels never touch each other, at normal text and at 2_0x`() {
        // (width, height) of a label in px: normal text, 1.5x and 2.0x. Outer ring radius 100..160 px.
        for ((w, h) in listOf(40f to 14f, 60f to 21f, 80f to 28f, 100f to 33f)) {
            for (outer in listOf(100f, 130f, 160f)) {
                val boxes = ringBoxes(outer, w, h)
                for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                    assertFalse("labels $i and $j overlap at ${w}x$h, outer ring $outer", boxes[i].overlaps(boxes[j]))
                }
            }
        }
    }

    @Test
    fun `no ring label covers the person's own ring`() {
        val personRing = 14f // the dot and ring plus a margin
        for ((w, h) in listOf(40f to 14f, 80f to 28f, 100f to 33f)) {
            for (outer in listOf(100f, 130f, 160f)) {
                ringBoxes(outer, w, h).forEachIndexed { i, box ->
                    assertTrue("label $i is clear of the person at ${w}x$h, outer $outer", box.distanceTo(cx, cy) > personRing)
                }
            }
        }
    }

    @Test
    fun `the 500 m label starts at its ring and goes below, the others go above`() {
        val boxes = ringBoxes(130f, 60f, 21f)
        assertTrue("500 m is below the middle", boxes[0].top >= cy)
        assertTrue("1 km is above the middle", boxes[1].bottom <= cy)
        assertTrue("2 km is above the middle", boxes[2].bottom <= cy)
    }
}
