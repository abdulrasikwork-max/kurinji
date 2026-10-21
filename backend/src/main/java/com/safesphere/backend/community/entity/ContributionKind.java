package com.safesphere.backend.community.entity;

/** One person's answer to a place signal. Exactly one per person per signal (V12 unique key). */
public enum ContributionKind {
    /** "This is here" (the first one creates the signal; later ones merge into it). */
    REPORT,
    /** "I saw this too". */
    CONFIRM,
    /** "This is no longer true" (for example the light was repaired). */
    NO_LONGER;

    public boolean supports() {
        return this != NO_LONGER;
    }
}
