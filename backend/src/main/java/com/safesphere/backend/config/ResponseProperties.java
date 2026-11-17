package com.safesphere.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase N5: the app.responses.* settings. Only the abuse limits are settings; the product rules (bands, close times,
 * 500 characters, turns) are decisions and live in ResponsePolicy as constants. Kept in memory per person
 * (AttemptLimiter), like every other limit here.
 *
 * @param signalsPerDay           Same and Say more taps (on and off) per day (N5 §3: 200)
 * @param asksPerDay              I'm in asks per day for an established member (20)
 * @param newMemberAsksPerDay     I'm in asks per day during the 7-day new-member period (10)
 * @param repliesPerDay           new private conversations per day for an established member (10)
 * @param newMemberRepliesPerDay  new private conversations per day during the new-member period (3)
 * @param messagesPerDay          messages per day (300)
 * @param messagesPerMinute       messages per minute (10: no bursts)
 * @param repliesReadsPerHour     Replies and marker reads per hour (600)
 */
@ConfigurationProperties(prefix = "app.responses")
public record ResponseProperties(Integer signalsPerDay, Integer asksPerDay, Integer newMemberAsksPerDay,
                                 Integer repliesPerDay, Integer newMemberRepliesPerDay, Integer messagesPerDay,
                                 Integer messagesPerMinute, Integer repliesReadsPerHour) {

    public ResponseProperties {
        if (signalsPerDay == null) signalsPerDay = 200;
        if (asksPerDay == null) asksPerDay = 20;
        if (newMemberAsksPerDay == null) newMemberAsksPerDay = 10;
        if (repliesPerDay == null) repliesPerDay = 10;
        if (newMemberRepliesPerDay == null) newMemberRepliesPerDay = 3;
        if (messagesPerDay == null) messagesPerDay = 300;
        if (messagesPerMinute == null) messagesPerMinute = 10;
        if (repliesReadsPerHour == null) repliesReadsPerHour = 600;
        if (signalsPerDay < 1 || asksPerDay < 1 || newMemberAsksPerDay < 1 || repliesPerDay < 1
                || newMemberRepliesPerDay < 1 || messagesPerDay < 1 || messagesPerMinute < 1 || repliesReadsPerHour < 1) {
            throw new IllegalStateException("app.responses.* values are invalid");
        }
    }
}
