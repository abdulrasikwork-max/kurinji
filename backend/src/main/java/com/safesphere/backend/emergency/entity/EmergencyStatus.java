package com.safesphere.backend.emergency.entity;

/**
 * Life cycle of a recorded emergency:
 *   ACTIVE -> RESOLVED  ("I'm safe")
 *   ACTIVE -> CANCELLED ("false alarm")
 * Both end states are final. (The 5-second countdown before ACTIVE exists only in the app.)
 */
public enum EmergencyStatus {
    ACTIVE,
    CANCELLED,
    RESOLVED
}
