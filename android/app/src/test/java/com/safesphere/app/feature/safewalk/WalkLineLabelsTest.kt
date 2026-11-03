package com.safesphere.app.feature.safewalk

import com.safesphere.app.feature.safewalk.domain.WalkLineLabels
import com.safesphere.app.feature.safewalk.domain.WalkLineLabels.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 17F-V6: a label beside a mark is never moved onto that mark or onto another label (found at 2.0x text on the A23). */
class WalkLineLabelsTest {

    private val viewWidth = 900f
    private val viewHeight = 900f
    private val edge = 5f
    private val gap = 10f

    private fun place(
        x: Float, y: Float, half: Float = 26f, textWidth: Float = 300f, textHeight: Float = 70f,
        preferRight: Boolean = true, width: Float = viewWidth, height: Float = viewHeight, avoid: List<Box> = emptyList()
    ) = WalkLineLabels.place(x, y, half, textWidth, textHeight, width, height, preferRight, gap, edge, avoid)

    @Test
    fun `with room the word goes on the preferred side, level with its mark`() {
        val box = place(x = 200f, y = 400f)
        assertEquals("right of the mark", 200f + 26f + gap, box.left, 0.01f)
        assertEquals("level with the mark", 400f, (box.top + box.bottom) / 2f, 0.01f)
        val left = place(x = 700f, y = 400f, preferRight = false)
        assertEquals("left of the mark", 700f - 26f - gap, left.right, 0.01f)
    }

    @Test
    fun `a destination near the right edge with big text is NOT pushed onto its own diamond (the A23 finding)`() {
        // The diamond is at x = 800 and the word is 300 wide: it cannot fit on the right (1136 > 900) - the old code clamped it
        // back to the left of the edge, straight over the mark. Now it goes to the other side instead.
        val mark = 800f
        val box = place(x = mark, y = 300f, preferRight = true)
        assertFalse("it must not overlap its mark", box.overlaps(WalkLineLabels.markBox(mark, 300f, 26f)))
        assertTrue("and it stays inside the view", box.insideView(viewWidth, viewHeight, edge))
        assertTrue("on the left of the mark, where there is room", box.right <= mark - 26f)
    }

    @Test
    fun `when neither side has room the word goes above or below the mark, still clear of it`() {
        // A narrow view: 300 wide word, 380 wide view, mark in the middle.
        val box = place(x = 190f, y = 400f, width = 380f)
        val mark = WalkLineLabels.markBox(190f, 400f, 26f)
        assertFalse(box.overlaps(mark))
        assertTrue(box.insideView(380f, viewHeight, edge))
        assertTrue("above or below", box.bottom <= mark.top || box.top >= mark.bottom)
    }

    @Test
    fun `a word keeps clear of other marks and labels it is told to avoid`() {
        val other = Box(240f, 365f, 560f, 435f) // sits exactly where the right-hand word would go
        val box = place(x = 200f, y = 400f, avoid = listOf(other))
        assertFalse(box.overlaps(other))
        assertFalse(box.overlaps(WalkLineLabels.markBox(200f, 400f, 26f)))
        assertTrue(box.insideView(viewWidth, viewHeight, edge))
    }

    @Test
    fun `at the top edge the word goes below instead of out of the picture`() {
        // Mark near the top, no room left or right, no room above.
        val box = place(x = 450f, y = 40f, textWidth = 880f)
        assertTrue(box.insideView(viewWidth, viewHeight, edge))
        assertFalse(box.overlaps(WalkLineLabels.markBox(450f, 40f, 26f)))
        assertTrue("below the mark", box.top >= 40f + 26f)
    }

    @Test
    fun `in a view too small for anything the word is still inside it, and the result is deterministic`() {
        val a = place(x = 100f, y = 100f, width = 200f, height = 200f)
        val b = place(x = 100f, y = 100f, width = 200f, height = 200f)
        assertEquals(a, b)
        assertTrue(a.left >= edge && a.top >= edge)
    }

    @Test
    fun `the You word goes above its mark when it can, and moves aside when something is there`() {
        val above = WalkLineLabels.place(400f, 500f, 20f, 120f, 60f, viewWidth, viewHeight, true, gap, edge, aboveFirst = true)
        assertTrue("above the mark", above.bottom <= 500f - 20f)
        val blocker = Box(300f, 400f, 500f, 470f) // another word right where "above" would be
        val moved = WalkLineLabels.place(400f, 500f, 20f, 120f, 60f, viewWidth, viewHeight, true, gap, edge, listOf(blocker), aboveFirst = true)
        assertFalse(moved.overlaps(blocker))
        assertFalse(moved.overlaps(WalkLineLabels.markBox(400f, 500f, 20f)))
    }

    @Test
    fun `ordinary text sizes behave as before - the preferred side whenever it fits`() {
        // 1.0x: a 90 px wide word, mark of 16 px.
        for (x in listOf(60f, 300f, 600f)) {
            val box = place(x = x, y = 500f, half = 16f, textWidth = 90f, textHeight = 28f, preferRight = true)
            assertEquals(x + 16f + gap, box.left, 0.01f)
        }
    }
}
