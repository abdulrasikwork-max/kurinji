package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.TooManyAttemptsException;
import com.safesphere.backend.config.MomentProperties;
import com.safesphere.backend.config.SocialProperties;
import com.safesphere.backend.security.AttemptLimiter;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Phase N4: the per-person limits of moments (PHASE_N4 §3): up to 3 moments a day as a new member, up to 10 once
 * established. In memory, like SocialLimits: nothing about how much someone shares is stored; per instance, reset on a
 * restart (documented limitation). A deleted moment still counts (deleting and sharing again is not a way around).
 */
@Component
public class MomentLimits {

    static final String TOO_MANY_MOMENTS = "You have shared a lot today. Please try again tomorrow.";
    static final String TOO_MANY_AS_NEW_MEMBER =
            "New members can share 3 moments a day in their first week. Please try again tomorrow.";
    static final String TOO_MANY_READS = "You have opened Today many times this hour. Please try again later.";

    private final AttemptLimiter moments;
    private final AttemptLimiter newMemberMoments;
    private final AttemptLimiter todayReads;

    public MomentLimits(MomentProperties properties, SocialProperties social, Clock clock) {
        int keys = social.maxTrackedKeys();
        this.moments = new AttemptLimiter(properties.perDay(), Duration.ofDays(1), keys, clock);
        this.newMemberMoments = new AttemptLimiter(properties.newMemberPerDay(), Duration.ofDays(1), keys, clock);
        this.todayReads = new AttemptLimiter(properties.todayReadsPerHour(), Duration.ofHours(1), keys, clock);
    }

    /**
     * One more moment by [userId]. A new member's moment counts against both limits, so the day someone becomes
     * established does not hand them a fresh allowance on top of what they already shared.
     */
    public void share(long userId, boolean newMember) {
        String key = key(userId);
        if (moments.isBlocked(key)) throw new TooManyAttemptsException(TOO_MANY_MOMENTS);
        if (newMember && !newMemberMoments.tryAcquire(key)) throw new TooManyAttemptsException(TOO_MANY_AS_NEW_MEMBER);
        moments.record(key);
    }

    public void readToday(long userId) {
        if (!todayReads.tryAcquire(key(userId))) throw new TooManyAttemptsException(TOO_MANY_READS);
    }

    private static String key(long userId) {
        return "user:" + userId;
    }
}
