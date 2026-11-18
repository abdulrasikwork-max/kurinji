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
        Instant updatedAt,
        // N7 (D36): this contact accepted to get your SOS in the IRL app too (never shown before they accept).
        boolean inApp
) {

    public static SafetyContactResponse from(SafetyContact contact) {
        return from(contact, false);
    }

    public static SafetyContactResponse from(SafetyContact contact, boolean inApp) {
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
                contact.getUpdatedAt().toInstant(ZoneOffset.UTC),
                inApp
        );
    }
}
