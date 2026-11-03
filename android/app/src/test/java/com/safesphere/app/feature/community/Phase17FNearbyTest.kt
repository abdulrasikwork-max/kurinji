package com.safesphere.app.feature.community

import com.safesphere.app.R
import com.safesphere.app.feature.community.domain.ReportChoices
import com.safesphere.app.feature.community.domain.SignalCategory
import com.safesphere.app.feature.community.domain.SignalGroup
import com.safesphere.app.feature.community.ui.CommunityTexts
import com.safesphere.app.feature.community.ui.RadarLayout
import com.safesphere.app.feature.community.ui.RadarLayout.Mark
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 17F: the one-step Nearby report, the thank-you wording, and a radar whose marks can all be told apart. */
class Phase17FNearbyTest {

    // ---- the report choices -----------------------------------------------------------------------------

    @Test
    fun `every place condition is offered exactly once, grouped as on the radar, and nothing else`() {
        val offered = ReportChoices.groups.flatMap { it.second }
        assertEquals(SignalCategory.entries.toSet(), offered.toSet())
        assertEquals("each exactly once", SignalCategory.entries.size, offered.size)
        assertEquals(listOf(SignalGroup.INCIDENT, SignalGroup.CONDITION, SignalGroup.HAVEN), ReportChoices.groups.map { it.first })
        ReportChoices.groups.forEach { (group, categories) -> assertTrue(categories.all { it.group == group }) }
    }

    @Test
    fun `the thank-you is said in the unit a person thinks in, never as 1465 minutes`() {
        assertEquals(R.plurals.community_recorded to 1, CommunityTexts.recordedDelay(0))
        assertEquals(R.plurals.community_recorded to 45, CommunityTexts.recordedDelay(45))
        assertEquals(R.plurals.community_recorded to 90, CommunityTexts.recordedDelay(90))
        assertEquals(R.plurals.community_recorded_hours to 2, CommunityTexts.recordedDelay(100))
        assertEquals(R.plurals.community_recorded_hours to 24, CommunityTexts.recordedDelay(1465))
        assertEquals(R.plurals.community_recorded_days to 2, CommunityTexts.recordedDelay(48 * 60))
        assertEquals(R.plurals.community_recorded_days to 3, CommunityTexts.recordedDelay(3 * 1440 - 10))
    }

    // ---- radar layout --------------------------------------------------------------------------------------

    private fun gap(a: Mark, b: Mark) = hypot(a.x - b.x, a.y - b.y)

    @Test
    fun `marks that overlap are moved apart, far ones are left alone`() {
        val marks = listOf(Mark(100f, 100f), Mark(102f, 101f), Mark(101f, 99f), Mark(220f, 180f))
        val spread = RadarLayout.spread(marks, minGap = 24f, centerX = 160f, centerY = 160f, limit = 150f)
        assertEquals(marks.size, spread.size)
        for (i in 0 until 3) for (j in i + 1 until 3) {
            assertTrue("marks $i and $j are readable apart", gap(spread[i], spread[j]) >= 24f * 0.9f)
        }
        assertEquals(marks[3], spread[3])
    }

    @Test
    fun `marks exactly on top of each other are still separated, the same way every time`() {
        val marks = listOf(Mark(80f, 80f), Mark(80f, 80f))
        val first = RadarLayout.spread(marks, 20f, 160f, 160f, 150f)
        val second = RadarLayout.spread(marks, 20f, 160f, 160f, 150f)
        assertEquals(first, second)
        assertTrue(gap(first[0], first[1]) >= 18f)
    }

    @Test
    fun `no mark is pushed out of the circle`() {
        val marks = List(8) { Mark(290f + it * 0.1f, 160f) } // all at the edge, on top of each other
        val spread = RadarLayout.spread(marks, 20f, 160f, 160f, 140f)
        assertTrue(spread.all { hypot(it.x - 160f, it.y - 160f) <= 140.01f })
    }

    @Test
    fun `one mark or none come back unchanged`() {
        assertEquals(emptyList<Mark>(), RadarLayout.spread(emptyList(), 20f, 0f, 0f, 100f))
        val one = listOf(Mark(5f, 5f))
        assertEquals(one, RadarLayout.spread(one, 20f, 0f, 0f, 100f))
    }
}
