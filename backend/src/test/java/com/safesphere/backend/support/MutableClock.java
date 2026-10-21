package com.safesphere.backend.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Test clock = real time + an offset the test can move forward (for lifetimes, delays and windows). It keeps
 * ticking, so it stays consistent with values the code takes from the real clock (for example users.created_at).
 * Always UTC.
 */
public final class MutableClock extends Clock {

    private volatile Duration offset = Duration.ZERO;

    public void reset() {
        this.offset = Duration.ZERO;
    }

    public void advance(Duration duration) {
        this.offset = offset.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset);
    }
}
