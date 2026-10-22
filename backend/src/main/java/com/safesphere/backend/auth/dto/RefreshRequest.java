package com.safesphere.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /api/auth/refresh and POST /api/auth/sign-out (Phase 11 C1). The token is a credential. */
public record RefreshRequest(
        @NotBlank(message = "Refresh token is required")
        @Size(max = 200, message = "Refresh token is invalid")
        String refreshToken) {

    @Override
    public String toString() {
        return "RefreshRequest[redacted]";
    }
}
