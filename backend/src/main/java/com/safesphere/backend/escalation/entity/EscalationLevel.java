package com.safesphere.backend.escalation.entity;

/**
 * The Escalation Ladder (Phase 4 design). Proportional: the automatic policy on the phone only ever moves one
 * step at a time (0 -> 1 -> 2); level 3 needs a person to confirm SOS; 4 is a dial prompt the person taps during
 * an emergency; 5 is reserved.
 */
public enum EscalationLevel {
    /** 0: everything normal (also recorded when the person checks in again after a missed check-in). */
    NORMAL(0),
    /** 1: a check-in was missed: the phone prompts the person. */
    CHECK_IN_MISSED(1),
    /** 2: no response to the prompt. Phase 4D: the PHONE may text the Safety Circle (opt-in); the server records it. */
    NO_RESPONSE(2),
    /** 3: confirmed SOS: an emergency is ACTIVE. */
    EMERGENCY(3),
    /** 4: emergency pathway: the person tapped "Call 112" during an ACTIVE emergency (dialer opened, no integration). */
    EMERGENCY_PATHWAY(4),
    /** 5: Verified Nearby Assistance (Phase 12). Reserved name, always rejected. */
    VERIFIED_NEARBY_ASSISTANCE(5);

    private final int code;

    EscalationLevel(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static EscalationLevel fromCode(int code) {
        for (EscalationLevel level : values()) {
            if (level.code == code) return level;
        }
        throw new IllegalArgumentException("Unknown escalation level " + code);
    }
}
