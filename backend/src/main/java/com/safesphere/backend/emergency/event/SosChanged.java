package com.safesphere.backend.emergency.event;

/**
 * N6: published (inside the writing transaction) when an SOS was recorded, got its first location or ended, so the
 * Safety Circle alert queue can work at once instead of at its next tick. Carries the id only; the listener reads
 * nothing from it but the fact that something changed. Emergency code never imports the alert code.
 */
public record SosChanged(long emergencyId) {
}
