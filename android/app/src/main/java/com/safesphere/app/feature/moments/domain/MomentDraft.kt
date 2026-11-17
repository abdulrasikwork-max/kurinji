package com.safesphere.app.feature.moments.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Phase N4 (A2): what Create sends. [text] may be empty only next to a photo; [planAt] and [planWhere] only on a
 * Who's in?; [questionId] = today's question when this answers it. The server checks every rule again and decides.
 */
data class MomentDraft(
    val kind: MomentKind,
    val audience: MomentAudience,
    val text: String,
    val mediaId: String?,
    val planAt: Instant?,
    val planWhere: String?,
    val questionId: String?,
    /** N5 (N-92): "This happened": the Who's in? it comes from (a Say with a photo, the plan's audience). */
    val happenedOf: String? = null
) {
    override fun toString() = "MomentDraft(kind=$kind, audience=$audience, photo=${mediaId != null}, happened=${happenedOf != null}, <words hidden>)"
}

/** A photo the server has taken in (re-encoded, without metadata), not yet part of a moment. */
data class UploadedPhoto(val mediaId: String, val width: Int, val height: Int)

/**
 * What Create needs to know before anything is written: whether the person is a member (else "Get verified"), and the
 * end of their new-member period (null once established or when unknown). Information only: the server decides.
 */
data class SharingStatus(val member: Boolean, val newMemberUntil: Instant?)

/** The last audience chosen on THIS phone, per account (D5: no default the first time). */
interface AudienceMemory {
    suspend fun last(): MomentAudience?
    suspend fun remember(audience: MomentAudience)
}

/**
 * Phase N4 (A2): the rules Create shows before sending (PURE, unit tested). They mirror the server's MomentPolicy so
 * the person learns them while writing; the server's answer is still the one that counts.
 */
object DraftRules {

    /** D4: a moment has at most 500 characters, counted as people count them (Tamil letters, emoji: one each). */
    const val MAX_TEXT = 500

    /** D9: "where" is one line of at most 60 characters. */
    const val MAX_WHERE = 60

    /** The room left is shown to the writer once this little is left (a calm screen until then). */
    const val SHOW_ROOM_FROM = 100

    /** M2 (locked): the new-member period lasts 7 days from the membership's approval (the server computes its own). */
    val NEW_MEMBER_PERIOD: Duration = Duration.ofDays(7)

    /** Characters as the server counts them: Unicode code points of the trimmed text. */
    fun length(text: String): Int {
        val trimmed = text.trim()
        return trimmed.codePointCount(0, trimmed.length)
    }

    /** Room left (negative = over); null while there is plenty (more than [SHOW_ROOM_FROM]). */
    fun roomLeft(text: String): Int? {
        val left = MAX_TEXT - length(text)
        return if (left > SHOW_ROOM_FROM) null else left
    }

    /** Words fit: 1 to 500 characters, or none at all next to a photo. */
    fun wordsFit(text: String, hasPhoto: Boolean): Boolean {
        val length = length(text)
        return length <= MAX_TEXT && (length > 0 || hasPhoto)
    }

    fun whereFits(where: String): Boolean {
        val trimmed = where.trim()
        return !trimmed.contains('\n') && trimmed.codePointCount(0, trimmed.length) <= MAX_WHERE
    }

    /**
     * D9: the plan's time is a clock time the person picks; it means its next occurrence within the next 24 hours
     * (later today, otherwise tomorrow), in the phone's zone, to the minute. Always after [now], never more than 24 h.
     */
    fun nextPlanTime(time: LocalTime, now: Instant, zone: ZoneId): Instant {
        val minute = time.truncatedTo(ChronoUnit.MINUTES)
        val today = now.atZone(zone).toLocalDate().atTime(minute).atZone(zone).toInstant()
        return if (today.isAfter(now)) today else now.atZone(zone).toLocalDate().plusDays(1).atTime(minute).atZone(zone).toInstant()
    }

    /** The end of a new-member period from the approval time, or null when it has ended (or is unknown). */
    fun newMemberUntil(verifiedAt: Instant?, now: Instant): Instant? =
        verifiedAt?.plus(NEW_MEMBER_PERIOD)?.takeIf { it.isAfter(now) }
}
