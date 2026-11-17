package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.TooManyAttemptsException;
import com.safesphere.backend.config.ResponseProperties;
import com.safesphere.backend.config.SocialProperties;
import com.safesphere.backend.security.AttemptLimiter;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Phase N5 (PHASE_N5 §3): how much one person may respond, in memory (nothing about how much someone talks is stored;
 * N-65). A refused request does not count. A new member's ask or new conversation counts against both limits.
 */
@Component
public class ResponseLimits {

    static final String TOO_MANY_SIGNALS = "You have tapped a lot today. Please try again tomorrow.";
    static final String TOO_MANY_ASKS = "You have asked to join many plans today. Please try again tomorrow.";
    static final String TOO_MANY_REPLIES = "You have started many private replies today. Please try again tomorrow.";
    static final String TOO_MANY_MESSAGES = "You have sent many messages today. Please try again tomorrow.";
    static final String TOO_FAST = "That was quick. Please wait a moment before sending again.";
    static final String TOO_MANY_READS = "You have opened Replies many times this hour. Please try again later.";

    private final AttemptLimiter signals;
    private final AttemptLimiter asks;
    private final AttemptLimiter newMemberAsks;
    private final AttemptLimiter replies;
    private final AttemptLimiter newMemberReplies;
    private final AttemptLimiter messagesDay;
    private final AttemptLimiter messagesMinute;
    private final AttemptLimiter reads;

    public ResponseLimits(ResponseProperties properties, SocialProperties social, Clock clock) {
        int keys = social.maxTrackedKeys();
        Duration day = Duration.ofDays(1);
        this.signals = new AttemptLimiter(properties.signalsPerDay(), day, keys, clock);
        this.asks = new AttemptLimiter(properties.asksPerDay(), day, keys, clock);
        this.newMemberAsks = new AttemptLimiter(properties.newMemberAsksPerDay(), day, keys, clock);
        this.replies = new AttemptLimiter(properties.repliesPerDay(), day, keys, clock);
        this.newMemberReplies = new AttemptLimiter(properties.newMemberRepliesPerDay(), day, keys, clock);
        this.messagesDay = new AttemptLimiter(properties.messagesPerDay(), day, keys, clock);
        this.messagesMinute = new AttemptLimiter(properties.messagesPerMinute(), Duration.ofMinutes(1), keys, clock);
        this.reads = new AttemptLimiter(properties.repliesReadsPerHour(), Duration.ofHours(1), keys, clock);
    }

    public void signal(long userId) {
        if (!signals.tryAcquire(key(userId))) throw new TooManyAttemptsException(TOO_MANY_SIGNALS);
    }

    public void ask(long userId, boolean newMember) {
        both(key(userId), asks, newMember ? newMemberAsks : null, TOO_MANY_ASKS);
    }

    public void startReply(long userId, boolean newMember) {
        both(key(userId), replies, newMember ? newMemberReplies : null, TOO_MANY_REPLIES);
    }

    public void message(long userId) {
        String key = key(userId);
        if (messagesDay.isBlocked(key)) throw new TooManyAttemptsException(TOO_MANY_MESSAGES);
        if (!messagesMinute.tryAcquire(key)) throw new TooManyAttemptsException(TOO_FAST);
        messagesDay.record(key);
    }

    public void read(long userId) {
        if (!reads.tryAcquire(key(userId))) throw new TooManyAttemptsException(TOO_MANY_READS);
    }

    /** Checks both limits first, then records in both, so a refusal by either never uses up the other. */
    private static void both(String key, AttemptLimiter all, AttemptLimiter newcomer, String message) {
        if (all.isBlocked(key) || (newcomer != null && newcomer.isBlocked(key))) {
            throw new TooManyAttemptsException(message);
        }
        all.record(key);
        if (newcomer != null) newcomer.record(key);
    }

    private static String key(long userId) {
        return "user:" + userId;
    }
}
