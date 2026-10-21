package com.safesphere.backend.journey.entity;

/**
 * ACTIVE can end as COMPLETED ("I've arrived"), CANCELLED, or EMERGENCY (an SOS was started during the walk;
 * the emergency then takes over). All three are final.
 */
public enum JourneyStatus {
    ACTIVE,
    COMPLETED,
    CANCELLED,
    EMERGENCY
}
