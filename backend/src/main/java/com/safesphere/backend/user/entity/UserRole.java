package com.safesphere.backend.user.entity;

/**
 * What an account may do beyond its own data (Phase 7, V13). Stored in users.role and read from the database on every
 * moderation request, never from the token or the client, so a revoked moderator loses access immediately.
 *
 *   USER       everyone
 *   MODERATOR  reviews community cases and appeals (cannot change roles)
 *   ADMIN      a moderator who can also appoint / remove moderators and read the audit log; set only in the database
 */
public enum UserRole {
    USER,
    MODERATOR,
    ADMIN;

    public boolean canModerate() {
        return this == MODERATOR || this == ADMIN;
    }
}
