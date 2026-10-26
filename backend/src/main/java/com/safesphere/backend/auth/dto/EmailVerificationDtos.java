package com.safesphere.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** V25: request and answer bodies of the e-mail verification endpoints. */
public final class EmailVerificationDtos {

    private EmailVerificationDtos() {
    }

    /** POST /api/auth/verify-email. The token is a credential: never printed. */
    public record VerifyEmailRequest(@NotBlank(message = "Token is required") @Size(max = 100) String token) {
        @Override
        public String toString() {
            return "VerifyEmailRequest[redacted]";
        }
    }

    /** POST /api/auth/verification/resend: the password proves the request comes from the account's owner. */
    public record ResendVerificationRequest(
            @NotBlank(message = "Email is required") @Size(max = 255) String email,
            @NotBlank(message = "Password is required") @Size(max = 200) String password) {
        @Override
        public String toString() {
            return "ResendVerificationRequest[redacted]";
        }
    }

    public record VerificationStatus(String status) {
        public static final VerificationStatus VERIFIED = new VerificationStatus("VERIFIED");
        public static final VerificationStatus RECEIVED = new VerificationStatus("RECEIVED");
    }
}
