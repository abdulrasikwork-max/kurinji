package com.safesphere.app.feature.social.domain

import java.time.YearMonth

/** What stops a draft from being shared, in the order the composer reports them. */
enum class ComposerIssue {
    BODY_EMPTY, BODY_TOO_LONG, ASK_TOO_SHORT, ASK_TOO_LONG, TIP_TOO_LONG, NO_MONTH, MONTH_IN_FUTURE, NO_PRECISION,
    NO_PLACE, NO_CITY, PRIVATE_PLACE, NO_AUDIENCE, TOO_MANY_PHOTOS
}

/**
 * Phase 13B: the composer's rules, mirroring the server's PostPolicy (the server checks again; it is the authority).
 * Pure. Lengths count characters (code points), as the server does.
 *
 *   Experience: text 1-5000, tip up to 300, the month it happened (not in the future).
 *   Ask: a question of 10-1000 characters, no tip, no month.
 *   Where: EXACT / AREA need a place that is not near a private place; CITY needs a city; NONE needs nothing.
 *   Who can see it: always chosen by the person (no preselection on the first post, D14).
 */
object ComposerPolicy {

    const val EXPERIENCE_BODY_MAX = 5000
    const val ASK_BODY_MIN = 10
    const val ASK_BODY_MAX = 1000
    const val TIP_MAX = 300
    const val MAX_PHOTOS = 6

    /** How many months back the "when" picker offers (older experiences are rarely useful to the next person). */
    const val MONTHS_OFFERED = 24

    fun issues(draft: PostDraft, currentMonth: YearMonth, placeIsPrivate: Boolean): List<ComposerIssue> {
        val issues = mutableListOf<ComposerIssue>()
        val length = length(draft.body.trim())
        if (draft.type == PostType.EXPERIENCE) {
            if (length < 1) issues += ComposerIssue.BODY_EMPTY
            if (length > EXPERIENCE_BODY_MAX) issues += ComposerIssue.BODY_TOO_LONG
            if (length(draft.tip.trim()) > TIP_MAX) issues += ComposerIssue.TIP_TOO_LONG
            val month = draft.month
            if (month == null) issues += ComposerIssue.NO_MONTH
            else if (month.isAfter(currentMonth)) issues += ComposerIssue.MONTH_IN_FUTURE
        } else {
            if (length < ASK_BODY_MIN) issues += ComposerIssue.ASK_TOO_SHORT
            if (length > ASK_BODY_MAX) issues += ComposerIssue.ASK_TOO_LONG
        }
        when (draft.precision) {
            null -> issues += ComposerIssue.NO_PRECISION
            Precision.EXACT, Precision.AREA -> when {
                draft.place == null -> issues += ComposerIssue.NO_PLACE
                placeIsPrivate -> issues += ComposerIssue.PRIVATE_PLACE
            }
            Precision.CITY -> if (draft.city == null) issues += ComposerIssue.NO_CITY
            Precision.NONE -> Unit
        }
        if (draft.audience == null) issues += ComposerIssue.NO_AUDIENCE
        if (draft.mediaIds.size > MAX_PHOTOS) issues += ComposerIssue.TOO_MANY_PHOTOS
        return issues
    }

    /** Which precisions can be chosen: the place ones only with a place that is not near a private place. */
    fun allowedPrecisions(place: Place?, placeIsPrivate: Boolean): List<Precision> =
        if (place != null && !placeIsPrivate) Precision.entries.toList() else listOf(Precision.CITY, Precision.NONE)

    /** An exact-place post reaches others a random 1-3 hours later (Q1); the composer says so before sharing. */
    fun isDelayed(precision: Precision?): Boolean = precision == Precision.EXACT

    /** Only me never takes perspectives. */
    fun effectiveCommentPolicy(audience: Audience?, chosen: CommentPolicy): CommentPolicy =
        if (audience == Audience.ONLY_ME) CommentPolicy.NOBODY else chosen

    /** The months offered by the "when" picker, newest first. */
    fun monthsOffered(currentMonth: YearMonth): List<YearMonth> =
        (0 until MONTHS_OFFERED).map { currentMonth.minusMonths(it.toLong()) }

    fun length(text: String): Int = text.codePointCount(0, text.length)
}
