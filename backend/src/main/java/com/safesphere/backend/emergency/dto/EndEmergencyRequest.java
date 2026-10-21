package com.safesphere.backend.emergency.dto;

/**
 * Optional body of PUT /api/emergencies/{id}/resolve|cancel (Phase 10A.4). The phone that started the SOS sends no
 * password; ending it from another sign-in needs the account [password].
 */
public record EndEmergencyRequest(String password) {

    @Override
    public String toString() {
        return "EndEmergencyRequest[redacted]";
    }
}
