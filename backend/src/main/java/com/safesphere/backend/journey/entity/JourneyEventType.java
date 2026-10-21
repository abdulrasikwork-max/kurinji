package com.safesphere.backend.journey.entity;

/** What happened during a journey. Stored as text (migration V5 checks the allowed values). */
public enum JourneyEventType {
    STARTED,
    CHECKED_IN,
    COMPLETED,
    CANCELLED,
    EMERGENCY
}
