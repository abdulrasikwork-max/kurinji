package com.safesphere.backend.explore;

import com.safesphere.backend.config.ExploreUnderstandingProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Phase 17G-3: OUR OWN limits on the optional understanding, independent of what the provider enforces: a per-person and a
 * global daily cap (UTC days) and a circuit breaker. After any provider failure no call is made for the breaker pause: a
 * spend-limit or empty-credit refusal repeats until someone fixes the account and carries no retry-after, so retrying would
 * only waste time. In memory by design: a restart resets the counters (the real ceiling is the spend limit set at the
 * provider); nothing here is stored or identifies a person beyond the id used as a counter key for one day.
 */
@Component
public class UnderstandingGate {

    public enum Verdict { ALLOWED, USER_CAP, GLOBAL_CAP, PAUSED }

    private final ExploreUnderstandingProperties properties;
    private final Clock clock;
    private final Map<Long, Integer> perUser = new HashMap<>();
    private LocalDate day;
    private int total;
    private Instant pausedUntil = Instant.MIN;

    public UnderstandingGate(ExploreUnderstandingProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Asks to make one provider call for [userId]; ALLOWED counts it. Refusals count nothing. */
    public synchronized Verdict tryAcquire(long userId) {
        Instant now = clock.instant();
        rollDay(now);
        if (now.isBefore(pausedUntil)) return Verdict.PAUSED;
        if (total >= properties.globalDailyCap()) return Verdict.GLOBAL_CAP;
        int used = perUser.getOrDefault(userId, 0);
        if (used >= properties.userDailyCap()) return Verdict.USER_CAP;
        perUser.put(userId, used + 1);
        total++;
        return Verdict.ALLOWED;
    }

    /** A provider failure: open the breaker. */
    public synchronized void failed() {
        pausedUntil = clock.instant().plus(properties.breakerPause());
    }

    public synchronized boolean paused() {
        return clock.instant().isBefore(pausedUntil);
    }

    private void rollDay(Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (!today.equals(day)) {
            day = today;
            total = 0;
            perUser.clear();
        }
    }
}
