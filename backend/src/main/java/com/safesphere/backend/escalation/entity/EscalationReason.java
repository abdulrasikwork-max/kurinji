package com.safesphere.backend.escalation.entity;

/** Why the level changed. Stored as text (migrations V6/V7 check the allowed values). */
public enum EscalationReason {
    CHECKED_IN,
    CHECK_IN_MISSED,
    NO_RESPONSE,
    SOS_CONFIRMED,
    /** Old rows only (N6-B1 removed route / stop checks). Phase 4C, level 1 only: the phone saw a sustained departure from the Safety Bubble. */
    ROUTE_DEVIATION,
    /** Old rows only (N6-B1). Phase 4C, level 1 only: the phone saw no movement for a long time away from the destination. */
    UNEXPECTED_STOP,
    /** Phase 4D, level 4 only: the person tapped "Call 112" and the phone's dialer was opened (not: a call was made). */
    DIAL_PROMPT_OPENED
}
