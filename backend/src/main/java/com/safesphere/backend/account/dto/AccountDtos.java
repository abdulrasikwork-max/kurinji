package com.safesphere.backend.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Request and response bodies of the Phase 10A account endpoints. No user id anywhere: the caller is the JWT. */
public final class AccountDtos {

    private AccountDtos() {
    }

    /** PUT /api/users/me/password. The new password follows the sign-up rules (8–72 characters, 72 bytes at most). */
    public record ChangePasswordRequest(
            @NotBlank(message = "Current password is required")
            @Size(max = 72, message = "Password is incorrect")
            String currentPassword,

            @NotBlank(message = "New password is required")
            @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
            String newPassword) {

        @Override
        public String toString() {
            return "ChangePasswordRequest[redacted]";
        }
    }

    /** POST /api/users/me/deletion and POST /api/users/me/deletion/cancel. */
    public record PasswordConfirmation(
            @NotBlank(message = "Password is required")
            @Size(max = 72, message = "Password is incorrect")
            String password) {

        @Override
        public String toString() {
            return "PasswordConfirmation[redacted]";
        }
    }

    /** 202 answer of a deletion request: when it was made and when, at the earliest, the account will be deleted. */
    public record DeletionScheduled(Instant deletionRequestedAt, Instant deletionDueAt) {
    }
}
