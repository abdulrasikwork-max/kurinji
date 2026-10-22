package com.safesphere.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/auth/login. Only presence is checked; a wrong value is simply "invalid credentials".
 *
 * Phase 11 C1: [previousRefreshToken] (optional) = the refresh token this phone still holds for the SAME account (for
 * example after "sign in to sync"). With the right password it ends that old sign-in on the server and hands its
 * running SOS to the new one. A token of another account is ignored (the phone never sends one).
 */
public record LoginRequest(

        @NotBlank(message = "Email is required")
        String email,

        @NotBlank(message = "Password is required")
        String password,

        @Size(max = 200, message = "Refresh token is invalid")
        String previousRefreshToken
) {

    public LoginRequest(String email, String password) {
        this(email, password, null);
    }

    @Override
    public String toString() {
        return "LoginRequest[redacted]";
    }
}
