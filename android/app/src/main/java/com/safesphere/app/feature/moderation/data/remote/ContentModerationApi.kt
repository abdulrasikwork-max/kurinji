package com.safesphere.app.feature.moderation.data.remote

import com.safesphere.app.feature.moderation.domain.CaseAuthor
import com.safesphere.app.feature.moderation.domain.CaseContent
import com.safesphere.app.feature.moderation.domain.CaseDecisionEntry
import com.safesphere.app.feature.moderation.domain.CaseMedia
import com.safesphere.app.feature.moderation.domain.ContentParticipant
import com.safesphere.app.feature.moderation.domain.CaseRevision
import com.safesphere.app.feature.moderation.domain.CaseRole
import com.safesphere.app.feature.moderation.domain.ContentAction
import com.safesphere.app.feature.moderation.domain.ContentAppeal
import com.safesphere.app.feature.moderation.domain.ContentCaseDetail
import com.safesphere.app.feature.moderation.domain.ContentCaseSummary
import com.safesphere.app.feature.moderation.domain.ContentDecisionReason
import com.safesphere.app.feature.moderation.domain.ContentTarget
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
    val youTookPart: Boolean? = null
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
            youTookPart = youTookPart != false
        )
    }
}

data class PersonInfoDto(
    val displayName: String? = null,
    val handle: String? = null,
    val bio: String? = null,
    val city: String? = null,
    val interests: List<String>? = null
)

data class MediaViewDto(val mediaId: String? = null, val width: Int? = null, val height: Int? = null)

data class ContentViewDto(
    val postType: String? = null,
    val commentKind: String? = null,
    val body: String? = null,
    val tip: String? = null,
    val category: String? = null,
    val precision: String? = null,
    val placeName: String? = null,
    val areaCell: String? = null,
    val city: String? = null,
    val audience: String? = null,
    val experiencedMonth: String? = null,
    val daypart: String? = null,
    val media: List<MediaViewDto>? = null,
    val contextBody: String? = null,
    val person: PersonInfoDto? = null
) {
    fun toDomain() = CaseContent(
        postType, commentKind, body, tip, category, precision, placeName, areaCell, city, audience, experiencedMonth,
        daypart, media.orEmpty().mapNotNull { m -> m.mediaId?.let { CaseMedia(it, m.width ?: 0, m.height ?: 0) } },
        contextBody,
        person?.let { p -> PersonInfo(p.displayName.orEmpty(), p.handle.orEmpty(), p.bio, p.city, p.interests.orEmpty()) }
    )
}

data class RevisionViewDto(val body: String? = null, val tip: String? = null, val replacedOn: String? = null)

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
            linkGroup = linkGroup
        )
    }

    override fun toString() = "ContentParticipantDto(ref=<hidden>, role=$role, reason=$reason)"
}

data class AuthorViewDto(
    val ref: String? = null,
    val strikePoints: Double? = null,
    val suggestedStep: String? = null,
    val restriction: String? = null,
    val restrictedUntil: String? = null
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
        restrictedUntil = restrictedUntil?.let { runCatching { Instant.parse(it) }.getOrNull() }
    )

    override fun toString() = "AuthorViewDto(ref=<hidden>, suggestedStep=$suggestedStep)"
}

data class CaseDecisionDto(val action: String? = null, val reason: String? = null, val decidedOn: String? = null,
                           val byYou: Boolean? = null)

data class ContentCaseDetailDto(
    val summary: ContentCaseSummaryDto? = null,
    val content: ContentViewDto? = null,
    val revisions: List<RevisionViewDto>? = null,
    val participants: List<ContentParticipantDto>? = null,
    val author: AuthorViewDto? = null,
    val possibleActions: List<String>? = null,
    val decisions: List<CaseDecisionDto>? = null
) {
    fun toDomain(): ContentCaseDetail? = summary?.toDomain()?.let { s -> ContentCaseDetail(
        summary = s,
        content = (content ?: ContentViewDto()).toDomain(),
        revisions = revisions.orEmpty().mapNotNull { r -> r.body?.let { CaseRevision(it, r.tip, dateOf(r.replacedOn)) } },
        participants = participants.orEmpty().mapNotNull { it.toDomain() },
        author = author?.toDomain(),
        possibleActions = possibleActions.orEmpty().mapNotNull { codeOf<ContentAction>(it) },
        decisions = decisions.orEmpty().mapNotNull { d ->
            codeOf<ContentAction>(d.action)?.let {
                CaseDecisionEntry(it, codeOf<ContentDecisionReason>(d.reason), dateOf(d.decidedOn), d.byYou == true)
            }
        }
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
