package com.safesphere.backend.emergency.dto;

import java.time.Instant;

/**
 * Optional body of PUT /api/emergencies/{id}/resolve|cancel (Phase 10A.4; N2: a code instead of the password). The
 * phone that started the SOS sends no confirmation; ending it from another sign-in needs a fresh code texted to the
 * account's number ([challenge] from POST /api/users/me/confirmation, and the [code]).
 * Phase 11: [endedAt] = when the person ended it on a phone that was offline at that moment (clamped by the server
 * between the start and now); absent = now.
 */
public record EndEmergencyRequest(String challenge, String code, Instant endedAt) {

    public EndEmergencyRequest(String challenge, String code) {
        this(challenge, code, null);
    }

    @Override
    public String toString() {
        return "EndEmergencyRequest[redacted]";
    }
}
