package com.safesphere.backend.community.service;

import com.safesphere.backend.common.exception.TooManyAttemptsException;
import com.safesphere.backend.config.CommunityProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Anti-scraping / anti-reconnaissance limit for area look-ups (Phase 6). Per account and fixed one-hour window:
 * at most N requests and at most M DIFFERENT areas. Someone standing in one place can refresh as often as is
 * sensible; someone "walking" the map remotely to collect every signal in a city is stopped after a few areas.
 *
 * Deliberately IN MEMORY: storing the looked-up areas in the database would itself be a location trail of the
 * person looking. The window holds at most one hour of area cells per account, is never logged and is dropped when
 * it ends (and on restart, which only resets the limit). Single-instance; a cluster would need a shared store.
 */
@Component
public class AreaQueryLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);

    private final CommunityProperties properties;
    private final Clock clock;
    private final Map<Long, Window> windows = new HashMap<>();

    public AreaQueryLimiter(CommunityProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    private static final class Window {
        final Instant start;
        int requests;
        final Set<String> areas = new HashSet<>();

        Window(Instant start) {
            this.start = start;
        }
    }

    /** Throws 429 when the caller is over a limit; otherwise records the look-up. */
    public synchronized void check(long userId, String area) {
        Instant now = clock.instant();
        Window window = windows.get(userId);
        if (window == null || !now.isBefore(window.start.plus(WINDOW))) {
            window = new Window(now);
            windows.put(userId, window);
        }
        if (window.requests >= properties.areaQueriesPerHour()) {
            throw new TooManyAttemptsException("Too many look-ups. Please try again later.");
        }
        if (!window.areas.contains(area) && window.areas.size() >= properties.distinctAreasPerHour()) {
            throw new TooManyAttemptsException(
                    "You have looked at many different areas. Community signals are for where you are; try again later.");
        }
        window.requests++;
        window.areas.add(area);
    }

    /** Drops finished windows (called by the periodic clean-up). */
    public synchronized void evictExpired() {
        Instant now = clock.instant();
        windows.values().removeIf(window -> !now.isBefore(window.start.plus(WINDOW)));
    }
}
