package com.safesphere.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase N4: the app.moments.* settings. Only the abuse limits are settings; the product rules (24 hours, 7 days, 6
 * kept, the 7-day new-member period, 500 and 60 characters) are decisions and live in MomentPolicy as constants.
 * The limits are kept in memory per person (AttemptLimiter), like every other limit in this backend.
 *
 * @param perDay            moments an established member may share per day (PHASE_N4 §3: 10)
 * @param newMemberPerDay   moments a new member may share per day during their first 7 days (N-56: 3)
 * @param todayReadsPerHour Today pages a person may load per hour (stops scraping the wall)
 * @param questionsAhead    how many days ahead an admin may schedule a daily question
 */
@ConfigurationProperties(prefix = "app.moments")
public record MomentProperties(Integer perDay, Integer newMemberPerDay, Integer todayReadsPerHour,
                               Integer questionsAhead) {

    public MomentProperties {
        if (perDay == null) perDay = 10;
        if (newMemberPerDay == null) newMemberPerDay = 3;
        if (todayReadsPerHour == null) todayReadsPerHour = 600;
        if (questionsAhead == null) questionsAhead = 60;
        if (perDay < 1 || newMemberPerDay < 1 || todayReadsPerHour < 1 || questionsAhead < 1 || questionsAhead > 366) {
            throw new IllegalStateException("app.moments.* values are invalid");
        }
    }
}
