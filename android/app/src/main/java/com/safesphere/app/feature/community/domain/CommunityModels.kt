package com.safesphere.app.feature.community.domain

import com.safesphere.app.core.location.GeoPoint

/**
 * Phase 6 place signals, as the app sees them (docs/PHASE6.md). Same names as the backend.
 * A signal is about a PLACE, never a person: there is no author, no text, no photo and no exact count or time.
 */
enum class SignalGroup { INCIDENT, CONDITION, HAVEN }

enum class SignalCategory(val group: SignalGroup) {
    HARASSMENT(SignalGroup.INCIDENT),
    FOLLOWING(SignalGroup.INCIDENT),
    THEFT_OR_SNATCHING(SignalGroup.INCIDENT),
    POOR_LIGHTING(SignalGroup.CONDITION),
    ISOLATED_STRETCH(SignalGroup.CONDITION),
    UNSAFE_CROSSING(SignalGroup.CONDITION),
    BROKEN_PATH(SignalGroup.CONDITION),
    WELL_LIT_BUSY(SignalGroup.HAVEN),
    OPEN_LATE_STAFFED(SignalGroup.HAVEN),
    HELP_POINT(SignalGroup.HAVEN);

    companion object {
        fun fromName(name: String?): SignalCategory? = entries.firstOrNull { it.name == name }
    }
}

/** AREA = only placed within ~1 km (one person said it); BLOCK = ~150 m (at least two people). */
enum class SignalPrecision { AREA, BLOCK }

/** A band, never a count. */
enum class SignalSupport { SINGLE, SEVERAL, MANY }

enum class SignalFreshness { TODAY, THIS_WEEK, EARLIER }

enum class ContributionKind { REPORT, CONFIRM, NO_LONGER }

/**
 * Phase 7: how sure the system is, from INDEPENDENT people and private track records. Never "verified": the app says
 * "not yet confirmed" / "confirmed by people nearby" / "strongly confirmed".
 */
enum class SignalConfidence { LOW, MODERATE, HIGH }

/** Phase 7: why a signal has its confidence. Aggregate facts only, nothing about any one person. */
enum class SignalReason {
    SINGLE_REPORT, CONFIRMED_BY_OTHERS, ESTABLISHED_CONTRIBUTORS, LINKED_SUPPORT_COUNTED_ONCE, DISPUTED
}

/** Phase 7: "something is wrong with this signal". "No longer true" is an answer, not a flag. */
enum class FlagReason { FALSE_INFORMATION, TARGETS_SOMEONE, SPAM_OR_COORDINATED }

/** One visible signal near the person. [centre] is the centre of the cell it is shown at, not where anyone stood. */
data class AreaSignal(
    val id: String,
    val category: SignalCategory,
    val precision: SignalPrecision,
    val cell: String,
    val centre: GeoPoint,
    val support: SignalSupport,
    val freshness: SignalFreshness,
    val disputed: Boolean,
    val yourContribution: ContributionKind?,
    val confidence: SignalConfidence = SignalConfidence.LOW,
    val reasons: List<SignalReason> = emptyList(),
    val youFlagged: Boolean = false
)

/** UNDER_REVIEW (Phase 7): hidden while moderators look at it; only its own contributors see this. */
enum class MyContributionStatus { PENDING, LIVE, NOT_SHOWN, UNDER_REVIEW }

/** Phase 7: the reason codes a moderator can give (only the person's own restriction reason reaches the app). */
enum class ModerationReason {
    FALSE_INFORMATION, TARGETS_SOMEONE, SPAM_OR_COORDINATED, DUPLICATE, NO_VIOLATION, NEEDS_REVIEW,
    APPEAL_REVIEWED, ROLE_CHANGE
}

enum class AppealStatus { OPEN, UPHELD, OVERTURNED }

/**
 * Phase 7: the person's own community standing. Only whether a moderator paused their contributions (never a score
 * or level: trust is private and only steers what the system shows).
 */
data class CommunityStanding(
    val contributionsPaused: Boolean,
    val pausedUntilMillis: Long?,
    val reason: ModerationReason?,
    val appeal: AppealStatus?,
    val canAppeal: Boolean
) {
    companion object {
        val ACTIVE = CommunityStanding(false, null, null, null, false)
    }
}

/** One of the person's own answers (only they see this list). */
data class MyContribution(
    val signalId: String,
    val category: SignalCategory,
    val kind: ContributionKind,
    val status: MyContributionStatus
)

/** Accepted by the server; it starts counting within about [appearsWithinMinutes]. */
data class ContributionAccepted(val appearsWithinMinutes: Int)
