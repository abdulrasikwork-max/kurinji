package com.safesphere.app.feature.moderation.domain

import com.safesphere.app.feature.community.domain.ContributionKind
import com.safesphere.app.feature.community.domain.ModerationReason

/**
 * Pure moderation rules on the phone (ModerationPolicyTest). They mirror the server (ModerationService) so the screen
 * only offers decisions that can succeed; the server still checks every one of them.
 */
object ModerationPolicy {

    /** Restriction lengths offered (the server accepts 1–90 days). */
    val RESTRICT_DAYS = listOf(7, 30)

    /** The reason codes the server accepts for each decision. No free text anywhere. */
    fun reasonsFor(action: CaseAction): List<ModerationReason> = when (action) {
        CaseAction.QUARANTINE -> listOf(
            ModerationReason.NEEDS_REVIEW, ModerationReason.FALSE_INFORMATION,
            ModerationReason.TARGETS_SOMEONE, ModerationReason.SPAM_OR_COORDINATED
        )
        CaseAction.DISMISS -> listOf(ModerationReason.NO_VIOLATION)
        CaseAction.REMOVE -> listOf(
            ModerationReason.FALSE_INFORMATION, ModerationReason.TARGETS_SOMEONE,
            ModerationReason.SPAM_OR_COORDINATED, ModerationReason.DUPLICATE
        )
        CaseAction.RESTRICT -> listOf(
            ModerationReason.FALSE_INFORMATION, ModerationReason.TARGETS_SOMEONE, ModerationReason.SPAM_OR_COORDINATED
        )
    }

    /** Decisions available on this case right now (empty = read only). */
    fun availableActions(detail: CaseDetail): List<CaseAction> {
        if (detail.youTookPart || detail.summary.youTookPart || detail.summary.status != CaseStatus.OPEN) return emptyList()
        val actions = mutableListOf<CaseAction>()
        if (detail.summary.moderation == SignalModeration.NONE) actions += CaseAction.QUARANTINE
        actions += CaseAction.DISMISS
        actions += CaseAction.REMOVE
        return actions
    }

    /** Whether "Restrict" is offered for this participant (anyone in an open case the moderator may act on). */
    fun canRestrict(detail: CaseDetail, participant: CaseParticipant): Boolean =
        availableActions(detail).isNotEmpty() && participant.tier != ContributorTier.RESTRICTED

    /**
     * Phase 9C: whether "Pause everyone in this group" is offered: an open case the moderator may act on, whose signal
     * belongs to a coord-1 group right now (SUPPORT_CORE or EXCLUSIVE_PAIR). A Phase 7 linked group alone is not one:
     * all of its people are already the case's participants.
     */
    fun canRestrictGroup(detail: CaseDetail): Boolean =
        availableActions(detail).isNotEmpty() && detail.coordination?.reasons.orEmpty().any {
            it == CoordinationReason.SUPPORT_CORE || it == CoordinationReason.EXCLUSIVE_PAIR
        }

    /** Separation of duties: nobody decides the appeal of their own restriction. */
    fun canDecide(appeal: AppealItem): Boolean = !appeal.youDecidedTheRestriction

    /** Reporters first, then confirmations, "no longer true", flags: the order a moderator reads a case in. */
    fun ordered(participants: List<CaseParticipant>): List<CaseParticipant> =
        participants.sortedBy {
            when (it.role) {
                ContributionKind.REPORT -> 0
                ContributionKind.CONFIRM -> 1
                ContributionKind.NO_LONGER -> 2
                null -> 3
            }
        }
}
