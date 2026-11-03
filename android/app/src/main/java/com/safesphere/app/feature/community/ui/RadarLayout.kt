package com.safesphere.app.feature.community.ui

import kotlin.math.hypot

/**
 * Phase 17F (radar readability): where each signal's mark is drawn. Pure Kotlin (no Android types), unit-tested.
 *
 * Two signals in the same direction at nearly the same distance used to be drawn on top of each other, so one looked
 * like it did not exist. [spread] moves overlapping marks apart by the smallest push that makes them readable. It only
 * changes where a mark is DRAWN (a few pixels); it never changes which signals exist, their order or any distance shown
 * in the list, so the radar still says nothing the list does not.
 */
object RadarLayout {

    data class Mark(val x: Float, val y: Float)

    /**
     * Returns marks at least [minGap] apart (when there is room), keeping each within [limit] of the centre
     * ([centerX], [centerY]). Deterministic: the same input gives the same output, so a redraw never makes marks dance.
     * Marks are treated in list order; an earlier (nearer) mark is pushed less than a later one.
     */
    fun spread(
        marks: List<Mark>,
        minGap: Float,
        centerX: Float,
        centerY: Float,
        limit: Float,
        rounds: Int = 60
    ): List<Mark> {
        if (marks.size < 2) return marks
        val xs = FloatArray(marks.size) { marks[it].x }
        val ys = FloatArray(marks.size) { marks[it].y }
        for (round in 0 until rounds) {
            var moved = false
            for (i in marks.indices) {
                for (j in i + 1 until marks.size) {
                    var dx = xs[j] - xs[i]
                    var dy = ys[j] - ys[i]
                    var distance = hypot(dx, dy)
                    if (distance >= minGap) continue
                    if (distance < 0.01f) {
                        // Exactly on top of each other: separate along a fixed, index-dependent direction.
                        dx = if ((i + j) % 2 == 0) 1f else -0.6f
                        dy = if ((i + j) % 3 == 0) 0.8f else -1f
                        distance = hypot(dx, dy)
                    }
                    val push = (minGap - distance) / 2f
                    // The later mark takes a bit more of the move, so the nearest signal stays closest to where it is.
                    val laterShare = 0.65f
                    val ux = dx / distance
                    val uy = dy / distance
                    xs[i] -= ux * push * (1f - laterShare) * 2f
                    ys[i] -= uy * push * (1f - laterShare) * 2f
                    xs[j] += ux * push * laterShare * 2f
                    ys[j] += uy * push * laterShare * 2f
                    moved = true
                }
            }
            for (i in marks.indices) {
                val ox = xs[i] - centerX
                val oy = ys[i] - centerY
                val d = hypot(ox, oy)
                if (d > limit) {
                    xs[i] = centerX + ox / d * limit
                    ys[i] = centerY + oy / d * limit
                }
            }
            if (!moved) break
        }
        return marks.indices.map { Mark(xs[it], ys[it]) }
    }
}
