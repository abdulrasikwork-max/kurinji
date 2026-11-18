package com.safesphere.backend.config;

import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The app.push.* settings (Phase N7, D26-D35: push notifications through Firebase Cloud Messaging). Every value has a safe
 * default; the credentials only from the environment (a file path, never the key itself). Limits are counted in the
 * database.
 *
 * @param provider          DEV_FILE (default: written to [devFile], nothing sent), FCM (HTTP v1) or NONE (kill switch:
 *                          nothing is queued or sent); the prod profile refuses DEV_FILE
 * @param credentialsFile   FCM_CREDENTIALS_FILE: the Firebase service-account JSON (project id, client e-mail, private
 *                          key); required for FCM, outside the repository
 * @param devFile           where DEV_FILE writes; empty = irl-dev-pushes.txt in the temp folder
 * @param jobEnabled        false = nothing runs by itself (tests call the dispatcher and the clean-up directly)
 * @param dispatchInterval  how often the queue is worked (besides the kick after each write)
 * @param socialPerHour     social pushes per person per hour (then SKIPPED; Replies still shows everything)
 * @param socialPerDay      social pushes per person per 24 h
 * @param batchWindow       "I'm in" within this time become one push
 * @param maxAttempts       FCM attempts per message before it is FAILED
 * @param retryDelay        wait before the next attempt (times the attempt number), unless FCM says how long
 * @param staleTokenAfter   a token not refreshed for this long is deleted
 * @param retention         queue rows are deleted this long after they were created
 * @param cleanupInterval   how often the clean-up runs
 */
@ConfigurationProperties(prefix = "app.push")
public record PushProperties(String provider, String credentialsFile, String devFile, Boolean jobEnabled,
                             Duration dispatchInterval, Integer socialPerHour, Integer socialPerDay, Duration batchWindow,
                             Integer maxAttempts, Duration retryDelay, Duration staleTokenAfter, Duration retention,
                             Duration cleanupInterval) {

    public enum Provider { DEV_FILE, FCM, NONE }

    public PushProperties {
        if (provider == null || provider.isBlank()) provider = Provider.DEV_FILE.name();
        if (credentialsFile == null) credentialsFile = "";
        if (devFile == null) devFile = "";
        if (jobEnabled == null) jobEnabled = true;
        if (dispatchInterval == null) dispatchInterval = Duration.ofSeconds(2);
        if (socialPerHour == null) socialPerHour = 6;
        if (socialPerDay == null) socialPerDay = 20;
        if (batchWindow == null) batchWindow = Duration.ofMinutes(2);
        if (maxAttempts == null) maxAttempts = 5;
        if (retryDelay == null) retryDelay = Duration.ofSeconds(20);
        if (staleTokenAfter == null) staleTokenAfter = Duration.ofDays(60);
        if (retention == null) retention = Duration.ofDays(7);
        if (cleanupInterval == null) cleanupInterval = Duration.ofHours(1);
        try {
            Provider.valueOf(provider.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.push.provider must be DEV_FILE, FCM or NONE");
        }
        if (notPositive(dispatchInterval) || socialPerHour < 1 || socialPerDay < 1 || batchWindow.isNegative()
                || maxAttempts < 1 || maxAttempts > 20 || notPositive(retryDelay) || notPositive(staleTokenAfter)
                || notPositive(retention) || notPositive(cleanupInterval)) {
            throw new IllegalStateException("app.push.* values are invalid");
        }
    }

    public Provider providerKind() {
        return Provider.valueOf(provider.trim().toUpperCase(Locale.ROOT));
    }

    private static boolean notPositive(Duration d) {
        return d.isNegative() || d.isZero();
    }

    /** Never prints the credentials path's content (there is none here) or anything else secret. */
    @Override
    public String toString() {
        return "PushProperties[provider=" + provider + "]";
    }
}
