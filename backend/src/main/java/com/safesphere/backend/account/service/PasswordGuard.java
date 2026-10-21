package com.safesphere.backend.account.service;

import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.TooManyAttemptsException;
import com.safesphere.backend.config.AccountProperties;
import com.safesphere.backend.security.AttemptLimiter;
import com.safesphere.backend.user.entity.User;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Phase 10A: the one check of "type your password again" for account actions (password change, deletion request and
 * cancel; since 10A.4 also ending an SOS from another phone). Wrong passwords for all of them together are limited to
 * [AccountProperties.passwordFailures] per window per account (429), in memory. A wrong password answers 409, never 401
 * (a 401 would make the phone sign out).
 */
@Component
public class PasswordGuard {

    static final String WRONG_PASSWORD = "Password is incorrect";
    static final String TOO_MANY = "Too many wrong passwords. Please wait a few minutes and try again.";
    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

    private final PasswordEncoder passwordEncoder;
    private final AttemptLimiter failures;

    public PasswordGuard(PasswordEncoder passwordEncoder, AccountProperties properties, Clock clock) {
        this.passwordEncoder = passwordEncoder;
        this.failures = new AttemptLimiter(properties.passwordFailures(), properties.passwordFailureWindow(),
                properties.maxTrackedKeys(), clock);
    }

    /** 429 before any comparison while over the limit; a wrong password is counted, a right one resets the count. */
    public void require(User user, String password) {
        String key = String.valueOf(user.getId());
        if (failures.isBlocked(key)) throw new TooManyAttemptsException(TOO_MANY);
        if (password == null || tooLong(password) || !passwordEncoder.matches(password, user.getPasswordHash())) {
            failures.record(key);
            throw new ConflictException(WRONG_PASSWORD);
        }
        failures.reset(key);
    }

    public void evictExpired() {
        failures.evictExpired();
    }

    static boolean tooLong(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_PASSWORD_BYTES;
    }
}
