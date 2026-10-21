package com.safesphere.backend.auth.dto;

import com.safesphere.backend.user.dto.UserResponse;

/** Returned by register and login: the JWT plus basic, safe user information. */
public record AuthResponse(String accessToken, String tokenType, long expiresInSeconds, UserResponse user) {

    public static AuthResponse bearer(String accessToken, long expiresInSeconds, UserResponse user) {
        return new AuthResponse(accessToken, "Bearer", expiresInSeconds, user);
    }
}
