package com.safesphere.app.feature.community.ui

import android.content.Context
import androidx.annotation.StringRes
import com.safesphere.app.R
import com.safesphere.app.core.ui.components.BadgeStatus
import com.safesphere.app.feature.community.domain.CommunityPolicy
import com.safesphere.app.feature.community.domain.AreaSignal
import com.safesphere.app.feature.community.domain.CommunityPolicy.PlacedSignal
import com.safesphere.app.feature.community.domain.ContributionKind
import com.safesphere.app.feature.community.domain.FlagReason
import com.safesphere.app.feature.community.domain.ModerationReason
import com.safesphere.app.feature.community.domain.MyContributionStatus
import com.safesphere.app.feature.community.domain.SignalCategory
import com.safesphere.app.feature.community.domain.SignalConfidence
import com.safesphere.app.feature.community.domain.SignalReason
import com.safesphere.app.feature.community.domain.SignalFreshness
import com.safesphere.app.feature.community.domain.SignalGroup
import com.safesphere.app.feature.community.domain.SignalPrecision
import com.safesphere.app.feature.community.domain.SignalSupport

/** Words for Phase 6 values. Status is always text (+ icon), never colour alone. */
object CommunityTexts {

    @StringRes
    fun category(category: SignalCategory): Int = when (category) {
        SignalCategory.HARASSMENT -> R.string.signal_harassment
        SignalCategory.FOLLOWING -> R.string.signal_following
        SignalCategory.THEFT_OR_SNATCHING -> R.string.signal_theft
        SignalCategory.POOR_LIGHTING -> R.string.signal_poor_lighting
        SignalCategory.ISOLATED_STRETCH -> R.string.signal_isolated
        SignalCategory.UNSAFE_CROSSING -> R.string.signal_unsafe_crossing
        SignalCategory.BROKEN_PATH -> R.string.signal_broken_path
        SignalCategory.WELL_LIT_BUSY -> R.string.signal_well_lit
        SignalCategory.OPEN_LATE_STAFFED -> R.string.signal_open_late
        SignalCategory.HELP_POINT -> R.string.signal_help_point
    }

    fun badge(group: SignalGroup): Pair<BadgeStatus, Int> = when (group) {
        SignalGroup.INCIDENT -> BadgeStatus.CAUTION to R.string.group_incident
        SignalGroup.CONDITION -> BadgeStatus.CAUTION to R.string.group_condition
        SignalGroup.HAVEN -> BadgeStatus.SAFE to R.string.group_haven
    }

    @StringRes
    fun support(support: SignalSupport): Int = when (support) {
        SignalSupport.SINGLE -> R.string.support_single
        SignalSupport.SEVERAL -> R.string.support_several
        SignalSupport.MANY -> R.string.support_many
    }

    @StringRes
    fun freshness(freshness: SignalFreshness): Int = when (freshness) {
        SignalFreshness.TODAY -> R.string.freshness_today
        SignalFreshness.THIS_WEEK -> R.string.freshness_week
        SignalFreshness.EARLIER -> R.string.freshness_earlier
    }

    @StringRes
    fun yourKind(kind: ContributionKind): Int = when (kind) {
        ContributionKind.REPORT -> R.string.your_kind_report
        ContributionKind.CONFIRM -> R.string.your_kind_confirm
        ContributionKind.NO_LONGER -> R.string.your_kind_no_longer
    }

    @StringRes
    fun myStatus(status: MyContributionStatus): Int = when (status) {
        MyContributionStatus.PENDING -> R.string.my_status_pending
        MyContributionStatus.LIVE -> R.string.my_status_live
        MyContributionStatus.NOT_SHOWN -> R.string.my_status_not_shown
        MyContributionStatus.UNDER_REVIEW -> R.string.my_status_under_review
    }

    /** Phase 7: plain words, never "verified". */
    @StringRes
    fun confidence(confidence: SignalConfidence): Int = when (confidence) {
        SignalConfidence.LOW -> R.string.confidence_low
        SignalConfidence.MODERATE -> R.string.confidence_moderate
        SignalConfidence.HIGH -> R.string.confidence_high
    }

    @StringRes
    fun reason(reason: SignalReason): Int = when (reason) {
        SignalReason.SINGLE_REPORT -> R.string.why_reason_single
        SignalReason.CONFIRMED_BY_OTHERS -> R.string.why_reason_confirmed
        SignalReason.ESTABLISHED_CONTRIBUTORS -> R.string.why_reason_established
        SignalReason.LINKED_SUPPORT_COUNTED_ONCE -> R.string.why_reason_linked
        SignalReason.DISPUTED -> R.string.why_reason_disputed
    }

    @StringRes
    fun flagReason(reason: FlagReason): Int = when (reason) {
        FlagReason.FALSE_INFORMATION -> R.string.flag_reason_false
        FlagReason.TARGETS_SOMEONE -> R.string.flag_reason_targets
        FlagReason.SPAM_OR_COORDINATED -> R.string.flag_reason_spam
    }

    @StringRes
    fun pausedReason(reason: ModerationReason?): Int = when (reason) {
        ModerationReason.FALSE_INFORMATION -> R.string.paused_reason_false
        ModerationReason.TARGETS_SOMEONE -> R.string.paused_reason_targets
        ModerationReason.SPAM_OR_COORDINATED -> R.string.paused_reason_spam
        else -> R.string.paused_reason_other
    }

    /** "How sure is this?": one line per reason, then what the system does and does not claim. */
    fun why(context: Context, signal: AreaSignal): String =
        (signal.reasons.map { "• " + context.getString(reason(it)) } + "" + context.getString(R.string.why_footer))
            .joinToString("\n")

    /**
     * "About 300 m · Confirmed by people nearby · This week" (+ "placed only to about 1 km" for a single report).
     * Phase 7: the confidence replaced the support band (it already says how many independent people agree).
     */
    fun details(context: Context, placed: PlacedSignal): String {
        val signal = placed.signal
        val distance = context.getString(R.string.signal_distance, distanceText(context, placed.distanceMeters))
        val parts = mutableListOf(distance, context.getString(confidence(signal.confidence)), context.getString(freshness(signal.freshness)))
        if (signal.precision == SignalPrecision.AREA) parts += context.getString(R.string.signal_area_precision)
        return parts.joinToString(" · ")
    }

    fun distanceText(context: Context, meters: Double): String {
        val rounded = CommunityPolicy.approximateMeters(meters)
        return if (rounded >= 1000) context.getString(R.string.distance_km, rounded / 1000.0)
        else context.getString(R.string.distance_m, rounded)
    }
}
