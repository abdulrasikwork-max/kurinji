package com.safesphere.backend.community.entity;

import java.util.Arrays;
import java.util.Optional;

/**
 * Phase N3: the closed lists of the community (V34 CHECK lists name exactly these values; stored as their names).
 */
public final class CommunityEnums {

    private CommunityEnums() {
    }

    public enum Level {
        UG, PG
    }

    /**
     * The MIET programmes (owner, 2026-11-16). The code is stored; the label is what people read. [years] is the usual
     * length, used only for the latest graduation year a person may give (no seat counts, no other data).
     */
    public enum Programme {
        BE_CSE("B.E. Computer Science & Engineering", Level.UG),
        BTECH_IT("B.Tech Information Technology", Level.UG),
        BE_MECH("B.E. Mechanical Engineering", Level.UG),
        BE_EEE("B.E. Electrical & Electronics Engineering", Level.UG),
        BE_ECE("B.E. Electronics & Communication Engineering", Level.UG),
        BTECH_AIDS("B.Tech Artificial Intelligence & Data Science", Level.UG),
        BE_CIVIL("B.E. Civil Engineering", Level.UG),
        BE_BME("B.E. Biomedical Engineering", Level.UG),
        ME_STRUCT("M.E. Structural Engineering", Level.PG),
        ME_MANUF("M.E. Manufacturing Engineering", Level.PG),
        ME_PED("M.E. Power Electronics & Drives", Level.PG),
        ME_VLSI("M.E. VLSI Design", Level.PG),
        ME_CSE("M.E. Computer Science & Engineering", Level.PG),
        MBA("MBA", Level.PG);

        private final String label;
        private final Level level;

        Programme(String label, Level level) {
            this.label = label;
            this.level = level;
        }

        public String label() {
            return label;
        }

        public Level level() {
            return level;
        }

        public int years() {
            return level == Level.UG ? 4 : 2;
        }

        public static Optional<Programme> of(String code) {
            return Arrays.stream(values()).filter(p -> p.name().equals(code)).findFirst();
        }
    }

    /** The two alternative ways to be verified; the applicant chooses ONE (owner, 2026-11-16). */
    public enum Method {
        IN_PERSON, ID_PHOTO
    }

    /** Why a membership ended. */
    public enum EndReason {
        GRADUATED, REMOVED, BANNED
    }

    /** Why a request was refused (shown to the applicant). */
    public enum RefusalReason {
        NAME_MISMATCH, ROLL_MISMATCH, NOT_THE_PERSON, NOT_MIET_ID, UNREADABLE, ALREADY_MEMBER, BANNED, OTHER;

        /** The reasons a person deciding may choose (the others are set by the server). */
        public boolean choosable() {
            return this != ALREADY_MEMBER && this != BANNED;
        }
    }

    /** Why someone was banned (shown to them). */
    public enum BanReason {
        HARASSMENT, HATE, THREATS, SEXUAL_CONTENT, IMPERSONATION, FALSE_ID, PRIVACY, REPEATED, OTHER
    }

    /** What the log records. */
    public enum ActionKind {
        VERIFIED, REFUSED, NAME_CORRECTED, VERIFIER_ADDED, VERIFIER_REMOVED, ENDED, BANNED, BAN_LIFTED, RELINKED
    }
}
