package com.safesphere.backend.social.policy;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Phase N4 (docs/PHASE_N4.md §3): what others learn about WHEN a moment was shared. Never a timestamp: a rough time in
 * India time (the community's time), counted from when others could first see the moment (visible_at), so a moment that
 * waited for a moderator does not reveal when it was written. Only the author gets exact times. Pure.
 *
 *   within today / yesterday (India calendar days):  TODAY or YESTERDAY + MORNING / AFTERNOON / EVENING / NIGHT
 *   older (a kept moment on a profile):              THIS_WEEK (under 7 days), THIS_MONTH (under 30 days), EARLIER
 *
 * Parts of the day in India time: MORNING 05:00-11:59, AFTERNOON 12:00-16:59, EVENING 17:00-20:59, NIGHT 21:00-04:59.
 * All stored times are UTC (docs/TIMESTAMPS.md); the India zone is used here only to name a day and a part of it.
 */
public final class MomentTimes {

    /** The community's time zone (MIET, the only community at launch). */
    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    public enum Day { TODAY, YESTERDAY, THIS_WEEK, THIS_MONTH, EARLIER }

    public enum Part { MORNING, AFTERNOON, EVENING, NIGHT }

    /** [part] is null for anything older than yesterday. */
    public record Rough(Day day, Part part) {
    }

    private static final LocalTime MORNING_FROM = LocalTime.of(5, 0);
    private static final LocalTime AFTERNOON_FROM = LocalTime.of(12, 0);
    private static final LocalTime EVENING_FROM = LocalTime.of(17, 0);
    private static final LocalTime NIGHT_FROM = LocalTime.of(21, 0);

    private MomentTimes() {
    }

    /** The rough time of something first visible at [sinceUtc], as seen at [nowUtc]. */
    public static Rough rough(LocalDateTime sinceUtc, LocalDateTime nowUtc) {
        LocalDateTime since = india(sinceUtc);
        LocalDate today = indiaDay(nowUtc);
        if (!since.toLocalDate().isBefore(today)) return new Rough(Day.TODAY, part(since.toLocalTime()));
        if (since.toLocalDate().equals(today.minusDays(1))) return new Rough(Day.YESTERDAY, part(since.toLocalTime()));
        Duration age = Duration.between(sinceUtc, nowUtc);
        if (age.compareTo(Duration.ofDays(7)) < 0) return new Rough(Day.THIS_WEEK, null);
        if (age.compareTo(Duration.ofDays(30)) < 0) return new Rough(Day.THIS_MONTH, null);
        return new Rough(Day.EARLIER, null);
    }

    public static Part part(LocalTime indiaTime) {
        if (indiaTime.isBefore(MORNING_FROM)) return Part.NIGHT;
        if (indiaTime.isBefore(AFTERNOON_FROM)) return Part.MORNING;
        if (indiaTime.isBefore(EVENING_FROM)) return Part.AFTERNOON;
        if (indiaTime.isBefore(NIGHT_FROM)) return Part.EVENING;
        return Part.NIGHT;
    }

    /** The India calendar day at [nowUtc] (the day a daily question belongs to). */
    public static LocalDate indiaDay(LocalDateTime nowUtc) {
        return india(nowUtc).toLocalDate();
    }

    private static LocalDateTime india(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(INDIA).toLocalDateTime();
    }
}
