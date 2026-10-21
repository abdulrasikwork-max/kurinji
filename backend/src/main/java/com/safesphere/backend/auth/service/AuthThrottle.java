package com.safesphere.backend.auth.service;

import com.safesphere.backend.common.exception.TooManyAttemptsException;
import com.safesphere.backend.config.AuthProperties;
import com.safesphere.backend.security.AttemptLimiter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Sign-in and sign-up abuse limits (Phase 7; the Phase 2 gap "login has no rate limit").
 *
 *   - failed sign-ins per ACCOUNT (e-mail): stops guessing one person's password from many machines;
 *   - failed sign-ins per network ADDRESS: stops one machine trying many accounts (credential stuffing);
 *   - new accounts per network ADDRESS: slows account farming for sock-puppet confirmations.
 *
 * Unknown e-mail addresses are counted exactly like known ones, so the limit never reveals whether an account exists.
 * Only FAILURES count; a correct password resets the account counter (not the address counter).
 *
 * A safety trade-off, documented in docs/PHASE7.md: someone who knows a person's e-mail can delay that person's NEW
 * sign-ins for one window. Sessions already signed in (24 h tokens) and everything on the phone, including SOS, keep
 * working. The account limit is therefore generous (10 per 15 min) and short.
 *
 * E-mail addresses are kept only as SHA-256 hashes in memory; nothing is written to the database or the log.
 * Network addresses are the TCP peer address; X-Forwarded-For is NOT trusted (any client can forge it).
 */
@Component
public class AuthThrottle {

    static final String TOO_MANY_SIGN_INS = "Too many sign-in attempts. Please wait a few minutes and try again.";
    static final String TOO_MANY_SIGN_UPS = "Too many new accounts from this network. Please try again later.";

    private final AttemptLimiter accountFailures;
    private final AttemptLimiter addressFailures;
    private final AttemptLimiter registrations;

    public AuthThrottle(AuthProperties properties, Clock clock) {
        this.accountFailures = new AttemptLimiter(properties.loginFailuresPerAccount(), properties.loginWindow(),
                properties.maxTrackedKeys(), clock);
        this.addressFailures = new AttemptLimiter(properties.loginFailuresPerAddress(), properties.loginWindow(),
                properties.maxTrackedKeys(), clock);
        this.registrations = new AttemptLimiter(properties.registrationsPerAddress(), properties.registrationWindow(),
                properties.maxTrackedKeys(), clock);
    }

    /** Before checking a password: refuses (429) when this account or this address is over its failure limit. */
    public void checkSignIn(String normalizedEmail, String address) {
        if (accountFailures.isBlocked(hash(normalizedEmail)) || addressFailures.isBlocked(address(address))) {
            throw new TooManyAttemptsException(TOO_MANY_SIGN_INS);
        }
    }

    public void signInFailed(String normalizedEmail, String address) {
        accountFailures.record(hash(normalizedEmail));
        addressFailures.record(address(address));
    }

    public void signInSucceeded(String normalizedEmail) {
        accountFailures.reset(hash(normalizedEmail));
    }

    /** Counts one registration attempt from [address]; refuses (429) when the address is over its limit. */
    public void checkRegistration(String address) {
        if (!registrations.tryAcquire(address(address))) {
            throw new TooManyAttemptsException(TOO_MANY_SIGN_UPS);
        }
    }

    /** Drops finished windows (called by the periodic clean-up). */
    public void evictExpired() {
        accountFailures.evictExpired();
        addressFailures.evictExpired();
        registrations.evictExpired();
    }

    private static String address(String address) {
        return address == null || address.isBlank() ? "unknown" : address;
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
