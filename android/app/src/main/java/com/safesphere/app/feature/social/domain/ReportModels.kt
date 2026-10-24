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

/** A moderator's reason: a report reason, or "needs a closer look" (a hold while reviewing). */
enum class ModerationReason {
    HARASSMENT, SEXUAL_HARASSMENT, HATE, THREAT, DOXXING, SPAM, NUDITY_OR_VIOLENCE, IMPERSONATION, OTHER, NEEDS_REVIEW
}

/** The state of one's OWN post or perspective (NONE is null). */
enum class ModerationState { COLLAPSED, HELD, REMOVED }

/** What everyone else learns about a collapsed post or perspective: why it is behind a tap, never who. */
enum class Collapsed { UNDER_REVIEW, BY_MODERATOR }

/** What happened to the viewer's own content. [reason]/[decisionId] are null for a collapse while it is reviewed. */
data class OwnModeration(val state: ModerationState, val reason: ModerationReason?, val decisionId: String?)

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
}
