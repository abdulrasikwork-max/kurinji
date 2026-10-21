package com.safesphere.app.feature.moderation.data.remote

import com.safesphere.app.feature.community.data.remote.enumOrNull
import com.safesphere.app.feature.community.domain.AppealStatus
import com.safesphere.app.feature.community.domain.ContributionKind
import com.safesphere.app.feature.community.domain.FlagReason
import com.safesphere.app.feature.community.domain.ModerationReason
import com.safesphere.app.feature.community.domain.SignalCategory
import com.safesphere.app.feature.community.domain.SignalConfidence
import com.safesphere.app.feature.community.domain.SignalReason
import com.safesphere.app.feature.moderation.domain.AccountAge
import com.safesphere.app.feature.moderation.domain.AppealItem
import com.safesphere.app.feature.moderation.domain.CaseDetail
import com.safesphere.app.feature.moderation.domain.CaseOrigin
import com.safesphere.app.feature.moderation.domain.CaseOutcome
import com.safesphere.app.feature.moderation.domain.CaseParticipant
import com.safesphere.app.feature.moderation.domain.CaseStatus
import com.safesphere.app.feature.moderation.domain.CaseSummary
import com.safesphere.app.feature.moderation.domain.ContributorTier
import com.safesphere.app.feature.moderation.domain.CoordinationPattern
import com.safesphere.app.feature.moderation.domain.CoordinationReason
import com.safesphere.app.feature.moderation.domain.GroupPlaces
import com.safesphere.app.feature.moderation.domain.GroupSize
import com.safesphere.app.feature.moderation.domain.ModerationAccess
import com.safesphere.app.feature.moderation.domain.SignalModeration

/**
 * Phase 7 moderation bodies. Unknown values from a newer server are skipped rather than shown wrongly; a case or
 * appeal that cannot be read is dropped. toString() of anything carrying a cell or a pseudonym keeps them out of logs.
 */
data class AccessDto(val canModerate: Boolean?, val canAdminister: Boolean?) {
    fun toDomain() = ModerationAccess(canModerate == true, canAdminister == true)
}

data class CaseSummaryDto(
    val id: String?,
    val origin: String?,
    val status: String?,
    val outcome: String?,
    val openedOn: String?,
    val category: String?,
    val moderation: String?,
    val flags: Map<String, Int>?,
    val independentSupporters: Int?,
    val confidence: String?,
    val youTookPart: Boolean?
) {
    fun toDomain(): CaseSummary? = summaryOrNull()

    private fun summaryOrNull(): CaseSummary? {
        return CaseSummary(
        id = id ?: return null,
        origin = enumOrNull<CaseOrigin>(origin) ?: return null,
        status = enumOrNull<CaseStatus>(status) ?: return null,
        outcome = enumOrNull<CaseOutcome>(outcome),
        openedOn = openedOn,
        category = SignalCategory.fromName(category) ?: return null,
        moderation = enumOrNull<SignalModeration>(moderation) ?: SignalModeration.NONE,
        flags = flags.orEmpty().mapNotNull { (k, v) -> enumOrNull<FlagReason>(k)?.let { it to v } }.toMap(),
        independentSupporters = independentSupporters ?: 0,
        confidence = enumOrNull<SignalConfidence>(confidence) ?: SignalConfidence.LOW,
        // Unknown = assume the moderator took part: the app then offers no decision (the server decides anyway).
        youTookPart = youTookPart ?: true
        )
    }
}

data class ParticipantDto(
    val ref: String?,
    val role: String?,
    val flag: String?,
    val tier: String?,
    val accountAge: String?,
    val counting: Boolean?,
    val linkGroup: String?
) {
    fun toDomain(): CaseParticipant? {
        return CaseParticipant(
            ref = ref ?: return null,
            role = enumOrNull<ContributionKind>(role),
            flag = enumOrNull<FlagReason>(flag),
            tier = enumOrNull<ContributorTier>(tier),
            accountAge = enumOrNull<AccountAge>(accountAge),
            counting = counting ?: false,
            linkGroup = linkGroup
        )
    }

    override fun toString() = "ParticipantDto(ref=<redacted>, role=$role, flag=$flag)"
}

/** Phase 9C. Unknown reasons are dropped; with none left there is no pattern to show. */
data class CoordinationDto(val reasons: List<String>?, val groupSize: String?, val groupPlaces: String?) {
    fun toDomain(): CoordinationPattern? {
        val known = reasons.orEmpty().mapNotNull { enumOrNull<CoordinationReason>(it) }
        if (known.isEmpty()) return null
        return CoordinationPattern(known, enumOrNull<GroupSize>(groupSize), enumOrNull<GroupPlaces>(groupPlaces))
    }
}

data class CaseDetailDto(
    val summary: CaseSummaryDto?,
    val cell: String?,
    val areaCell: String?,
    val publiclyVisible: Boolean?,
    val reasons: List<String>?,
    val independentDisputers: Int?,
    val participants: List<ParticipantDto>?,
    val youTookPart: Boolean?,
    val coordination: CoordinationDto? = null,
    /** Phase 9C: the rest of a coordination group (an older server sends none). */
    val groupMembers: List<ParticipantDto>? = null
) {
    fun toDomain(): CaseDetail? {
        val readSummary = summary?.toDomain() ?: return null
        val readCell = cell ?: return null
        return CaseDetail(
            summary = readSummary,
            cell = readCell,
            areaCell = areaCell ?: readCell.take(6),
            publiclyVisible = publiclyVisible ?: false,
            reasons = reasons.orEmpty().mapNotNull { enumOrNull<SignalReason>(it) },
            independentDisputers = independentDisputers ?: 0,
            participants = participants.orEmpty().mapNotNull { it.toDomain() },
            youTookPart = youTookPart ?: true,
            coordination = coordination?.toDomain(),
            // A group member has no role and no flag by definition; anything else is not read as one.
            groupMembers = groupMembers.orEmpty().mapNotNull { it.toDomain() }
                .filter { it.role == null && it.flag == null }
        )
    }

    override fun toString() = "CaseDetailDto(summary=$summary, cell=<redacted>, participants=${participants?.size}, " +
        "groupMembers=${groupMembers?.size})"
}

/** [contributorRef] and [days] only for RESTRICT; [wholeGroup] (Phase 9C) instead of [contributorRef]. */
data class ActionRequestDto(
    val action: String,
    val reason: String,
    val contributorRef: String?,
    val days: Int?,
    val wholeGroup: Boolean? = null
) {
    override fun toString() =
        "ActionRequestDto(action=$action, reason=$reason, contributorRef=<redacted>, days=$days, wholeGroup=$wholeGroup)"
}

data class AppealDecisionDto(val decision: String)

data class AppealDto(
    val id: String?,
    val status: String?,
    val createdOn: String?,
    val restrictionReason: String?,
    val category: String?,
    val restrictedUntil: String?,
    val currentTier: String?,
    val strikes: Int?,
    val youDecidedTheRestriction: Boolean?
) {
    fun toDomain(): AppealItem? {
        return AppealItem(
            id = id ?: return null,
            status = enumOrNull<AppealStatus>(status) ?: return null,
            createdOn = createdOn,
            restrictionReason = enumOrNull<ModerationReason>(restrictionReason),
            category = SignalCategory.fromName(category),
            restrictedUntil = restrictedUntil,
            currentTier = enumOrNull<ContributorTier>(currentTier),
            strikes = strikes ?: 0,
            // Unknown = assume it was the caller's decision: the app then offers no decision.
            youDecidedTheRestriction = youDecidedTheRestriction ?: true
        )
    }
}
