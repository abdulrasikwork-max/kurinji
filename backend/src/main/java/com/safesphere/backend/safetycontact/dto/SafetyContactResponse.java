package com.safesphere.backend.safetycontact.dto;

import com.safesphere.backend.safetycontact.entity.Relationship;
import com.safesphere.backend.safetycontact.entity.SafetyContact;
import java.time.Instant;
import java.time.ZoneOffset;

/** What the API returns about a contact. The owner's user id is intentionally not included. */
public record SafetyContactResponse(
        Long id,
        String name,
        String phoneNumber,
        String email,
        Relationship relationship,
        int priority,
        boolean primary,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {

    public static SafetyContactResponse from(SafetyContact contact) {
        return new SafetyContactResponse(
                contact.getId(),
                contact.getName(),
                contact.getPhoneNumber(),
                contact.getEmail(),
                contact.getRelationship(),
                contact.getPriority(),
                contact.isPrimaryContact(),
                contact.isEnabled(),
                contact.getCreatedAt().toInstant(ZoneOffset.UTC),
                contact.getUpdatedAt().toInstant(ZoneOffset.UTC)
        );
    }
}
