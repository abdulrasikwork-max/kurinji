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

    /** A rectangle in view pixels (y pointing down). */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun overlaps(other: Box): Boolean =
            left < other.right && other.left < right && top < other.bottom && other.top < bottom

        /** Distance from the point to the nearest point of this box (0 when inside). */
        fun distanceTo(x: Float, y: Float): Float =
            hypot((x - x.coerceIn(left, right)), (y - y.coerceIn(top, bottom)))
    }

    /**
     * 17F-V7: where the text of a distance ring goes. At 2.0x text the three labels (500 m, 1 km, 2 km) all sat on the same
     * diagonal and ran into each other and into "You". The 500 m label now goes below-right of its ring, starting at the
     * ring (so it never covers the person in the middle), and the other two stay above-right, centred - far enough apart
     * at any text size. [index] 0 = the innermost ring. Only drawing: the rings and the list say the same as before.
     */
    fun ringLabelBox(
        index: Int, centerX: Float, centerY: Float, ringRadius: Float, textWidth: Float, textHeight: Float, gap: Float
    ): Box {
        val diagonal = ringRadius * 0.71f
        return if (index == 0) {
            Box(centerX + diagonal + gap, centerY + diagonal, centerX + diagonal + gap + textWidth, centerY + diagonal + textHeight)
        } else {
            val middle = centerX + diagonal + gap
            Box(middle - textWidth / 2f, centerY - diagonal - textHeight * 0.8f, middle + textWidth / 2f, centerY - diagonal + textHeight * 0.2f)
        }
    }

    /**
     * 17F-V7: the centre of the number that goes with a mark. It sits on the OUTSIDE of the mark (away from the person in the
     * middle), so the "You" ring can never cover it. A mark exactly at the centre gets its number up and to the right.
     */
    fun numberSpot(markX: Float, markY: Float, centerX: Float, centerY: Float, offset: Float): Mark {
        var dx = markX - centerX
        var dy = markY - centerY
        var distance = hypot(dx, dy)
        if (distance < 0.01f) {
            dx = 0.7071f; dy = -0.7071f; distance = 1f
        }
        return Mark(markX + dx / distance * offset, markY + dy / distance * offset)
    }

    /**
     * Returns marks at least [minGap] apart (when there is room), keeping each within [limit] of the centre
     * ([centerX], [centerY]). Deterministic: the same input gives the same output, so a redraw never makes marks dance.
     * Marks are treated in list order; an earlier (nearer) mark is pushed less than a later one.
     *
     * 17F-V7: [keepClear] > 0 also keeps every mark at least that far from the centre, where the person's own ring is
     * drawn (a mark used to sit half under it). It moves a mark only where it is drawn; the list keeps the true distance.
     */
    fun spread(
        marks: List<Mark>,
        minGap: Float,
        centerX: Float,
        centerY: Float,
        limit: Float,
        rounds: Int = 60,
        keepClear: Float = 0f
    ): List<Mark> {
        if (marks.isEmpty()) return marks
        if (marks.size < 2 && keepClear <= 0f) return marks
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
                } else if (d < keepClear - 0.001f) {
                    // Out from under the person's own ring, along the same direction (up-right when exactly on the centre).
                    val (ux, uy) = if (d < 0.01f) 0.7071f to -0.7071f else ox / d to oy / d
                    xs[i] = centerX + ux * keepClear
                    ys[i] = centerY + uy * keepClear
                    moved = true
                }
            }
            if (!moved) break
        }
        return marks.indices.map { Mark(xs[it], ys[it]) }
    }
}
