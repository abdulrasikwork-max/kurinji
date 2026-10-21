package com.safesphere.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The app.moderation.* settings (Phase 7, docs/PHASE7.md). Every value has a safe default.
 *
 * @param removalHold            how long a removed signal stays as a hidden tombstone (re-reports join it and only
 *                               reopen a case, so a removed fake cannot simply be posted again at once)
 * @param closedCaseRetention    closed cases are deleted after this
 * @param auditRetention         moderation audit entries (and their appeals) are deleted after this
 * @param maxFlagsPerDay         abuse flags one person may give in 24 h
 * @param moderatorRequestsPerHour moderation API requests per moderator per hour (limits a stolen moderator token)
 * @param pageSize               most cases / appeals / log entries per answer
 */
@ConfigurationProperties(prefix = "app.moderation")
public record ModerationProperties(Duration removalHold, Duration closedCaseRetention, Duration auditRetention,
                                   Integer maxFlagsPerDay, Integer moderatorRequestsPerHour, Integer pageSize) {

    public ModerationProperties {
        if (removalHold == null) removalHold = Duration.ofDays(30);
        if (closedCaseRetention == null) closedCaseRetention = Duration.ofDays(90);
        if (auditRetention == null) auditRetention = Duration.ofDays(365);
        if (maxFlagsPerDay == null) maxFlagsPerDay = 10;
        if (moderatorRequestsPerHour == null) moderatorRequestsPerHour = 600;
        if (pageSize == null) pageSize = 50;
        if (removalHold.isNegative() || closedCaseRetention.isNegative() || auditRetention.isNegative()
                || maxFlagsPerDay < 1 || moderatorRequestsPerHour < 1 || pageSize < 1 || pageSize > 200) {
            throw new IllegalStateException("app.moderation.* values are invalid");
        }
    }
}
