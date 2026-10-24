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
 * @param reportsPerDay          13D: reports (posts, perspectives, people) one person may file in 24 h
 * @param collapseReports        13D (M6): independent counting reports that collapse a post or perspective while it
 *                               waits for a moderator (the k = 3 floor of the butterfly count, C2)
 */
@ConfigurationProperties(prefix = "app.moderation")
public record ModerationProperties(Duration removalHold, Duration closedCaseRetention, Duration auditRetention,
                                   Integer maxFlagsPerDay, Integer moderatorRequestsPerHour, Integer pageSize,
                                   Integer reportsPerDay, Integer collapseReports) {

    public ModerationProperties {
        if (reportsPerDay == null) reportsPerDay = 20;
        if (collapseReports == null) collapseReports = 3;
        if (reportsPerDay < 1 || collapseReports < 2) {
            throw new IllegalStateException("app.moderation.* values are invalid");
        }
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
