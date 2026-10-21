package com.safesphere.backend.community.entity;

/**
 * Why someone says a place signal is wrong (Phase 7). A closed list with no free text, like the categories.
 * "No longer true" is NOT a flag: a change of the place is answered with a NO_LONGER contribution.
 */
public enum FlagReason {
    /** It did not happen / the place is not like that. */
    FALSE_INFORMATION,
    /** It is aimed at a person, a home or a business rather than describing a place. */
    TARGETS_SOMEONE,
    /** Looks automated or coordinated (many fake reports). */
    SPAM_OR_COORDINATED
}
