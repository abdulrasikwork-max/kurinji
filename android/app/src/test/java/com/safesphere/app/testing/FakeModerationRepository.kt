package com.safesphere.app.testing

import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.community.domain.AppealStatus
import com.safesphere.app.feature.community.domain.ContributionKind
import com.safesphere.app.feature.community.domain.FlagReason
import com.safesphere.app.feature.community.domain.ModerationReason
import com.safesphere.app.feature.community.domain.SignalCategory
import com.safesphere.app.feature.community.domain.SignalConfidence
import com.safesphere.app.feature.moderation.domain.AccountAge
import com.safesphere.app.feature.moderation.domain.AppealItem
import com.safesphere.app.feature.moderation.domain.CaseAction
import com.safesphere.app.feature.moderation.domain.CaseDetail
import com.safesphere.app.feature.moderation.domain.CaseOrigin
import com.safesphere.app.feature.moderation.domain.CaseParticipant
import com.safesphere.app.feature.moderation.domain.CaseStatus
import com.safesphere.app.feature.moderation.domain.CaseSummary
import com.safesphere.app.feature.moderation.domain.ContributorTier
import com.safesphere.app.feature.moderation.domain.ModerationAccess
import com.safesphere.app.feature.moderation.domain.ModerationRepository
import com.safesphere.app.feature.moderation.domain.SignalModeration

/** Records every call; answers come from the public fields. */
class FakeModerationRepository : ModerationRepository {
    var accessResult: ApiResult<ModerationAccess> = ApiResult.Success(ModerationAccess(true, false))
    var casesResult: ApiResult<List<CaseSummary>> = ApiResult.Success(listOf(caseSummary()))
    var detailResult: ApiResult<CaseDetail> = ApiResult.Success(caseDetail())
    var actResult: ApiResult<CaseDetail>? = null
    var appealsResult: ApiResult<List<AppealItem>> = ApiResult.Success(emptyList())
    var decideResult: ApiResult<AppealItem>? = null

    data class Act(
        val caseId: String,
        val action: CaseAction,
        val reason: ModerationReason,
        val ref: String?,
        val days: Int?,
        val wholeGroup: Boolean = false
    )

    val acts = mutableListOf<Act>()
    val decisions = mutableListOf<Pair<String, AppealStatus>>()
    var casesCalls = 0

    override suspend fun access() = accessResult
    override suspend fun openCases() = casesResult.also { casesCalls++ }
    override suspend fun caseDetail(caseId: String) = detailResult
    override suspend fun act(
        caseId: String,
        action: CaseAction,
        reason: ModerationReason,
        participantRef: String?,
        days: Int?,
        wholeGroup: Boolean
    ) = (actResult ?: detailResult).also { acts += Act(caseId, action, reason, participantRef, days, wholeGroup) }
    override suspend fun openAppeals() = appealsResult
    override suspend fun decideAppeal(appealId: String, decision: AppealStatus) =
        (decideResult ?: ApiResult.Success(appeal(id = appealId).copy(status = decision))).also { decisions += appealId to decision }
}

fun caseSummary(
    id: String = "c1",
    status: CaseStatus = CaseStatus.OPEN,
    moderation: SignalModeration = SignalModeration.NONE,
    youTookPart: Boolean = false
) = CaseSummary(id, CaseOrigin.FLAGS, status, null, "2026-10-20", SignalCategory.HARASSMENT, moderation,
    mapOf(FlagReason.TARGETS_SOMEONE to 1), 1, SignalConfidence.LOW, youTookPart)

fun participant(ref: String = "0123456789ab", role: ContributionKind? = ContributionKind.REPORT,
                tier: ContributorTier = ContributorTier.STANDARD) =
    CaseParticipant(ref, role, if (role == null) FlagReason.TARGETS_SOMEONE else null, tier, AccountAge.RECENT, true, null)

fun caseDetail(summary: CaseSummary = caseSummary(), youTookPart: Boolean = false) = CaseDetail(
    summary, "tdr1v9q", "tdr1v9", true, emptyList(), 0,
    listOf(participant(ref = "ffffffffffff", role = null), participant()), youTookPart
)

fun appeal(id: String = "a1", mine: Boolean = false) = AppealItem(
    id, AppealStatus.OPEN, "2026-10-20", ModerationReason.FALSE_INFORMATION, SignalCategory.HARASSMENT, "2026-10-27",
    ContributorTier.RESTRICTED, 1, mine
)
