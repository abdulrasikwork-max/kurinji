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

    /**
     * Phase 7 actions, and (13D, V23) the decisions about posts, perspectives, people and places. A 13D decision always
     * names its [TargetKind] in the audit row; a Phase 7 one never does.
     */
    public enum ActionType {
        QUARANTINE, DISMISS, REMOVE, RESTRICT, APPEAL_UPHELD, APPEAL_OVERTURNED, ROLE_GRANTED, ROLE_REVOKED,
        CONTENT_KEEP, CONTENT_HOLD, CONTENT_COLLAPSE, CONTENT_REMOVE,
        PERSON_KEEP, PERSON_WARN, PERSON_PAUSE_COMMENTS, PERSON_PAUSE_POSTING, PERSON_SUSPEND,
        PLACE_MERGED;

        /** 13D: a decision about a person (adds a strike unless it is PERSON_KEEP). */
        public boolean aboutPerson() {
            return this == PERSON_KEEP || this == PERSON_WARN || this == PERSON_PAUSE_COMMENTS
                    || this == PERSON_PAUSE_POSTING || this == PERSON_SUSPEND;
        }

        /** 13D: the decisions the person concerned may ask a different moderator to look at again (M11). */
        public boolean appealableSocial() {
            return this == CONTENT_COLLAPSE || this == CONTENT_REMOVE || this == PERSON_PAUSE_COMMENTS
                    || this == PERSON_PAUSE_POSTING || this == PERSON_SUSPEND;
        }
    }

    /**
     * Reason codes only: no free text in moderation (it would invite personal data and opinions about people). 13D adds
     * the content codes (the same names as PostEnums.ModerationReason without NEEDS_REVIEW, which already exists).
     */
    public enum ActionReason {
        FALSE_INFORMATION, TARGETS_SOMEONE, SPAM_OR_COORDINATED, DUPLICATE, NO_VIOLATION, NEEDS_REVIEW,
        APPEAL_REVIEWED, ROLE_CHANGE,
        HARASSMENT, SEXUAL_HARASSMENT, HATE, THREAT, DOXXING, SPAM, NUDITY_OR_VIOLENCE, IMPERSONATION, OTHER
    }

    public enum AppealStatus { OPEN, UPHELD, OVERTURNED }

    /** Phase 13D (V23): what a 13D decision or case is about. */
    public enum TargetKind { POST, COMMENT, PERSON, PLACE }

    /** Phase 13D: the result of a content case, as its reporters see it. */
    public enum ContentOutcome { NO_VIOLATION, ACTION_TAKEN }
}
