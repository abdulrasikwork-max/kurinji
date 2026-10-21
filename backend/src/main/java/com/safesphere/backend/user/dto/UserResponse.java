package com.safesphere.backend.user.dto;

import com.safesphere.backend.user.entity.User;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * What the API is allowed to say about a user. It has no password and no passwordHash field,
 * so they cannot leak by accident: the entity is never serialised directly.
 * Phase 10A: [deletionRequestedAt] / [deletionDueAt] are set while a deletion request is pending (the phone's banner);
 * both null otherwise.
 */
public record UserResponse(Long id, String name, String email, Instant createdAt, Instant deletionRequestedAt,
                           Instant deletionDueAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getCreatedAt().toInstant(ZoneOffset.UTC),
                utc(user.getDeletionRequestedAt()),
                utc(user.getDeletionDueAt())
        );
    }

    private static Instant utc(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
