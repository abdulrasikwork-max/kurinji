package com.safesphere.backend.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase N3: the app.community.* settings (verified MIET membership). Every value has a safe default.
 *
 * @param secret            COMMUNITY_SECRET (≥ 32 characters): the key behind the roll-number and phone HMACs, the
 *                          in-person code HMAC and the ID-photo encryption. Empty = a development key derived from
 *                          JWT_SECRET (warned at start-up; the prod profile refuses to start)
 * @param photoDir          where encrypted ID photos wait for an admin (absolute in production; never served
 *                          statically; files deleted at the decision, withdrawal or expiry)
 * @param codeLifetime      how long an in-person code may be typed by a verifier
 * @param claimLifetime     how long after typing the code the same verifier may decide
 * @param requestLifetime   an undecided request (and its photo) is deleted after this
 * @param relinkLifetime    how long a "moving to a new number" code waits for an admin
 * @param requestsPerDay    new requests per person per day (in memory)
 * @param approvalsPerDay   approvals per verifier per 24 h (a misused verifier account is limited); admins exempt
 * @param wrongCodes        wrong codes a verifier or admin may type per [wrongCodeWindow] (in memory) before 429
 * @param wrongCodeWindow   the window for that limit
 * @param jobEnabled        the hourly upkeep (expiry, graduation, photo sweep); the tests call it directly
 */
@ConfigurationProperties(prefix = "app.community")
public record CommunityProperties(String secret, Path photoDir, Duration codeLifetime, Duration claimLifetime,
                                  Duration requestLifetime, Duration relinkLifetime, Integer requestsPerDay,
                                  Integer approvalsPerDay, Integer wrongCodes, Duration wrongCodeWindow,
                                  Boolean jobEnabled) {

    public CommunityProperties {
        if (photoDir == null) photoDir = Path.of("id-photo-store");
        if (codeLifetime == null) codeLifetime = Duration.ofMinutes(10);
        if (claimLifetime == null) claimLifetime = Duration.ofMinutes(15);
        if (requestLifetime == null) requestLifetime = Duration.ofDays(7);
        if (relinkLifetime == null) relinkLifetime = Duration.ofHours(1);
        if (requestsPerDay == null) requestsPerDay = 3;
        if (approvalsPerDay == null) approvalsPerDay = 50;
        if (wrongCodes == null) wrongCodes = 10;
        if (wrongCodeWindow == null) wrongCodeWindow = Duration.ofMinutes(15);
        if (jobEnabled == null) jobEnabled = true;
        if (secret != null && !secret.isBlank() && secret.length() < 32) {
            throw new IllegalStateException("COMMUNITY_SECRET must be at least 32 characters");
        }
        if (codeLifetime.compareTo(Duration.ofMinutes(1)) < 0 || codeLifetime.compareTo(Duration.ofHours(1)) > 0
                || claimLifetime.compareTo(Duration.ofMinutes(1)) < 0
                || requestLifetime.compareTo(Duration.ofDays(1)) < 0 || requestLifetime.compareTo(Duration.ofDays(30)) > 0
                || relinkLifetime.compareTo(Duration.ofMinutes(5)) < 0 || relinkLifetime.compareTo(Duration.ofDays(1)) > 0
                || requestsPerDay < 1 || approvalsPerDay < 1 || wrongCodes < 1
                || wrongCodeWindow.isNegative() || wrongCodeWindow.isZero()) {
            throw new IllegalStateException("app.community.* values are invalid");
        }
    }

    public boolean hasOwnSecret() {
        return secret != null && !secret.isBlank();
    }
}
