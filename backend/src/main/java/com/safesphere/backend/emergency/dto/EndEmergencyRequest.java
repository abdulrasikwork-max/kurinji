package com.safesphere.backend.emergency.dto;

import java.time.Instant;

/**
 * Optional body of PUT /api/emergencies/{id}/resolve|cancel (Phase 10A.4). The phone that started the SOS sends no
 * password; ending it from another sign-in needs the account [password].
 * Phase 11: [endedAt] = when the person ended it on a phone that was offline at that moment (clamped by the server
 * between the start and now); absent = now.
 */
public record EndEmergencyRequest(String password, Instant endedAt) {

    public EndEmergencyRequest(String password) {
        this(password, null);
    }

    @Override
    public String toString() {
        return "EndEmergencyRequest[redacted]";
    }
}
