package com.safesphere.backend.moderation.service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * When something in moderation happened, roughly, for the person it concerns (their own reports, decisions about
 * them): THIS_WEEK (under 7 days), THIS_MONTH (under 30 days), EARLIER. Never a timestamp. Pure.
 */
final class Periods {

    private Periods() {
    }

    static String of(LocalDateTime since, LocalDateTime now) {
        Duration age = Duration.between(since, now);
        if (age.compareTo(Duration.ofDays(7)) < 0) return "THIS_WEEK";
        if (age.compareTo(Duration.ofDays(30)) < 0) return "THIS_MONTH";
        return "EARLIER";
    }
}
