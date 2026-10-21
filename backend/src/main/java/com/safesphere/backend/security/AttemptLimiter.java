package com.safesphere.backend.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A small in-memory fixed-window counter (Phase 7): at most [limit] events per key per [window]. Used for sign-in
 * failures, registrations, community write bursts and moderator requests.
 *
 * Deliberately in memory (same reasoning as Phase 6's AreaQueryLimiter): keeping IP addresses or e-mail addresses of
 * failed sign-ins in the database would be a log of personal data nobody needs once the window is over. Keys are
 * dropped when their window ends. Memory is bounded: at most [maxKeys] keys; when full, finished windows are dropped
 * first, then the least recently used key. Per instance; resets on restart (documented limitation).
 */
public final class AttemptLimiter {

    private final int limit;
    private final Duration window;
    private final int maxKeys;
    private final Clock clock;
    private final LinkedHashMap<String, Window> windows = new LinkedHashMap<>(256, 0.75f, true);

    private static final class Window {
        final Instant start;
        int count;

        Window(Instant start) {
            this.start = start;
        }
    }

    public AttemptLimiter(int limit, Duration window, int maxKeys, Clock clock) {
        if (limit < 1 || window.isNegative() || window.isZero() || maxKeys < 1) {
            throw new IllegalArgumentException("Invalid limiter settings");
        }
        this.limit = limit;
        this.window = window;
        this.maxKeys = maxKeys;
        this.clock = clock;
    }

    /** True when [key] has already used up its window (the next event would be over the limit). Records nothing. */
    public synchronized boolean isBlocked(String key) {
        Window current = live(key, clock.instant());
        return current != null && current.count >= limit;
    }

    /** Records one event for [key]. */
    public synchronized void record(String key) {
        Instant now = clock.instant();
        Window current = live(key, now);
        if (current == null) {
            makeRoom(now);
            current = new Window(now);
            windows.put(key, current);
        }
        current.count++;
    }

    /** Records one event unless the key is already at its limit. Returns false (and records nothing) when blocked. */
    public synchronized boolean tryAcquire(String key) {
        if (isBlocked(key)) return false;
        record(key);
        return true;
    }

    /** Forgets [key] (for example after a successful sign-in). */
    public synchronized void reset(String key) {
        windows.remove(key);
    }

    public synchronized void evictExpired() {
        Instant now = clock.instant();
        windows.values().removeIf(w -> !now.isBefore(w.start.plus(window)));
    }

    synchronized int size() {
        return windows.size();
    }

    private Window live(String key, Instant now) {
        Window current = windows.get(key);
        if (current != null && !now.isBefore(current.start.plus(window))) {
            windows.remove(key);
            return null;
        }
        return current;
    }

    private void makeRoom(Instant now) {
        if (windows.size() < maxKeys) return;
        windows.values().removeIf(w -> !now.isBefore(w.start.plus(window)));
        Iterator<Map.Entry<String, Window>> eldest = windows.entrySet().iterator();
        while (windows.size() >= maxKeys && eldest.hasNext()) {
            eldest.next();
            eldest.remove();
        }
    }
}
