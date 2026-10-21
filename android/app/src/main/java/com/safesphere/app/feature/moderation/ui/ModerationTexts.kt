package com.safesphere.app.feature.moderation.ui

import android.content.Context
import androidx.annotation.StringRes
import com.safesphere.app.R
import com.safesphere.app.feature.community.domain.ContributionKind
import com.safesphere.app.feature.community.domain.ModerationReason
import com.safesphere.app.feature.community.ui.CommunityTexts
import com.safesphere.app.feature.moderation.domain.AccountAge
import com.safesphere.app.feature.moderation.domain.CaseAction
import com.safesphere.app.feature.moderation.domain.CaseDetail
import com.safesphere.app.feature.moderation.domain.CaseOrigin
import com.safesphere.app.feature.moderation.domain.CaseParticipant
import com.safesphere.app.feature.moderation.domain.CaseSummary
import com.safesphere.app.feature.moderation.domain.ContributorTier
import com.safesphere.app.feature.moderation.domain.CoordinationReason
import com.safesphere.app.feature.moderation.domain.GroupPlaces
import com.safesphere.app.feature.moderation.domain.GroupSize

/** Words for Phase 7 moderation values. Status is always text, never colour alone. */
object ModerationTexts {

    @StringRes
    fun origin(origin: CaseOrigin): Int = when (origin) {
        CaseOrigin.FLAGS -> R.string.case_origin_flags
        CaseOrigin.REREPORTED -> R.string.case_origin_rereported
        CaseOrigin.COORDINATION -> R.string.case_origin_coordination
    }

    @StringRes
    fun reason(reason: ModerationReason?): Int = when (reason) {
        ModerationReason.FALSE_INFORMATION -> R.string.reason_false
        ModerationReason.TARGETS_SOMEONE -> R.string.reason_targets
        ModerationReason.SPAM_OR_COORDINATED -> R.string.reason_spam
        ModerationReason.DUPLICATE -> R.string.reason_duplicate
        ModerationReason.NO_VIOLATION -> R.string.reason_no_violation
        ModerationReason.NEEDS_REVIEW, null -> R.string.reason_needs_review
        ModerationReason.APPEAL_REVIEWED, ModerationReason.ROLE_CHANGE -> R.string.paused_reason_other
    }

    @StringRes
    fun action(action: CaseAction): Int = when (action) {
        CaseAction.QUARANTINE -> R.string.action_quarantine
        CaseAction.DISMISS -> R.string.action_keep_signal
        CaseAction.REMOVE -> R.string.action_remove_signal
        CaseAction.RESTRICT -> R.string.action_restrict
    }

    /**
     * The confirmation for a decision. Phase 9C.1: keeping the signal on a COORDINATION case also tells the moderator
     * what it changes: the people behind it count separately for this place (the server's S15 Option B).
     */
    @StringRes
    fun confirmation(action: CaseAction, origin: CaseOrigin? = null): Int = when (action) {
        CaseAction.QUARANTINE -> R.string.moderation_confirm_quarantine
        CaseAction.DISMISS ->
            if (origin == CaseOrigin.COORDINATION) R.string.coordination_confirm_dismiss else R.string.moderation_confirm_dismiss
        CaseAction.REMOVE -> R.string.moderation_confirm_remove
        CaseAction.RESTRICT -> R.string.moderation_confirm_restrict
    }

    @StringRes
    fun tier(tier: ContributorTier?): Int = when (tier) {
        ContributorTier.RESTRICTED -> R.string.tier_restricted
        ContributorTier.LIMITED -> R.string.tier_limited
        ContributorTier.TRUSTED -> R.string.tier_trusted
        ContributorTier.STANDARD, null -> R.string.tier_standard
    }

    @StringRes
    fun age(age: AccountAge?): Int = when (age) {
        AccountAge.NEW, null -> R.string.age_new
        AccountAge.RECENT -> R.string.age_recent
        AccountAge.ESTABLISHED -> R.string.age_established
    }

    @StringRes
    fun coordinationReason(reason: CoordinationReason): Int = when (reason) {
        CoordinationReason.LINKED_GROUP -> R.string.coordination_linked_group
        CoordinationReason.SUPPORT_CORE -> R.string.coordination_support_core
        CoordinationReason.EXCLUSIVE_PAIR -> R.string.coordination_exclusive_pair
    }

    @StringRes
    fun groupSize(size: GroupSize): Int = when (size) {
        GroupSize.TWO -> R.string.coordination_size_two
        GroupSize.THREE_TO_FIVE -> R.string.coordination_size_small
        GroupSize.SIX_OR_MORE -> R.string.coordination_size_large
    }

    @StringRes
    fun groupPlaces(places: GroupPlaces): Int = when (places) {
        GroupPlaces.TWO_TO_FOUR -> R.string.coordination_places_few
        GroupPlaces.FIVE_TO_NINE -> R.string.coordination_places_several
        GroupPlaces.TEN_OR_MORE -> R.string.coordination_places_many
    }

    /**
     * Phase 9C: the pattern lines for a case, with the guidance that backing each other proves nothing by itself.
     * A COORDINATION case whose pattern has gone says so; any other case without a pattern shows nothing.
     * Returns string resource ids, in reading order.
     */
    fun coordinationLines(detail: CaseDetail): List<Int> {
        val pattern = detail.coordination
            ?: return if (detail.summary.origin == CaseOrigin.COORDINATION) listOf(R.string.coordination_gone) else emptyList()
        val lines = mutableListOf(R.string.coordination_heading)
        pattern.reasons.forEach { lines += coordinationReason(it) }
        pattern.groupSize?.let { lines += groupSize(it) }
        pattern.groupPlaces?.let { lines += groupPlaces(it) }
        lines += R.string.coordination_guidance
        return lines
    }

    /** "Flagged by people nearby · 2 flags · opened 2026-10-20". */
    fun caseLine(context: Context, case: CaseSummary): String = context.getString(
        R.string.case_line,
        context.getString(origin(case.origin)),
        case.flags.values.sum().let { context.resources.getQuantityString(R.plurals.case_flags, it, it) },
        case.openedOn ?: "?"
    )

    fun flagCounts(context: Context, case: CaseSummary): String =
        case.flags.entries.joinToString(", ") { (reason, count) ->
            "${context.getString(CommunityTexts.flagReason(reason))} ($count)"
        }

    /** "Reported it · good record · over 30 days" (+ "not counting", "acts with group A"). */
    fun participant(context: Context, participant: CaseParticipant): String {
        val role = when (participant.role) {
            ContributionKind.REPORT -> context.getString(R.string.role_report)
            ContributionKind.CONFIRM -> context.getString(R.string.role_confirm)
            ContributionKind.NO_LONGER -> context.getString(R.string.role_no_longer)
            null -> participant.flag?.let { context.getString(R.string.role_flag, context.getString(CommunityTexts.flagReason(it))) }
                ?: context.getString(R.string.coordination_member_role) // Phase 9C: in the group, not behind this place
        }
        val parts = mutableListOf(context.getString(R.string.participant_line, role,
            context.getString(tier(participant.tier)), context.getString(age(participant.accountAge))))
        if (!participant.counting) parts += context.getString(R.string.participant_not_counting)
        participant.linkGroup?.let { parts += context.getString(R.string.participant_group, it) }
        return parts.joinToString(" · ")
    }
}
