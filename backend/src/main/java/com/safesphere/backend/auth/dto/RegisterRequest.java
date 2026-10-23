package com.safesphere.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of POST /api/auth/register. The rules match the checks the Android app does locally. */
public record RegisterRequest(

        @NotBlank(message = "Name is required")
        @Size(max = 100, message = "Name must be at most 100 characters")
        String name,

        @NotBlank(message = "Email is required")
        @Size(max = 255, message = "Email must be at most 255 characters")
        @Pattern(regexp = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$", message = "Enter a valid email address")
        String email,

        // 72 is a BCrypt limit: it ignores everything after 72 bytes.
        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        String password,

        // Phase 13A (D6): checked for 18+ and then discarded; never stored, never logged. Format yyyy-MM-dd.
        @NotNull(message = "Date of birth is required")
        LocalDate dateOfBirth
) {

    /** Never prints the password or the date of birth. */
    @Override
    public String toString() {
        return "RegisterRequest[name=" + name + ", email=" + email + "]";
    }
}
