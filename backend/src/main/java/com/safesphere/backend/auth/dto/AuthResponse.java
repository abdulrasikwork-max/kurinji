package com.safesphere.backend.auth.dto;

import com.safesphere.backend.user.dto.UserResponse;

/**
 * Returned by register, login, refresh, token renewal and password change: the JWT plus basic, safe user information.
 * Phase 11 C1: [refreshToken] is set when this answer starts or rotates a device session (register, login, refresh, and
 * the one-time adoption of an older sign-in); null otherwise, and then the phone keeps the secret it has.
 */
public record AuthResponse(String accessToken, String tokenType, long expiresInSeconds, UserResponse user,
                           String refreshToken) {

    public static AuthResponse bearer(String accessToken, long expiresInSeconds, UserResponse user) {
        return new AuthResponse(accessToken, "Bearer", expiresInSeconds, user, null);
    }

    public static AuthResponse bearer(String accessToken, long expiresInSeconds, UserResponse user, String refreshToken) {
        return new AuthResponse(accessToken, "Bearer", expiresInSeconds, user, refreshToken);
    }

    @Override
    public String toString() {
        return "AuthResponse[redacted]";
    }
}
