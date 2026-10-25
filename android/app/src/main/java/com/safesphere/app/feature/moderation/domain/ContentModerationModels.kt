package com.safesphere.app.feature.moderation.domain

import com.safesphere.app.core.network.ApiResult
import java.time.Instant
import java.time.LocalDate

/**
 * Phase 13D: the moderators' view of reported posts, perspectives and people (docs/PHASE13D_PLAN.md M8-M12). People are
 * case pseudonyms ("Person 3f9a1c…"); the server decides every check again and answers 403 for a case the moderator is
 * part of. Nothing is stored on the phone.
 */

enum class ContentTarget { POST, COMMENT, PERSON }

/** The decisions of the server, in the order a moderator reads them. */
enum class ContentAction {
    CONTENT_KEEP, CONTENT_HOLD, CONTENT_COLLAPSE, CONTENT_REMOVE,
    PERSON_KEEP, PERSON_WARN, PERSON_PAUSE_COMMENTS, PERSON_PAUSE_POSTING, PERSON_SUSPEND;

    val aboutPerson: Boolean get() = name.startsWith("PERSON_")
}

/** Reason codes of a decision (no free text). */
enum class ContentDecisionReason {
    NO_VIOLATION, NEEDS_REVIEW,
    HARASSMENT, SEXUAL_HARASSMENT, HATE, THREAT, DOXXING, SPAM, NUDITY_OR_VIOLENCE, IMPERSONATION, OTHER
}

data class ContentCaseSummary(
    val caseId: String,
    val target: ContentTarget,
    val open: Boolean,
    /** Closed cases only: true = a rule was broken. */
    val actionTaken: Boolean?,
    val openedOn: LocalDate?,
    val topReason: ContentDecisionReason?,
    val reasons: Map<ContentDecisionReason, Int>,
    val counting: Int,
    val independent: Int,
    /** COLLAPSED / HELD / REMOVED / NONE for posts and perspectives; null for a person. */
    val contentState: String?,
    val youTookPart: Boolean,
    /** Phase 13E: REPORTS / SYSTEM / AUTHOR_REQUEST (unknown reads as REPORTS: the 13D words). */
    val openedBy: String = "REPORTS",
    /** Phase 13E: who set the content's state (SYSTEM = the automatic check); null while NONE or for a person. */
    val contentSource: String? = null
)

/** Phase 13E: one finding of the automatic check, with the flagged phrase as code points of the case's text. */
data class CheckFinding(val category: String, val severity: Int, val targeted: Boolean, val quoted: Boolean,
                        val span: IntRange?)

/** Phase 13E: what the automatic check found (evidence, not a verdict). */
data class CaseCheck(val action: String, val rule: String, val findings: List<CheckFinding>, val versions: String)

data class PersonInfo(val displayName: String, val handle: String, val bio: String?, val city: String?,
                      val interests: List<String>)

data class CaseMedia(val mediaId: String, val width: Int, val height: Int)

data class CaseContent(
    val postType: String?,
    val commentKind: String?,
    val body: String?,
    val tip: String?,
    val category: String?,
    val precision: String?,
    val placeName: String?,
    val areaCell: String?,
    val city: String?,
    val audience: String?,
    val experiencedMonth: String?,
    val daypart: String?,
    val media: List<CaseMedia>,
    /** A perspective's post, at most 500 characters. */
    val contextBody: String?,
    val person: PersonInfo?
)

data class CaseRevision(val body: String, val tip: String?, val replacedOn: LocalDate?)

enum class CaseRole { AUTHOR, SUBJECT, REPORTER }

data class ContentParticipant(
    val ref: String,
    val role: CaseRole,
    val reason: ContentDecisionReason?,
    val note: String?,
    val counting: Boolean,
    val accountAge: String?,
    val linkGroup: String?
)

data class CaseAuthor(
    val ref: String,
    val strikePoints: Double,
    /** WARN / PAUSE_COMMENTS / PAUSE_POSTING / SUSPEND (a suggestion only). */
    val suggestedStep: ContentAction?,
    val restriction: String?,
    val restrictedUntil: Instant?
)

data class CaseDecisionEntry(val action: ContentAction, val reason: ContentDecisionReason?, val decidedOn: LocalDate?,
                             val byYou: Boolean)

data class ContentCaseDetail(
    val summary: ContentCaseSummary,
    val content: CaseContent,
    val revisions: List<CaseRevision>,
    val participants: List<ContentParticipant>,
    val author: CaseAuthor?,
    val possibleActions: List<ContentAction>,
    val decisions: List<CaseDecisionEntry>,
    /** Phase 13E: null when the check never ran on it (or it is a person case). */
    val check: CaseCheck? = null
)

data class ContentAppeal(
    val appealId: String,
    val open: Boolean,
    val createdOn: LocalDate?,
    val action: ContentAction,
    val reason: ContentDecisionReason?,
    val target: ContentTarget?,
    val caseId: String?,
    /** The moderator who made the decision may not decide its appeal (the server answers 403). */
    val youDecided: Boolean
)

interface ContentModerationRepository {

    /** Open cases, highest priority first. */
    suspend fun openCases(): ApiResult<List<ContentCaseSummary>>

    suspend fun caseDetail(caseId: String): ApiResult<ContentCaseDetail>

    suspend fun act(caseId: String, action: ContentAction, reason: ContentDecisionReason): ApiResult<ContentCaseDetail>

    suspend fun openAppeals(): ApiResult<List<ContentAppeal>>

    suspend fun decideAppeal(appealId: String, overturn: Boolean): ApiResult<ContentAppeal>

    /** The address of a case photo (loaded only after a tap, D15). */
    fun photoUrl(caseId: String, mediaId: String): String
}
