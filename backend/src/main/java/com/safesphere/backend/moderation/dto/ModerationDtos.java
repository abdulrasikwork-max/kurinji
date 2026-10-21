package com.safesphere.backend.moderation.dto;

import com.safesphere.backend.community.entity.ContributionKind;
import com.safesphere.backend.community.entity.FlagReason;
import com.safesphere.backend.community.entity.ModerationState;
import com.safesphere.backend.community.entity.SignalCategory;
import com.safesphere.backend.community.entity.SignalCategory.SignalGroup;
import com.safesphere.backend.community.service.SignalPolicy.Confidence;
import com.safesphere.backend.community.service.SignalPolicy.Precision;
import com.safesphere.backend.community.service.SignalPolicy.Reason;
import com.safesphere.backend.community.service.TrustPolicy.Tier;
import com.safesphere.backend.moderation.entity.ModerationEnums.ActionReason;
import com.safesphere.backend.moderation.entity.ModerationEnums.ActionType;
import com.safesphere.backend.moderation.entity.ModerationEnums.AppealStatus;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseOrigin;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseOutcome;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseStatus;
import com.safesphere.backend.user.entity.UserRole;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Phase 7 request and response bodies for moderation. Responses are FIELD ALLOW-LISTS: no names, e-mails, user ids,
 * emergency, Safe Walk, Safety Circle or evidence data ever appear in a moderator's view. People in a case are
 * case-scoped pseudonyms (CaseRefs). Only the ADMIN audit-log view carries account ids (for accountability).
 */
public final class ModerationDtos {

    private ModerationDtos() {
    }

    /** What the signed-in person may do (shown or hidden menu entries; the server checks again on every call). */
    public record Access(boolean canModerate, boolean canAdminister) {
    }

    /**
     * One case in the queue (oldest first). [openedOn] is a day, not a time. [youTookPart] = the CALLER contributed to
     * or flagged this signal, so another moderator must review it (the detail and actions answer 403 for them).
     */
    public record CaseSummary(String id, CaseOrigin origin, CaseStatus status, CaseOutcome outcome, LocalDate openedOn,
                              SignalCategory category, SignalGroup group, ModerationState moderation,
                              Map<FlagReason, Integer> flags, int independentSupporters, Confidence confidence,
                              boolean youTookPart) {
    }

    /** Account age as a band, never a date. */
    public enum AccountAge { NEW, RECENT, ESTABLISHED }

    /**
     * One person in a case, pseudonymous. [role] = their contribution kind, or null for a flagger ([flag] set).
     * [linkGroup] = a letter shared by participants who keep acting together (null = independent).
     * In CaseDetail.groupMembers (Phase 9C) both [role] and [flag] are null, and [counting] means "their community
     * contributions count at all" (false once paused).
     */
    public record Participant(String ref, ContributionKind role, FlagReason flag, Tier tier, AccountAge accountAge,
                              boolean counting, String linkGroup) {
    }

    /**
     * The full case. [cell] is the ~150 m block (needed to judge "targets a home"); no coordinates are stored.
     * [coordination] (Phase 9C) is the coordination pattern behind the signal right now, or null when there is none.
     * [groupMembers] (Phase 9C) are the OTHER people of a coord-1 group this signal belongs to right now (not behind
     * this signal), by case pseudonym and bands only, so one review can pause the whole group; empty otherwise.
     */
    public record CaseDetail(CaseSummary summary, String cell, String areaCell, Precision precision,
                             boolean publiclyVisible, List<Reason> reasons, int independentDisputers,
                             List<Participant> participants, boolean youTookPart, Coordination coordination,
                             List<Participant> groupMembers) {
    }

    /** Phase 9C: why the support behind a case's signal looks coordinated. Recomputed each time the case is viewed. */
    public enum CoordinationReason {
        /** Phase 7: all of the support comes from accounts that keep acting together (counted as one person). */
        LINKED_GROUP,
        /** coord-1 R1: a group that keeps backing each other's reports in several parts of town, and nobody else. */
        SUPPORT_CORE,
        /** coord-1 R2: two accounts that are the only backers of several places across town. */
        EXCLUSIVE_PAIR
    }

    /** How many accounts, as a band, never a count. */
    public enum GroupSize {
        TWO, THREE_TO_FIVE, SIX_OR_MORE;

        public static GroupSize of(int people) {
            return people <= 2 ? TWO : people <= 5 ? THREE_TO_FIVE : SIX_OR_MORE;
        }
    }

    /** How many places only this group backs, as a band. */
    public enum GroupPlaces {
        TWO_TO_FOUR, FIVE_TO_NINE, TEN_OR_MORE;

        public static GroupPlaces of(int places) {
            return places <= 4 ? TWO_TO_FOUR : places <= 9 ? FIVE_TO_NINE : TEN_OR_MORE;
        }
    }

    /**
     * Phase 9C: the pattern behind a case, in bands only. No cell of any other signal and no number of areas: that would
     * sketch where pseudonymous people go. [groupPlaces] is null when only LINKED_GROUP applies.
     */
    public record Coordination(List<CoordinationReason> reasons, GroupSize groupSize, GroupPlaces groupPlaces) {
    }

    /**
     * A moderator's decision on a case. [contributorRef] and [days] only for RESTRICT. [wholeGroup] (Phase 9C) only for
     * RESTRICT, instead of [contributorRef]: pause every member of the coord-1 group behind this signal in ONE decision
     * (each person gets their own audit entry and may appeal on their own).
     */
    public record ActionRequest(
            @NotNull(message = "Action is required") ActionType action,
            @NotNull(message = "Reason is required") ActionReason reason,
            @Pattern(regexp = "^[0-9a-f]{12}$", message = "Unknown participant") String contributorRef,
            @Min(value = 1, message = "At least 1 day") @Max(value = 90, message = "At most 90 days") Integer days,
            Boolean wholeGroup) {

        public boolean forWholeGroup() {
            return Boolean.TRUE.equals(wholeGroup);
        }
    }

    /** An appeal as a moderator sees it: what was decided, never who appealed. */
    public record AppealView(String id, AppealStatus status, LocalDate createdOn, ActionReason restrictionReason,
                             SignalCategory category, LocalDate restrictedUntil, Tier currentTier, int strikes,
                             boolean youDecidedTheRestriction) {
    }

    public record AppealDecision(@NotNull(message = "Decision is required") AppealStatus decision) {
    }

    /** The person's own community standing (OWNER view). The private tier is NOT included, on purpose. */
    public record MyStanding(boolean contributionsPaused, LocalDateTime pausedUntil, ActionReason reason,
                             AppealStatus appeal, boolean canAppeal) {
    }

    public record AppealAccepted(String status) {
    }

    /** ADMIN: change someone's role. [role] may only be USER or MODERATOR (ADMIN is set in the database only). */
    public record RoleChange(
            @NotNull(message = "Email is required") String email,
            @NotNull(message = "Role is required") UserRole role) {
    }

    /** ADMIN: one audit-log entry. Account ids, not names or e-mails. */
    public record AuditEntry(String id, long actorId, ActionType action, ActionReason reason, String caseRef,
                             SignalCategory category, Long subjectUserId, LocalDateTime restrictedUntil,
                             UserRole newRole, LocalDateTime occurredAt) {
    }
}
