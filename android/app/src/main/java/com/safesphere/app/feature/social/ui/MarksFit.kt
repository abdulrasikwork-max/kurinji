package com.safesphere.app.feature.social.ui

/**
 * Phase 17E: how much of a feed card's marks bar (butterfly, comments, Why?, Save) fits on one row. The row never wraps,
 * so on a narrow screen or with large text the words go first, never a button: found on a 320 dp screen at 2.0x text,
 * where "Comment" broke mid-word and Save was pushed off the card.
 *
 * The budget is the screen width in dp divided by the text scale (how many "1x" dp of words fit). Each button keeps its
 * spoken description in every mode, so TalkBack users lose nothing.
 */
object MarksFit {

    enum class Mode {
        /** Every mark with its word ("Inspired", "Comment", "Why?", "Save"). */
        FULL,

        /** The comment button shows only its icon (and the number when there are comments). */
        SHORT,

        /** Icons only; Why? becomes the info icon. */
        ICONS
    }

    /** At least this budget shows every word (a 412 dp phone at 1.0x). */
    const val FULL_FROM = 400f

    /** At least this budget keeps the words of the butterfly and Save (a 360 dp phone up to ~1.1x). */
    const val SHORT_FROM = 320f

    fun mode(screenWidthDp: Int, fontScale: Float): Mode {
        val budget = screenWidthDp / fontScale.coerceAtLeast(MIN_SCALE)
        return when {
            budget >= FULL_FROM -> Mode.FULL
            budget >= SHORT_FROM -> Mode.SHORT
            else -> Mode.ICONS
        }
    }

    /** Smaller text gives more room, but the gain is capped (scales below this count as this). */
    private const val MIN_SCALE = 0.85f
}
