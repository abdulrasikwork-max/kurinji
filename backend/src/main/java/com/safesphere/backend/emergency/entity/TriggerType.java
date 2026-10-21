package com.safesphere.backend.emergency.entity;

/**
 * What started the emergency. Only MANUAL is accepted in Phase 3; the others are reserved so later
 * phases can add them without changing the table.
 */
public enum TriggerType {
    MANUAL,
    SHAKE,
    VOICE,
    DURESS_PIN
}
