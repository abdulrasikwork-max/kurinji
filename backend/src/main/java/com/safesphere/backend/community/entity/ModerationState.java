package com.safesphere.backend.community.entity;

/**
 * Moderation state of a place signal (Phase 7, V13). Anything but NONE is hidden from everyone except the people who
 * contributed to it (who see "under review") and moderators.
 */
public enum ModerationState {
    NONE,
    /** Hidden while a case is reviewed: set automatically by independent flags, or by a moderator. Reversible. */
    QUARANTINED,
    /** Hidden by a moderator's decision. The row stays (no contributions) until moderation_until as a tombstone. */
    REMOVED
}
