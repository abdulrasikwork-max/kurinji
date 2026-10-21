package com.safesphere.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The app.auth.* settings (Phase 7: sign-in and sign-up abuse limits). Every value has a safe default.
 *
 * @param loginFailuresPerAccount failed sign-ins for one e-mail address per [loginWindow] before further attempts for
 *                                that address are refused (stops password guessing against one person)
 * @param loginFailuresPerAddress failed sign-ins from one network address per [loginWindow] (stops one machine trying
 *                                many accounts); generous because a campus or mobile network shares addresses
 * @param loginWindow             the window for both sign-in limits
 * @param registrationsPerAddress new accounts from one network address per [registrationWindow] (account farming)
 * @param registrationWindow      the window for the registration limit
 * @param maxTrackedKeys          most addresses / accounts remembered at once per limiter (memory bound)
 */
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(Integer loginFailuresPerAccount, Integer loginFailuresPerAddress, Duration loginWindow,
                             Integer registrationsPerAddress, Duration registrationWindow, Integer maxTrackedKeys) {

    public AuthProperties {
        if (loginFailuresPerAccount == null) loginFailuresPerAccount = 10;
        if (loginFailuresPerAddress == null) loginFailuresPerAddress = 30;
        if (loginWindow == null) loginWindow = Duration.ofMinutes(15);
        if (registrationsPerAddress == null) registrationsPerAddress = 10;
        if (registrationWindow == null) registrationWindow = Duration.ofHours(1);
        if (maxTrackedKeys == null) maxTrackedKeys = 100_000;
        if (loginFailuresPerAccount < 1 || loginFailuresPerAddress < 1 || registrationsPerAddress < 1
                || maxTrackedKeys < 1 || loginWindow.isNegative() || loginWindow.isZero()
                || registrationWindow.isNegative() || registrationWindow.isZero()) {
            throw new IllegalStateException("app.auth.* values are invalid");
        }
    }
}
