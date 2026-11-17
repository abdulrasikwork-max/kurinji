package com.safesphere.backend.social.entity;

/**
 * The closed lists of a moment's moderation state (Phase 13D / 13E for posts, V23 and V24; since Phase N4 for moments,
 * V35, whose CHECKs use the same codes). A code is added by a migration, never by free text.
 */
public final class ContentModeration {

    private ContentModeration() {
    }

    /**
     * COLLAPSED = shown behind a tap (by independent reports while a moderator looks, by the automatic check, or by a
     * moderator's decision); HELD = hidden from everyone but its author while a person looks at it; REMOVED = hidden
     * from everyone but its author by a moderator's decision.
     */
    public enum ModerationState {
        NONE, COLLAPSED, HELD, REMOVED;

        /** Whether people other than the author may see it at all. */
        public boolean visibleToOthers() {
            return this == NONE || this == COLLAPSED;
        }
    }

    /**
     * Why something was reported or decided. Reports use the first nine codes; IMPERSONATION fits people only; OTHER
     * needs a short note from the reporter. The rest are never chosen by a reporter:
     * NEEDS_REVIEW = held for a person to look at; AWAITING_CHECK = a new member's words wait for the automatic check
     * (at most 2 hours); NEW_MEMBER_PHOTO (N-57) = a new member's photo for the whole community waits for a moderator;
     * ABOUT_ME (N-52) = someone said "This is about me": hidden at once until a moderator decides.
     */
    public enum ModerationReason {
        HARASSMENT, SEXUAL_HARASSMENT, HATE, THREAT, DOXXING, SPAM, NUDITY_OR_VIOLENCE, IMPERSONATION, OTHER,
        NEEDS_REVIEW, AWAITING_CHECK, NEW_MEMBER_PHOTO, ABOUT_ME;

        /** A code a person may choose when they report something. */
        public boolean reportable() {
            return ordinal() <= OTHER.ordinal();
        }
    }

    /**
     * Who set a moderation state (null while NONE). REPORTS = a collapse by independent reports while a moderator
     * looks; SYSTEM = a rule (the automatic check, the new-member photo hold, "This is about me"): collapsed or held,
     * never removed; MODERATOR = a human decision.
     */
    public enum ModerationSource {
        REPORTS, SYSTEM, MODERATOR;

        /** The source of a state set by reports (no decision) or by a moderator (a decision id); null for NONE. */
        public static ModerationSource of(ModerationState state, String decision) {
            if (state == ModerationState.NONE) return null;
            return decision == null ? REPORTS : MODERATOR;
        }
    }
}
