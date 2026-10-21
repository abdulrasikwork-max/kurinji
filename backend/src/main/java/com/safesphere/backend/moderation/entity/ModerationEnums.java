package com.safesphere.backend.moderation.entity;

/** The closed vocabularies of Phase 7 moderation (same names as the V13 CHECK constraints). */
public final class ModerationEnums {

    private ModerationEnums() {
    }

    /** Why a case was opened. */
    public enum CaseOrigin {
        /** People flagged the signal. */
        FLAGS,
        /** A removed signal was reported again by independent people while its tombstone was still active. */
        REREPORTED,
        /** Its only support comes from accounts that keep acting together (possible sock puppets). */
        COORDINATION
    }

    /**
     * Phase 9B (V15): which rule opened a COORDINATION case, stored on the case so a decision is counted against the
     * rule that asked for it (Phase 7's linked group, or coord-1's R1 / R2).
     */
    public enum CoordinationRule { LINKED_GROUP, SUPPORT_CORE, EXCLUSIVE_PAIR }

    public enum CaseStatus { OPEN, CLOSED }

    public enum CaseOutcome { DISMISSED, REMOVED }

    public enum ActionType {
        QUARANTINE, DISMISS, REMOVE, RESTRICT, APPEAL_UPHELD, APPEAL_OVERTURNED, ROLE_GRANTED, ROLE_REVOKED
    }

    /** Reason codes only: no free text in moderation (it would invite personal data and opinions about people). */
    public enum ActionReason {
        FALSE_INFORMATION, TARGETS_SOMEONE, SPAM_OR_COORDINATED, DUPLICATE, NO_VIOLATION, NEEDS_REVIEW,
        APPEAL_REVIEWED, ROLE_CHANGE
    }

    public enum AppealStatus { OPEN, UPHELD, OVERTURNED }
}
