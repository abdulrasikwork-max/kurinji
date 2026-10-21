package com.safesphere.backend.user.dto;

import com.safesphere.backend.user.entity.User;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * What the API is allowed to say about a user. It has no password and no passwordHash field,
 * so they cannot leak by accident: the entity is never serialised directly.
 */
public record UserResponse(Long id, String name, String email, Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getCreatedAt().toInstant(ZoneOffset.UTC)
        );
    }
}
