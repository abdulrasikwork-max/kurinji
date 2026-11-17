package com.safesphere.app.feature.moderation.data.remote

import com.safesphere.app.feature.moderation.domain.CaseAuthor
import com.safesphere.app.feature.moderation.domain.CaseCheck
import com.safesphere.app.feature.moderation.domain.CaseContent
import com.safesphere.app.feature.moderation.domain.CaseDecisionEntry
import com.safesphere.app.feature.moderation.domain.CasePhoto
import com.safesphere.app.feature.moderation.domain.ContentParticipant
import com.safesphere.app.feature.moderation.domain.CaseRole
import com.safesphere.app.feature.moderation.domain.CheckFinding
import com.safesphere.app.feature.moderation.domain.ContentAction
import com.safesphere.app.feature.moderation.domain.ContentAppeal
import com.safesphere.app.feature.moderation.domain.ContentCaseDetail
import com.safesphere.app.feature.moderation.domain.ContentCaseSummary
import com.safesphere.app.feature.moderation.domain.ContentDecisionReason
import com.safesphere.app.feature.moderation.domain.ContentTarget
import com.safesphere.app.feature.moderation.domain.CaseConversation
import com.safesphere.app.feature.moderation.domain.CaseLine
import com.safesphere.app.feature.moderation.domain.PersonInfo
import java.time.Instant
import java.time.LocalDate
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Phase 13D: the moderators' content queue (server: MODERATOR / ADMIN role read from its database on every call). */
interface ContentModerationApi {

    @GET("api/moderation/content-cases")
    suspend fun cases(@Query("status") status: String): List<ContentCaseSummaryDto>

    @GET("api/moderation/content-cases/{id}")
    suspend fun caseDetail(@Path("id") caseId: String): ContentCaseDetailDto

    @POST("api/moderation/content-cases/{id}/actions")
    suspend fun act(@Path("id") caseId: String, @Body body: ContentActionDto): ContentCaseDetailDto

    @GET("api/moderation/content-appeals")
    suspend fun appeals(): List<ContentAppealDto>

    @POST("api/moderation/content-appeals/{id}/decision")
    suspend fun decide(@Path("id") appealId: String, @Body body: AppealDecisionDto): ContentAppealDto
}

// FAIL CLOSED: an unknown target or appealed action drops the item; unknown possible actions are dropped (never offered);
// "you took part" defaults to true (the case is then read-only); unknown reasons read as none. Pseudonyms and notes are
// redacted in toString().

private inline fun <reified T : Enum<T>> codeOf(name: String?): T? =
    if (name == null) null else enumValues<T>().firstOrNull { it.name == name }

private fun dateOf(value: String?): LocalDate? = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

data class ContentActionDto(val action: String, val reason: String)

data class ContentCaseSummaryDto(
    val caseId: String? = null,
    val targetKind: String? = null,
    val status: String? = null,
    val outcome: String? = null,
    val openedOn: String? = null,
    val topReason: String? = null,
    val reasons: Map<String, Int>? = null,
    val counting: Int? = null,
    val independent: Int? = null,
    val contentState: String? = null,
    val priority: Int? = null,
    val youTookPart: Boolean? = null,
    val openedBy: String? = null,
    val contentSource: String? = null,
    val waitingHours: Int? = null
) {
    fun toDomain(): ContentCaseSummary? {
        val reasonCounts = buildMap {
            reasons.orEmpty().forEach { (k, v) -> codeOf<ContentDecisionReason>(k)?.let { put(it, v) } }
        }
        return ContentCaseSummary(
            caseId = caseId ?: return null,
            target = codeOf<ContentTarget>(targetKind) ?: return null,
            open = status == "OPEN",
            actionTaken = when (outcome) {
                "ACTION_TAKEN" -> true
                "NO_VIOLATION" -> false
                else -> null
            },
            openedOn = dateOf(openedOn),
            topReason = codeOf<ContentDecisionReason>(topReason),
            reasons = reasonCounts,
            counting = counting ?: 0,
            independent = independent ?: 0,
            contentState = contentState,
            youTookPart = youTookPart != false,
            openedBy = openedBy?.takeIf { it in OPENED_BY } ?: "REPORTS",
            contentSource = contentSource?.takeIf { it in setOf("REPORTS", "SYSTEM", "MODERATOR") },
            topReasonCode = topReason?.takeIf { it in REASON_CODES },
            waitingHours = (waitingHours ?: 0).coerceAtLeast(0)
        )
    }
}

