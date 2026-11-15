package com.safesphere.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The app.auth.* settings (Phase 7: sign-up abuse limit; the password sign-in limits left with the password in N2).
 * Every value has a safe default.
 *
 * @param registrationsPerAddress new accounts from one network address per [registrationWindow] (account farming)
 * @param registrationWindow      the window for the registration limit
 * @param maxTrackedKeys          most addresses / numbers remembered at once per limiter (memory bound)
 */
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(Integer registrationsPerAddress, Duration registrationWindow, Integer maxTrackedKeys) {

    public AuthProperties {
        if (registrationsPerAddress == null) registrationsPerAddress = 10;
        if (registrationWindow == null) registrationWindow = Duration.ofHours(1);
        if (maxTrackedKeys == null) maxTrackedKeys = 100_000;
        if (registrationsPerAddress < 1 || maxTrackedKeys < 1
                || registrationWindow.isNegative() || registrationWindow.isZero()) {
            throw new IllegalStateException("app.auth.* values are invalid");
        }
    }
}
