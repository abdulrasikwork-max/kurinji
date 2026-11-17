package com.safesphere.app.feature.moments.domain

import java.time.Instant

/**
 * Phase N4: moments, as the app sees them (docs/PHASE_N4.md). No model carries a count of anything, a place, a
 * distance, who looked, or (for anyone but the author) the exact time a moment was shared: other people get a rough
 * time only. Nothing here is stored on the phone.
 */

/** A Say is something on your mind; a Who's in? turns it into a plan. */
enum class MomentKind { SAY, WHOS_IN }

/** Who can see a moment: the whole community, or the author's mutual connections ("My people"). */
enum class MomentAudience { COMMUNITY, MY_PEOPLE }

/**
 * LIVE = on Today now; ENDED = its 24 hours are over (others reach it only while it is kept); WAITING = nobody else
 * can see it yet (only its author is ever told this).
 */
enum class MomentStatus { LIVE, ENDED, WAITING }

enum class RoughDay { TODAY, YESTERDAY, THIS_WEEK, THIS_MONTH, EARLIER }

enum class DayPart { MORNING, AFTERNOON, EVENING, NIGHT }

/** When a moment was shared, roughly (India time). [part] only for today and yesterday. */
data class RoughTime(val day: RoughDay, val part: DayPart?)

/** The author: the account's real name; the rest is null for someone who has not made a profile. */
data class MomentAuthor(val profileId: String?, val handle: String?, val name: String, val photo: String?)

/** The size of a moment's photo, so a card can be laid out before the picture arrives. */
data class MomentPhoto(val width: Int, val height: Int) {
    /** Height over width, kept between a wide strip and a tall portrait so one photo cannot fill the wall. */
    val ratio: Float get() = (height.toFloat() / width.toFloat()).coerceIn(MIN_RATIO, MAX_RATIO)

    companion object {
        const val MIN_RATIO = 0.5f
        const val MAX_RATIO = 1.25f
    }
}

/** The plan of a Who's in?: the time exactly as the author set it (may be null) and "where" in their own words. */
data class MomentPlan(val at: Instant?, val where: String?)

/** The daily question: pinned on Today, or the one a moment answers. */
data class DailyQuestion(val id: String, val text: String)

enum class NoticeState { COLLAPSED, HELD, REMOVED }

/**
 * What the AUTHOR is told about their own moment (no hidden states). [reason] is the server's code (a guideline,
 * NEEDS_REVIEW, AWAITING_CHECK or NEW_MEMBER_PHOTO) or null for a collapse by reports; [byPerson] = a moderator
 * decided (false = a rule, null = not said); [reviewable] = the author may ask a moderator to look; [decisionId] = a
 * moderator's decision that can be appealed from "Your standing".
 */
data class MomentNotice(
    val state: NoticeState,
    val reason: String?,
    val byPerson: Boolean?,
    val reviewable: Boolean,
    val decisionId: String?
)

data class Moment(
    val id: String,
    val kind: MomentKind,
    val audience: MomentAudience,
    val author: MomentAuthor,
    val mine: Boolean,
    val text: String?,
    val photo: MomentPhoto?,
    val plan: MomentPlan?,
    val question: DailyQuestion?,
    /** Null while nobody else can see the moment yet. */
    val shared: RoughTime?,
    val status: MomentStatus,
    val kept: Boolean,
    /** For other people: shown behind a tap. */
    val collapsed: Boolean,
    /** Only ever set on the author's own moment. */
    val notice: MomentNotice?,
    /** Only in the author's own answer to sharing: one calm support line, never stored. */
    val supportSuggested: Boolean,
    /** The exact times: the author's own moments only. */
    val visibleAt: Instant?,
    val expiresAt: Instant?,
    /** N5: Same, Say more, the follow-up, I'm in, private replies (never a number). */
    val responses: MomentResponses = MomentResponses.NONE,
    /** N5 (N-92): the plan this Say says happened. */
    val happenedOf: HappenedRef? = null
) {
    /** Others cannot see it right now (waiting from the start, or hidden while someone looks at it). */
    val hiddenFromOthers: Boolean
        get() = status == MomentStatus.WAITING || notice?.state == NoticeState.HELD || notice?.state == NoticeState.REMOVED
}

/** Whose moments Today shows: everyone the viewer may see, or their connections only. */
enum class People { ALL, MINE }

/** One page of Today. [nextCursor] null = the wall ends here. */
data class TodayPage(val question: DailyQuestion?, val moments: List<Moment>, val nextCursor: String?)

/** The caller's own moments: the ones that have not ended, and the ones kept on the profile (at most [keptMax]). */
data class MyMoments(val current: List<Moment>, val kept: List<Moment>, val keptMax: Int)

/** Someone's moments the viewer may see: live now, and the ones they keep. */
data class ProfileMoments(val live: List<Moment>, val kept: List<Moment>)
