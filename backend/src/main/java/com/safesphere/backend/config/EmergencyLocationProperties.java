package com.safesphere.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The app.emergency.locations.* settings. Every value has a safe default, so none of them is required.
 *
 * @param maxPerEmergency most points one emergency may store (protects the database from a runaway phone)
 * @param uploadGrace     how long after an emergency ends the phone may still upload points it collected
 *                        while it was ACTIVE (for example after being offline)
 * @param clockTolerance  how far the phone's clock may differ from the server's before a point is rejected
 */
@ConfigurationProperties(prefix = "app.emergency.locations")
public record EmergencyLocationProperties(Integer maxPerEmergency, Duration uploadGrace, Duration clockTolerance) {

    public EmergencyLocationProperties {
        if (maxPerEmergency == null) maxPerEmergency = 1000;
        if (uploadGrace == null) uploadGrace = Duration.ofMinutes(15);
        if (clockTolerance == null) clockTolerance = Duration.ofMinutes(5);
        if (maxPerEmergency < 1 || uploadGrace.isNegative() || clockTolerance.isNegative()) {
            throw new IllegalStateException("app.emergency.locations.* values must be positive");
        }
    }
}
