package com.safesphere.backend.user.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** Body of POST /api/users/me/age-confirmation (Phase 13A). The date is checked for 18+ and then discarded. */
public record AgeConfirmationRequest(
        @NotNull(message = "Date of birth is required")
        LocalDate dateOfBirth
) {

    /** Never prints the date of birth. */
    @Override
    public String toString() {
        return "AgeConfirmationRequest[]";
    }
}
