package com.safesphere.app.feature.social.data.remote

import com.safesphere.app.feature.social.domain.AppealState
import com.safesphere.app.feature.social.domain.DecisionAction
import com.safesphere.app.feature.social.domain.ModerationDecision
import com.safesphere.app.feature.social.domain.ModerationReason
import com.safesphere.app.feature.social.domain.MyReport
import com.safesphere.app.feature.social.domain.PauseLevel
import com.safesphere.app.feature.social.domain.ReportReason
import com.safesphere.app.feature.social.domain.ReportTarget
import com.safesphere.app.feature.social.domain.SocialPause
import com.safesphere.app.feature.social.domain.SocialStanding
import com.safesphere.app.feature.social.domain.TimeBand
import java.time.Instant
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/** Phase 13D: reports, own standing and appeals (outside /api/moderation, which is for moderators only). */
interface ReportApi {

    @POST("api/reports")
    suspend fun report(@Body body: ReportRequestDto): ReportReceivedDto

    @GET("api/reports/mine")
    suspend fun mine(): List<MyReportDto>

    @GET("api/social-standing")
    suspend fun standing(): StandingDto

    @POST("api/social-standing/decisions/{id}/appeal")
    suspend fun appeal(@Path("id") decisionId: String): ReportReceivedDto
}

// These DTOs FAIL CLOSED like the other social ones: an unknown code is dropped (a report or decision this app cannot
// describe is not shown with wrong words), an unknown pause level reads as the widest pause, an unknown appeal state as
// "already asked" (no button that would only be refused).

data class ReportRequestDto(val targetKind: String, val targetId: String, val reason: String, val note: String?) {
    override fun toString() = "ReportRequestDto(targetKind=$targetKind, reason=$reason)"
}

data class ReportReceivedDto(val status: String? = null)

data class MyReportDto(
    val reportId: String? = null,
    val targetKind: String? = null,
    val reason: String? = null,
    val status: String? = null,
    val outcome: String? = null,
    val period: String? = null
) {
    fun toDomain(): MyReport? {
        val reviewed = when (status) {
            "RECEIVED" -> false
            "REVIEWED" -> true
            else -> return null
        }
        return MyReport(
            reportId = reportId ?: return null,
            target = enumOrNull<ReportTarget>(targetKind) ?: return null,
            reason = enumOrNull<ReportReason>(reason) ?: return null,
            reviewed = reviewed,
            actionTaken = if (!reviewed) null else when (outcome) {
                "ACTION_TAKEN" -> true
                "NO_VIOLATION" -> false
                else -> null
            },
            period = enumOrNull<TimeBand>(period) ?: TimeBand.EARLIER
        )
    }
}

data class RestrictionDto(
    val level: String? = null,
    val until: String? = null,
    val reason: String? = null,
    val decisionId: String? = null,
    val appeal: String? = null,
    val canAppeal: Boolean? = null
) {
    fun toDomain(): SocialPause? {
        val appealState = enumOrNull<AppealState>(appeal) ?: AppealState.OPEN
        return SocialPause(
            level = enumOrNull<PauseLevel>(level) ?: PauseLevel.SUSPENDED,
            until = until?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null,
            reason = enumOrNull<ModerationReason>(reason),
            decisionId = decisionId ?: return null,
            appeal = appealState,
            canAppeal = canAppeal == true && appealState == AppealState.NONE
        )
    }
}

data class DecisionDto(
    val decisionId: String? = null,
    val action: String? = null,
    val reason: String? = null,
    val targetKind: String? = null,
    val targetId: String? = null,
    val period: String? = null,
    val appeal: String? = null,
    val canAppeal: Boolean? = null
) {
    fun toDomain(): ModerationDecision? {
        val appealState = enumOrNull<AppealState>(appeal) ?: AppealState.OPEN
        return ModerationDecision(
            decisionId = decisionId ?: return null,
            action = enumOrNull<DecisionAction>(action) ?: return null,
            reason = enumOrNull<ModerationReason>(reason),
            targetId = targetId,
            period = enumOrNull<TimeBand>(period) ?: TimeBand.EARLIER,
            appeal = appealState,
            canAppeal = canAppeal == true && appealState == AppealState.NONE
        )
    }
}

data class StandingDto(val restriction: RestrictionDto? = null, val decisions: List<DecisionDto>? = null) {
    fun toDomain() = SocialStanding(restriction?.toDomain(), decisions.orEmpty().mapNotNull { it.toDomain() })
}
