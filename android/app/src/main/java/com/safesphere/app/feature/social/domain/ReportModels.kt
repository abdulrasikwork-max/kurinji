package com.safesphere.app.feature.social.domain

import com.safesphere.app.core.network.ApiResult
import java.time.Instant

/**
 * Phase 13D: moderation as a member sees it (docs/PHASE13D_PLAN.md). Nothing here ever names a reporter or carries a
 * count: a reporter sees their own reports; the person concerned sees what a moderator decided about them or their
 * content, with the reason, and may ask for a review. Nothing is stored on the phone.
 */

/** What can be reported. */
enum class ReportTarget { POST, COMMENT, PERSON }

/** Why: the closed list of the server (§7.3). IMPERSONATION fits people only; OTHER needs a short note. */
enum class ReportReason {
    HARASSMENT, SEXUAL_HARASSMENT, HATE, THREAT, DOXXING, SPAM, NUDITY_OR_VIOLENCE, IMPERSONATION, OTHER;

    companion object {
        /** The reasons offered for [target], in the order shown. */
        fun forTarget(target: ReportTarget): List<ReportReason> =
            if (target == ReportTarget.PERSON) entries else entries.filter { it != IMPERSONATION }

        const val NOTE_MAX = 200

        /** The server's rule, mirrored: the reason fits the target, and a note of 1-200 characters exactly for OTHER. */
        fun accepts(target: ReportTarget, reason: ReportReason, note: String?): Boolean {
            if (reason !in forTarget(target)) return false
            val text = note?.trim().orEmpty()
            return if (reason == OTHER) text.isNotEmpty() && text.codePointCount(0, text.length) <= NOTE_MAX
            else text.isEmpty()
        }
    }
}

/**
 * A moderator's reason: a report reason, or "needs a closer look" (a hold while reviewing). Phase 13E: AWAITING_CHECK =
 * a new account's content waits for the automatic check (at most 2 hours).
 */
enum class ModerationReason {
    HARASSMENT, SEXUAL_HARASSMENT, HATE, THREAT, DOXXING, SPAM, NUDITY_OR_VIOLENCE, IMPERSONATION, OTHER, NEEDS_REVIEW,
    AWAITING_CHECK
}

/** The state of one's OWN post or perspective (NONE is null). */
enum class ModerationState { COLLAPSED, HELD, REMOVED }

/** Phase 13E: who set it. Unknown reads as MODERATOR (the most careful words, without a flagged phrase). */
enum class ModerationSource { REPORTS, SYSTEM, MODERATOR }

/**
 * What everyone else learns about a collapsed post or perspective: why it is behind a tap, never who. Phase 13E:
 * BY_SYSTEM = the automatic check thinks it may be hurtful.
 */
enum class Collapsed { UNDER_REVIEW, BY_MODERATOR, BY_SYSTEM }

/**
 * What happened to the viewer's own content. [reason]/[decisionId] are null for a collapse while it is reviewed.
 * Phase 13E: [source], and for the automatic check the [flagged] phrases (code points of the text as written: the body,
 * a new line and the tip; or the perspective).
 */
data class OwnModeration(
    val state: ModerationState,
    val reason: ModerationReason?,
    val decisionId: String?,
    val source: ModerationSource? = null,
    val flagged: List<IntRange> = emptyList()
) {
    /** Phase 13E: set by the automatic check (not by people). */
    val bySystem: Boolean get() = source == ModerationSource.SYSTEM

    /** Phase 13E: the author may ask a moderator to look at an automatic collapse (a hold is looked at anyway). */
    val canAskCheckReview: Boolean get() = bySystem && state == ModerationState.COLLAPSED
}

/** One of the caller's own reports. [actionTaken] only once a moderator reviewed it. */
data class MyReport(
    val reportId: String,
    val target: ReportTarget,
    val reason: ReportReason,
    val reviewed: Boolean,
    val actionTaken: Boolean?,
    val period: TimeBand
)

enum class PauseLevel { COMMENTS_PAUSED, POSTING_PAUSED, SUSPENDED }

enum class AppealState { NONE, OPEN, UPHELD, OVERTURNED }

enum class DecisionAction {
    CONTENT_HOLD, CONTENT_COLLAPSE, CONTENT_REMOVE, PERSON_WARN, PERSON_PAUSE_COMMENTS, PERSON_PAUSE_POSTING,
    PERSON_SUSPEND
}

/** The pause in force for the caller. [until] is their own information (shown as a date). */
data class SocialPause(
    val level: PauseLevel,
    val until: Instant,
    val reason: ModerationReason?,
    val decisionId: String,
    val appeal: AppealState,
    val canAppeal: Boolean
)

/** A moderator's decision about the caller or their content (last 90 days). */
data class ModerationDecision(
    val decisionId: String,
    val action: DecisionAction,
    val reason: ModerationReason?,
    val targetId: String?,
    val period: TimeBand,
    val appeal: AppealState,
    val canAppeal: Boolean
)

data class SocialStanding(val pause: SocialPause?, val decisions: List<ModerationDecision>) {
    /** The decision behind the current state of the caller's post or perspective [targetId], if any. */
    fun decisionFor(decisionId: String?): ModerationDecision? =
        decisionId?.let { id -> decisions.firstOrNull { it.decisionId == id } }
}

/** Phase 13D: reports, the caller's standing and appeals on the backend. */
interface ReportRepository {

    /** Always the same answer for a target the caller can see, also when they had reported it already. */
    suspend fun report(target: ReportTarget, targetId: String, reason: ReportReason, note: String?): ApiResult<Unit>

    suspend fun myReports(): ApiResult<List<MyReport>>

    suspend fun standing(): ApiResult<SocialStanding>

    suspend fun appeal(decisionId: String): ApiResult<Unit>

    /** Phase 13E: a moderator looks at one's own post or perspective [target] that the automatic check collapsed. */
    suspend fun askCheckReview(target: ReportTarget, targetId: String): ApiResult<Unit>
}
