package com.safesphere.app.feature.moderation.domain

import com.safesphere.app.core.network.ApiResult
import java.time.Instant
import java.time.LocalDate

/**
 * Phase 13D: the moderators' view of reported content and people (docs/PHASE13D_PLAN.md M8-M12; N4-A3: moments). People are
 * case pseudonyms ("Person 3f9a1c…"); the server decides every check again and answers 403 for a case the moderator is
 * part of. Nothing is stored on the phone.
 */

/** N5: MESSAGE = a message in a plan or a private reply (seen with its context only, never browsed). */
enum class ContentTarget { MOMENT, PERSON, MESSAGE }

/** The decisions of the server, in the order a moderator reads them. */
enum class ContentAction {
    CONTENT_KEEP, CONTENT_HOLD, CONTENT_COLLAPSE, CONTENT_REMOVE,
    PERSON_KEEP, PERSON_WARN, PERSON_PAUSE_COMMENTS, PERSON_PAUSE_POSTING, PERSON_SUSPEND,
    /** Phase 17E: remove the person's profile photo (shown to them; not a strike; the case stays open). */
    PERSON_REMOVE_PHOTO;

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
    /** COLLAPSED / HELD / REMOVED / NONE for a moment; null for a person. */
    val contentState: String?,
    val youTookPart: Boolean,
    /** Phase 13E: REPORTS / SYSTEM / AUTHOR_REQUEST; N4: ABOUT_ME (unknown reads as REPORTS: the 13D words). */
    val openedBy: String = "REPORTS",
    /** Phase 13E: who set the content's state (SYSTEM = the automatic check); null while NONE or for a person. */
    val contentSource: String? = null,
    /**
     * N4: the server's top reason as a code, also the ones that are no decision reason (ABOUT_ME, NEW_MEMBER_PHOTO,
     * AWAITING_CHECK); null when unknown. [topReason] stays the decision-reason view of it.
     */
    val topReasonCode: String? = null,
    /** N4 (N-69): whole hours the case has waited (shown so the oldest are not forgotten). */
    val waitingHours: Int = 0
)

/** Phase 13E: one finding of the automatic check, with the flagged phrase as code points of the case's text. */
data class CheckFinding(val category: String, val severity: Int, val targeted: Boolean, val quoted: Boolean,
                        val span: IntRange?)

/** Phase 13E: what the automatic check found (evidence, not a verdict). */
data class CaseCheck(val action: String, val rule: String, val findings: List<CheckFinding>, val versions: String)

/** The public profile of a PERSON case (N4: programme and year from the membership; the photo only after a tap). */
data class PersonInfo(val displayName: String, val handle: String, val bio: String?, val programme: String?,
                      val graduationYear: Int?, /** Phase 17E: shown only after a tap. */ val hasPhoto: Boolean = false)

/** N4: the size of the moment's photo (the picture: only after a tap, through the case). */
data class CasePhoto(val width: Int, val height: Int)

/**
 * N4: what a MOMENT case is about, as written (also a My people one: needed to judge): [kind] SAY / WHOS_IN,
 * [audience] COMMUNITY / MY_PEOPLE, the words, a Who's in?'s plan, the daily question it answers, its photo. A PERSON
 * case has only [person].
 */
data class CaseContent(
    val kind: String? = null,
    val audience: String? = null,
    val body: String? = null,
    val planAt: Instant? = null,
    val planWhere: String? = null,
    val question: String? = null,
    val photo: CasePhoto? = null,
    val person: PersonInfo? = null,
    /** N5: a moment's follow-up ("say more"), part of its words. */
    val more: String? = null,
    /** N5 (N-97): where a MESSAGE was written. */
    val conversation: CaseConversation? = null
)

/**
 * N5 (N-97): a message case's context. [kind] PLAN / REPLY; [lines] up to 10 earlier messages and the reported one
 * (pseudonyms, oldest first), or the held message alone; [connected] for a held private reply: whether the two are
 * connected (null otherwise).
 */
data class CaseConversation(val kind: String?, val lines: List<CaseLine>, val connected: Boolean?)

data class CaseLine(val ref: String, val text: String, val reported: Boolean)

enum class CaseRole { AUTHOR, SUBJECT, REPORTER }

data class ContentParticipant(
    val ref: String,
    val role: CaseRole,
    val reason: ContentDecisionReason?,
    val note: String?,
    val counting: Boolean,
    val accountAge: String?,
    val linkGroup: String?,
    /** N4: this person said the moment is about them ("This is about me"). */
    val aboutMe: Boolean = false
)

data class CaseAuthor(
    val ref: String,
    val strikePoints: Double,
    /** WARN / PAUSE_COMMENTS / PAUSE_POSTING / SUSPEND (a suggestion only). */
    val suggestedStep: ContentAction?,
    val restriction: String?,
    val restrictedUntil: Instant?,
    /** N4 (N-56): the end of their 7-day new-member period; null once established. */
    val newMemberUntil: Instant? = null
)

data class CaseDecisionEntry(val action: ContentAction, val reason: ContentDecisionReason?, val decidedOn: LocalDate?,
                             val byYou: Boolean)

data class ContentCaseDetail(
    val summary: ContentCaseSummary,
    val content: CaseContent,
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

    /** N4: the address of the case's moment photo (loaded only after a tap, D15). */
    fun photoUrl(caseId: String): String


    /** Phase 17E: the profile photo of the person a PERSON case is about. */
    fun personPhotoUrl(caseId: String): String

}