/** Phase 13E: the automatic check, as moderators see it. Unknown categories are dropped; a bad span is no span. */
data class AssessmentViewDto(
    val status: String? = null,
    val action: String? = null,
    val rule: String? = null,
    val evidence: List<EvidenceViewDto>? = null,
    val normaliserVersion: String? = null,
    val lexiconVersion: String? = null,
    val modelVersion: String? = null,
    val policyVersion: String? = null
) {
    fun toDomain(): CaseCheck? {
        val a = action ?: return null
        val r = rule ?: return null
        return CaseCheck(a, r, evidence.orEmpty().mapNotNull { it.toDomain() },
            listOfNotNull(normaliserVersion, lexiconVersion, modelVersion?.takeIf { it != "-" }, policyVersion)
                .joinToString(", "))
    }
}

data class EvidenceViewDto(val category: String? = null, val severity: Int? = null, val targeted: Boolean? = null,
                           val quoted: Boolean? = null, val start: Int? = null, val end: Int? = null) {
    fun toDomain(): CheckFinding? {
        val c = category?.takeIf { it in CHECK_CATEGORIES } ?: return null
        val s = start
        val e = end
        return CheckFinding(c, (severity ?: 0).coerceIn(0, 3), targeted == true, quoted == true,
            if (s != null && e != null && s in 0 until e) s until e else null)
    }
}

private val CHECK_CATEGORIES = setOf("PROFANITY", "HARASSMENT", "SEXUAL_HARASSMENT", "HATE", "THREAT", "DOXXING", "SPAM")

/** N4: the case origins the app knows. */
private val OPENED_BY = setOf("REPORTS", "SYSTEM", "AUTHOR_REQUEST", "ABOUT_ME")

/** N4: reason codes a case may carry (the decision reasons and the three that are not). */
private val REASON_CODES = ContentDecisionReason.entries.map { it.name }.toSet() + setOf("ABOUT_ME", "NEW_MEMBER_PHOTO", "AWAITING_CHECK")

data class PersonInfoDto(
    val displayName: String? = null,
    /** Phase 17E */
    val hasPhoto: Boolean? = null,
    val handle: String? = null,
    val bio: String? = null,
    val programme: String? = null,
    val graduationYear: Int? = null
)

data class CasePhotoDto(val width: Int? = null, val height: Int? = null)

/** N4: a moment case's content; an unknown kind or audience reads as none (shown as nothing, never guessed). */
data class ContentViewDto(
    val kind: String? = null,
    val audience: String? = null,
    val body: String? = null,
    val planAt: String? = null,
    val planWhere: String? = null,
    val question: String? = null,
    val photo: CasePhotoDto? = null,
    val person: PersonInfoDto? = null,
    val more: String? = null,
    val conversation: ConversationContextDto? = null
) {
    fun toDomain() = CaseContent(
        kind = kind?.takeIf { it in setOf("SAY", "WHOS_IN", "MESSAGE") },
        audience = audience?.takeIf { it in setOf("COMMUNITY", "MY_PEOPLE") },
        body = body,
        planAt = planAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
        planWhere = planWhere?.takeIf { it.isNotBlank() },
        question = question?.takeIf { it.isNotBlank() },
        photo = photo?.let { p ->
            val w = p.width ?: 0
            val h = p.height ?: 0
            if (w > 0 && h > 0) CasePhoto(w, h) else null
        },
        person = person?.let { p ->
            PersonInfo(p.displayName.orEmpty(), p.handle.orEmpty(), p.bio, p.programme?.takeIf { PROGRAMME.matches(it) },
                p.graduationYear?.takeIf { it in 1990..2100 }, p.hasPhoto == true)
        },
        more = more?.takeIf { it.isNotBlank() },
        conversation = conversation?.toDomain()
    )
}

/** N5 (N-97): a message case's context; lines without a pseudonym or words are dropped, an unknown kind reads as none. */
data class ConversationContextDto(val kind: String? = null, val messages: List<ContextMessageDto?>? = null,
                                  val connected: Boolean? = null) {
    fun toDomain() = CaseConversation(kind?.takeIf { it in setOf("PLAN", "REPLY") },
        messages.orEmpty().mapNotNull { m ->
            val ref = m?.ref?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val text = m.text?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CaseLine(ref, text, m.reported == true)
        }, connected)
}

