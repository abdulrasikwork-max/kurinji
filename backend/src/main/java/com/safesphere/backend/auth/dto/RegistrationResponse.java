package com.safesphere.backend.auth.dto;

/**
 * V25: answer of POST /api/auth/register. No session and no token: the account becomes usable only after its owner
 * opens the link sent to [email] (the normalised address). [status] is always "VERIFICATION_REQUIRED".
 */
public record RegistrationResponse(String status, String email) {

    public static RegistrationResponse verificationRequired(String email) {
        return new RegistrationResponse("VERIFICATION_REQUIRED", email);
    }
}
