package com.safesphere.backend.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Request and response bodies of the account endpoints. No user id anywhere: the caller is the JWT. */
public final class AccountDtos {

    private AccountDtos() {
    }

    /**
     * Phase N2: a fresh code texted to the person's own number (POST /api/users/me/confirmation) confirms a sensitive
     * action: deletion request and cancel, signing out the other phones. Replaces the password of Phase 10A.
     */
    public record CodeConfirmation(
            @NotBlank(message = "Ask for a code first") @Size(max = 64) String challenge,
            @NotBlank(message = "Enter the code") @Size(max = 10) String code) {

        @Override
        public String toString() {
            return "CodeConfirmation[redacted]";
        }
    }

    /** Phase N2 (N-24): PUT /api/users/me/name, the person's real name (RealNamePolicy); locked after N3's ID check. */
    public record NameChange(
            @NotBlank(message = "Enter your name") @Size(max = 200, message = "Your name is too long") String name) {
    }

    /** 202 answer of a deletion request: when it was made and when, at the earliest, the account will be deleted. */
    public record DeletionScheduled(Instant deletionRequestedAt, Instant deletionDueAt) {
    }
}
