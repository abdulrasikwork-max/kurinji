package com.safesphere.backend.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.safesphere.backend.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AttemptLimiterTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void blocksAfterTheLimit_untilTheWindowEnds() {
        AttemptLimiter limiter = new AttemptLimiter(3, Duration.ofMinutes(15), 100, clock);
        for (int i = 0; i < 3; i++) assertTrue(limiter.tryAcquire("a"));
        assertFalse(limiter.tryAcquire("a"));
        assertTrue(limiter.isBlocked("a"));
        assertFalse(limiter.isBlocked("b"), "keys are independent");
        clock.advance(Duration.ofMinutes(15));
        assertFalse(limiter.isBlocked("a"));
        assertTrue(limiter.tryAcquire("a"));
    }

    @Test
    void reset_forgetsAKey() {
        AttemptLimiter limiter = new AttemptLimiter(1, Duration.ofMinutes(15), 100, clock);
        limiter.record("a");
        assertTrue(limiter.isBlocked("a"));
        limiter.reset("a");
        assertFalse(limiter.isBlocked("a"));
    }

    @Test
    void memoryIsBounded_finishedWindowsFirst_thenTheLeastRecentlyUsed() {
        AttemptLimiter limiter = new AttemptLimiter(5, Duration.ofMinutes(10), 3, clock);
        limiter.record("old");
        clock.advance(Duration.ofMinutes(11));
        limiter.record("a");
        limiter.record("b");
        limiter.record("c");
        assertEquals(3, limiter.size(), "the finished window was dropped to make room");
        limiter.record("d");
        assertEquals(3, limiter.size());
        limiter.evictExpired();
        clock.advance(Duration.ofMinutes(11));
        limiter.evictExpired();
        assertEquals(0, limiter.size());
    }
}
