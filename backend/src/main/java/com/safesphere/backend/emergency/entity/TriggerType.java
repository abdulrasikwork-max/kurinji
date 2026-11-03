package com.safesphere.backend.emergency.entity;

/**
 * What started the emergency. MANUAL (Phase 3) and SHAKE (Phase 17F, after the 5 s countdown on the phone) are
 * accepted; VOICE and DURESS_PIN are reserved and rejected.
 */
public enum TriggerType {
    MANUAL,
    SHAKE,
    VOICE,
    DURESS_PIN
}