data class ContextMessageDto(val ref: String? = null, val text: String? = null, val reported: Boolean? = null)

private val PROGRAMME = Regex("^[A-Z0-9_]{1,40}$")

data class ContentParticipantDto(
    val ref: String? = null,
    val role: String? = null,
    val reason: String? = null,
    val note: String? = null,
    val counting: Boolean? = null,
    val accountAge: String? = null,
    val linkGroup: String? = null
) {
    fun toDomain(): ContentParticipant? {
        return ContentParticipant(
            ref = ref ?: return null,
            role = codeOf<CaseRole>(role) ?: return null,
            reason = codeOf<ContentDecisionReason>(reason),
            note = note,
            counting = counting == true,
            accountAge = accountAge,
            linkGroup = linkGroup,
            aboutMe = reason == "ABOUT_ME"
        )
    }

    override fun toString() = "ContentParticipantDto(ref=<hidden>, role=$role, reason=$reason)"
}

data class AuthorViewDto(
    val ref: String? = null,
    val strikePoints: Double? = null,
    val suggestedStep: String? = null,
    val restriction: String? = null,
    val restrictedUntil: String? = null,
    val newMemberUntil: String? = null
) {
    fun toDomain(): CaseAuthor? = if (ref == null) null else CaseAuthor(
        ref = ref,
        strikePoints = strikePoints ?: 0.0,
        suggestedStep = when (suggestedStep) {
            "WARN" -> ContentAction.PERSON_WARN
            "PAUSE_COMMENTS" -> ContentAction.PERSON_PAUSE_COMMENTS
            "PAUSE_POSTING" -> ContentAction.PERSON_PAUSE_POSTING
            "SUSPEND" -> ContentAction.PERSON_SUSPEND
            else -> null
        },
        restriction = restriction,
        restrictedUntil = restrictedUntil?.let { runCatching { Instant.parse(it) }.getOrNull() },
        newMemberUntil = newMemberUntil?.let { runCatching { Instant.parse(it) }.getOrNull() }
    )

    override fun toString() = "AuthorViewDto(ref=<hidden>, suggestedStep=$suggestedStep)"
}

data class CaseDecisionDto(val action: String? = null, val reason: String? = null, val decidedOn: String? = null,
                           val byYou: Boolean? = null)

data class ContentCaseDetailDto(
    val summary: ContentCaseSummaryDto? = null,
    val content: ContentViewDto? = null,
    val participants: List<ContentParticipantDto>? = null,
    val author: AuthorViewDto? = null,
    val possibleActions: List<String>? = null,
    val decisions: List<CaseDecisionDto>? = null,
    val assessment: AssessmentViewDto? = null
) {
    fun toDomain(): ContentCaseDetail? = summary?.toDomain()?.let { s -> ContentCaseDetail(
        summary = s,
        content = (content ?: ContentViewDto()).toDomain(),
        participants = participants.orEmpty().mapNotNull { it.toDomain() },
        author = author?.toDomain(),
        possibleActions = possibleActions.orEmpty().mapNotNull { codeOf<ContentAction>(it) },
        decisions = decisions.orEmpty().mapNotNull { d ->
            codeOf<ContentAction>(d.action)?.let {
                CaseDecisionEntry(it, codeOf<ContentDecisionReason>(d.reason), dateOf(d.decidedOn), d.byYou == true)
            }
        },
        check = assessment?.toDomain()
    ) }
}

data class ContentAppealDto(
    val appealId: String? = null,
    val status: String? = null,
    val createdOn: String? = null,
    val action: String? = null,
    val reason: String? = null,
    val targetKind: String? = null,
    val caseId: String? = null,
    val youDecided: Boolean? = null
) {
    fun toDomain(): ContentAppeal? {
        return ContentAppeal(
            appealId = appealId ?: return null,
            open = status == "OPEN",
            createdOn = dateOf(createdOn),
            action = codeOf<ContentAction>(action) ?: return null,
            reason = codeOf<ContentDecisionReason>(reason),
            target = codeOf<ContentTarget>(targetKind),
            caseId = caseId,
            youDecided = youDecided != false
        )
    }
}
