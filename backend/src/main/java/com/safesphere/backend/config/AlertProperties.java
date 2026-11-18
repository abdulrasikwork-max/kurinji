package com.safesphere.backend.config;

import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The app.alerts.* settings (Phase N6, D20: the server texts the Safety Circle when an SOS reaches it). Every value has a
 * safe default; secrets only from the environment. The limits are counted in the database (they survive a restart).
 *
 * @param provider          DEV_FILE (default: texts written to [devFile] on this computer, nothing sent) or FAST2SMS
 *                          (the shared Fast2SMS client, key and URL from app.otp.*); the prod profile requires FAST2SMS
 * @param secret            ALERT_SECRET: HMAC key for numbers (the stop list and the per-number limit); empty = derived
 *                          from JWT_SECRET (development only; the prod profile requires its own)
 * @param devFile           where DEV_FILE writes; empty = irl-dev-alerts.txt in the temp folder
 * @param jobEnabled        false = nothing runs by itself (tests call the dispatcher directly)
 * @param dispatchInterval  how often the queue is worked
 * @param sendDelay         the first text waits for the first location or this long, whichever comes first
 * @param staleAfter        an SOS that reaches the server later than this after it started is not texted
 * @param lateAfter         an SOS that reaches the server later than this after it started is texted as "sent late"
 * @param followUpWindow    a location that arrives within this time after a text without one is sent once
 * @param closingWindow     a closing text ("I'm safe" / false alarm) only within this time after the first text
 * @param maxContacts       contacts texted per SOS (by priority)
 * @param roundsPerPerson   SOS alert rounds per person per 24 h
 * @param perNumber         alert texts to one number per 24 h, across all senders
 * @param serverWide        alert texts per 24 h for the whole server
 * @param testsPerPerson    "Test my alerts" per person per 24 h
 * @param maxAttempts       provider attempts per text before it is FAILED
 * @param retryDelay        wait before the next attempt (times the attempt number)
 */
@ConfigurationProperties(prefix = "app.alerts")
public record AlertProperties(String provider, String secret, String devFile, Boolean jobEnabled,
                              Duration dispatchInterval, Duration sendDelay, Duration staleAfter, Duration lateAfter,
                              Duration followUpWindow, Duration closingWindow, Integer maxContacts,
                              Integer roundsPerPerson, Integer perNumber, Integer serverWide, Integer testsPerPerson,
                              Integer maxAttempts, Duration retryDelay) {

    public enum Provider { DEV_FILE, FAST2SMS }

    public AlertProperties {
        if (provider == null || provider.isBlank()) provider = Provider.DEV_FILE.name();
        if (secret == null) secret = "";
        if (devFile == null) devFile = "";
        if (jobEnabled == null) jobEnabled = true;
        if (dispatchInterval == null) dispatchInterval = Duration.ofSeconds(5);
        if (sendDelay == null) sendDelay = Duration.ofSeconds(20);
        if (staleAfter == null) staleAfter = Duration.ofMinutes(30);
        if (lateAfter == null) lateAfter = Duration.ofMinutes(2);
        if (followUpWindow == null) followUpWindow = Duration.ofMinutes(10);
        if (closingWindow == null) closingWindow = Duration.ofHours(24);
        if (maxContacts == null) maxContacts = 5;
        if (roundsPerPerson == null) roundsPerPerson = 5;
        if (perNumber == null) perNumber = 10;
        if (serverWide == null) serverWide = 100;
        if (testsPerPerson == null) testsPerPerson = 1;
        if (maxAttempts == null) maxAttempts = 3;
        if (retryDelay == null) retryDelay = Duration.ofSeconds(15);
        try {
            Provider.valueOf(provider.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.alerts.provider must be DEV_FILE or FAST2SMS");
        }
        if (!secret.isEmpty() && secret.length() < 32) {
            throw new IllegalStateException("ALERT_SECRET must be at least 32 characters (or empty to derive it)");
        }
        if (notPositive(dispatchInterval) || sendDelay.isNegative() || notPositive(staleAfter) || lateAfter.isNegative()
                || notPositive(followUpWindow) || notPositive(closingWindow) || notPositive(retryDelay)
                || maxContacts < 1 || maxContacts > 10 || roundsPerPerson < 1 || perNumber < 1 || serverWide < 1
                || testsPerPerson < 1 || maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalStateException("app.alerts.* values are invalid");
        }
    }

    public Provider providerKind() {
        return Provider.valueOf(provider.trim().toUpperCase(Locale.ROOT));
    }

    private static boolean notPositive(Duration d) {
        return d.isNegative() || d.isZero();
    }

    /** Never prints the secret. */
    @Override
    public String toString() {
        return "AlertProperties[provider=" + provider + "]";
    }
}
