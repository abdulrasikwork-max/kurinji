package com.safesphere.app.feature.safewalk.domain

/**
 * 17F-V6 (found on the A23 at 2.0x text): where the words beside the Safe Walk line's marks go. Pure Kotlin, unit-tested;
 * the view only draws what this returns.
 *
 * The first version put a label on one side of its mark and then pushed it back inside the view. With big text and a
 * destination near the edge, the push moved the word ONTO its own mark ("Destination" printed over the diamond). Now a
 * label is only ever put where it fits without being moved: preferred side, then the other side, then above, then below.
 * A place counts only if the word stays inside the view and touches no mark and no other label. This changes where words
 * are drawn; it never changes the line, the band or any check.
 */
object WalkLineLabels {

    /** A rectangle in view pixels (y pointing down). */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top

        fun overlaps(other: Box): Boolean =
            left < other.right && other.left < right && top < other.bottom && other.top < bottom

        fun insideView(viewWidth: Float, viewHeight: Float, edge: Float): Boolean =
            left >= edge && top >= edge && right <= viewWidth - edge && bottom <= viewHeight - edge
    }

    /** The square (or diamond's bounding square) a mark occupies, [half] from its centre on every side. */
    fun markBox(x: Float, y: Float, half: Float): Box = Box(x - half, y - half, x + half, y + half)

    /**
     * The box for a word of [textWidth] x [textHeight] beside the mark at ([markX], [markY]) whose half-size is [markHalf].
     * [avoid] = every other mark and label already placed. [preferRight] = the side with (usually) more room.
     * If nowhere is free (a tiny view), the above-position pushed inside the view is returned: still readable, and the
     * only case where a word can touch something.
     */
    fun place(
        markX: Float, markY: Float, markHalf: Float,
        textWidth: Float, textHeight: Float,
        viewWidth: Float, viewHeight: Float,
        preferRight: Boolean, gap: Float, edge: Float,
        avoid: List<Box> = emptyList(),
        aboveFirst: Boolean = false
    ): Box {
        val own = markBox(markX, markY, markHalf)
        val verticalMiddle = markY - textHeight / 2f
        val right = Box(markX + markHalf + gap, verticalMiddle, markX + markHalf + gap + textWidth, verticalMiddle + textHeight)
        val left = Box(markX - markHalf - gap - textWidth, verticalMiddle, markX - markHalf - gap, verticalMiddle + textHeight)
        val centredLeft = markX - textWidth / 2f
        val above = Box(centredLeft, markY - markHalf - gap - textHeight, centredLeft + textWidth, markY - markHalf - gap)
        val below = Box(centredLeft, markY + markHalf + gap, centredLeft + textWidth, markY + markHalf + gap + textHeight)

        val order = when {
            aboveFirst -> if (preferRight) listOf(above, below, right, left) else listOf(above, below, left, right)
            preferRight -> listOf(right, left, above, below)
            else -> listOf(left, right, above, below)
        }
        val free = order.firstOrNull { box ->
            box.insideView(viewWidth, viewHeight, edge) && !box.overlaps(own) && avoid.none { it.overlaps(box) }
        }
        if (free != null) return free

        // Nowhere is free: keep the word readable inside the view, above the mark.
        val x = above.left.coerceIn(edge, (viewWidth - edge - textWidth).coerceAtLeast(edge))
        val y = above.top.coerceIn(edge, (viewHeight - edge - textHeight).coerceAtLeast(edge))
        return Box(x, y, x + textWidth, y + textHeight)
    }
}
