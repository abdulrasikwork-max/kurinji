package com.safesphere.backend.intelligence.service;

import com.safesphere.backend.common.exception.TooManyAttemptsException;
import com.safesphere.backend.config.IntelligenceProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Budget for region packs (Phase 8D, docs/PHASE8_DESIGN.md §19.3). Per account and fixed 24-hour window: at most N
 * downloads and at most M DIFFERENT regions (fewer for an account still in its community probation). Checking the same
 * walk again is free of the distinct budget; collecting a city region by region is stopped after a few.
 *
 * Deliberately IN MEMORY, like AreaQueryLimiter: a database log of which regions someone downloaded would be a trail
 * of where they plan to go. At most a handful of 5-character regions per account, never logged, dropped when the
 * window ends (and on restart, which only resets the budget). Single-instance; a cluster would need a shared store.
 */
@Component
public class RegionQueryLimiter {

    private static final Duration WINDOW = Duration.ofHours(24);

    private final IntelligenceProperties properties;
    private final Clock clock;
    private final Map<Long, Window> windows = new HashMap<>();

    public RegionQueryLimiter(IntelligenceProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    private static final class Window {
        final Instant start;
        int requests;
        final Set<String> regions = new HashSet<>();

        Window(Instant start) {
            this.start = start;
        }
    }

    /** Throws 429 when the caller is over a budget; otherwise records the download. */
    public synchronized void check(long userId, String region, boolean newAccount) {
        Instant now = clock.instant();
        Window window = windows.get(userId);
        if (window == null || !now.isBefore(window.start.plus(WINDOW))) {
            window = new Window(now);
            windows.put(userId, window);
        }
        if (window.requests >= properties.regionRequestsPerDay()) {
            throw new TooManyAttemptsException("You have checked the way many times today. Please try again tomorrow.");
        }
        int distinct = newAccount ? properties.regionDistinctNewAccount() : properties.regionDistinctPerDay();
        if (!window.regions.contains(region) && window.regions.size() >= distinct) {
            throw new TooManyAttemptsException(
                    "You have checked many different areas today. Please try again tomorrow.");
        }
        window.requests++;
        window.regions.add(region);
    }

    /** Drops finished windows (called by the periodic clean-up). */
    public synchronized void evictExpired() {
        Instant now = clock.instant();
        windows.values().removeIf(window -> !now.isBefore(window.start.plus(WINDOW)));
    }
}
