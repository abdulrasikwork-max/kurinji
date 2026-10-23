package com.safesphere.backend.social.policy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;

/**
 * Phase 13B (design §4.3): time privacy. Others never get a timestamp of a post or a comment, only a band: THIS_WEEK
 * (under 7 days), THIS_MONTH (under 30 days), EARLIER. Bands are counted from when others could first see it
 * (visible_from), so the Q1 delay is not undone by a finer band. Pure.
 */
public final class TimeBands {

    public enum Band { THIS_WEEK, THIS_MONTH, EARLIER }

    /** The earliest month an experience may be dated (anything older is almost certainly a typing error). */
    public static final YearMonth EARLIEST_MONTH = YearMonth.of(1950, 1);

    /**
     * How far ahead of UTC the person's own calendar may be (UTC+14 is the furthest time zone), so that "this month"
     * written just after midnight on the 1st in India is never refused as "in the future".
     */
    private static final Duration LATEST_ZONE_OFFSET = Duration.ofHours(14);

    private TimeBands() {
    }

    public static Band band(LocalDateTime since, LocalDateTime now) {
        Duration age = Duration.between(since, now);
        if (age.compareTo(Duration.ofDays(7)) < 0) return Band.THIS_WEEK;
        if (age.compareTo(Duration.ofDays(30)) < 0) return Band.THIS_MONTH;
        return Band.EARLIER;
    }

    /** The latest month an experience may be dated at [nowUtc]: never in the future, in any time zone. */
    public static YearMonth latestMonth(LocalDateTime nowUtc) {
        return YearMonth.from(nowUtc.plus(LATEST_ZONE_OFFSET));
    }

    public static boolean isAllowedMonth(YearMonth month, LocalDateTime nowUtc) {
        return month != null && !month.isBefore(EARLIEST_MONTH) && !month.isAfter(latestMonth(nowUtc));
    }
}
