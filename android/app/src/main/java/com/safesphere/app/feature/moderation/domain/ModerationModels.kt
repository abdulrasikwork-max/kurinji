package com.safesphere.app.feature.moderation.domain

import com.safesphere.app.feature.community.domain.AppealStatus
import com.safesphere.app.feature.community.domain.ContributionKind
import com.safesphere.app.feature.community.domain.FlagReason
import com.safesphere.app.feature.community.domain.ModerationReason
import com.safesphere.app.feature.community.domain.SignalCategory
import com.safesphere.app.feature.community.domain.SignalConfidence
import com.safesphere.app.feature.community.domain.SignalReason

/**
 * Phase 7 moderation as the app sees it (docs/PHASE7.md §4). Same names as the backend. Nothing here identifies a
 * person: participants are case-scoped pseudonyms, ages and tiers are bands. The server decides who may see any of it.
 */
data class ModerationAccess(val canModerate: Boolean, val canAdminister: Boolean) {
    companion object {
        val NONE = ModerationAccess(canModerate = false, canAdminister = false)
    }
}

enum class CaseOrigin { FLAGS, REREPORTED, COORDINATION }

enum class CaseStatus { OPEN, CLOSED }

enum class CaseOutcome { DISMISSED, REMOVED }

/** Moderation state of the signal itself. */
enum class SignalModeration { NONE, QUARANTINED, REMOVED }

/** Private tier, shown ONLY to moderators (never to the public, never to the person). */
enum class ContributorTier { RESTRICTED, LIMITED, STANDARD, TRUSTED }

enum class AccountAge { NEW, RECENT, ESTABLISHED }

data class CaseSummary(
    val id: String,
    val origin: CaseOrigin,
    val status: CaseStatus,
    val outcome: CaseOutcome?,
    /** ISO date (a day, never a time). */
    val openedOn: String?,
    val category: SignalCategory,
    val moderation: SignalModeration,
    val flags: Map<FlagReason, Int>,
    val independentSupporters: Int,
    val confidence: SignalConfidence,
    /** The moderator contributed to or flagged this signal: someone else must review it. */
    val youTookPart: Boolean
)

/**
 * One person in a case: [ref] is a pseudonym valid for this case only. [role] null = a flagger ([flag] set); both null =
 * a member of the coordination group who is not behind this place (CaseDetail.groupMembers).
 */
data class CaseParticipant(
    val ref: String,
    val role: ContributionKind?,
    val flag: FlagReason?,
    val tier: ContributorTier?,
    val accountAge: AccountAge?,
    val counting: Boolean,
    /** Same letter = accounts that keep acting together. */
    val linkGroup: String?
)

data class CaseDetail(
    val summary: CaseSummary,
    /** ~150 m geohash block (needed to judge "aimed at a home"); never coordinates. */
    val cell: String,
    val areaCell: String,
    val publiclyVisible: Boolean,
    val reasons: List<SignalReason>,
    val independentDisputers: Int,
    val participants: List<CaseParticipant>,
    val youTookPart: Boolean,
    /** Phase 9C: the coordination pattern behind the signal right now, or null when there is none. */
    val coordination: CoordinationPattern? = null,
    /** Phase 9C: the rest of that group (not behind this place), by case pseudonym and bands only. */
    val groupMembers: List<CaseParticipant> = emptyList()
)

/** Phase 9C: why the support behind a case's signal looks coordinated. Same names as the backend. */
enum class CoordinationReason { LINKED_GROUP, SUPPORT_CORE, EXCLUSIVE_PAIR }

/** How many accounts, as a band (never a count). */
enum class GroupSize { TWO, THREE_TO_FIVE, SIX_OR_MORE }

/** How many places only this group backs, as a band. */
enum class GroupPlaces { TWO_TO_FOUR, FIVE_TO_NINE, TEN_OR_MORE }

/** Bands only: no other place and no number of areas reach the phone. */
data class CoordinationPattern(
    val reasons: List<CoordinationReason>,
    val groupSize: GroupSize?,
    val groupPlaces: GroupPlaces?
)

/** Decisions a moderator can take on a case. */
enum class CaseAction { QUARANTINE, DISMISS, REMOVE, RESTRICT }

/** An appeal as a moderator sees it: what was decided, never who appealed. */
data class AppealItem(
    val id: String,
    val status: AppealStatus,
    val createdOn: String?,
    val restrictionReason: ModerationReason?,
    val category: SignalCategory?,
    val restrictedUntil: String?,
    val currentTier: ContributorTier?,
    val strikes: Int,
    /** The caller made the restriction: separation of duties, someone else must decide. */
    val youDecidedTheRestriction: Boolean
)
