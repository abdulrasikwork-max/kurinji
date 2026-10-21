package com.safesphere.backend.safetycontact.dto;

import com.safesphere.backend.safetycontact.entity.Relationship;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of POST and PUT /api/safety-contacts. There is deliberately NO userId field: the owner always
 * comes from the JWT. primary and enabled are optional (defaults: not primary, enabled).
 */
public record SafetyContactRequest(

        @NotBlank(message = "Name is required")
        @Size(max = 100, message = "Name must be at most 100 characters")
        String name,

        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^\\+?[0-9\\s().-]{7,30}$", message = "Enter a valid phone number")
        String phoneNumber,

        // Optional. An empty value is allowed and stored as "no email".
        @Size(max = 255, message = "Email must be at most 255 characters")
        @Pattern(regexp = "^$|^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$", message = "Enter a valid email address")
        String email,

        @NotNull(message = "Relationship is required")
        Relationship relationship,

        @NotNull(message = "Priority is required")
        @Min(value = 1, message = "Priority must be between 1 and 5")
        @Max(value = 5, message = "Priority must be between 1 and 5")
        Integer priority,

        Boolean primary,

        Boolean enabled
) {
}
