package com.safesphere.backend.moderation.service;

import com.safesphere.backend.social.entity.PostEnums.ModerationReason;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase 13D (pure; StrikePolicyTest, M10): repeat offenders (EXPERIENCE_PLATFORM_DESIGN.md §8.4).
 *
 *   weight     the reason's severity (ReportPolicy.severity): 3 threat / sexual harassment / hate / doxxing,
 *              2 harassment / nudity or violence / impersonation, 1 spam / other;
 *   points     each strike's weight halves every 90 days (the Phase 7 decay), computed when read, never stored;
 *   suggestion the next step for a moderator, from the points the person already has plus this case's reason:
 *              below 3 warn, below 5 pause perspectives (3 days), below 8 pause posting (7 days), else suspend
 *              (30 days). A suggestion only: a human decides every step, and every step can be appealed.
 */
public final class StrikePolicy {

    public static final Duration HALF_LIFE = Duration.ofDays(90);

    /** The graduated steps, mildest first. */
    public enum Step {
        WARN(Duration.ZERO), PAUSE_COMMENTS(Duration.ofDays(3)), PAUSE_POSTING(Duration.ofDays(7)),
        SUSPEND(Duration.ofDays(30));

        private final Duration length;

        Step(Duration length) {
            this.length = length;
        }

        /** How long the pause lasts (zero for a warning). */
        public Duration length() {
            return length;
        }
    }

    /** One strike as the counting needs it. */
    public record Strike(int weight, LocalDateTime createdAt) {
    }

    private StrikePolicy() {
    }

    public static int weight(ModerationReason reason) {
        return ReportPolicy.severity(reason);
    }

    /** The decayed points of [strikes] at [now]. */
    public static double points(List<Strike> strikes, LocalDateTime now) {
        double total = 0;
        for (Strike strike : strikes) {
            double halfLives = Math.max(0, Duration.between(strike.createdAt(), now).toMinutes())
                    / (double) HALF_LIFE.toMinutes();
            total += strike.weight() * Math.pow(0.5, halfLives);
        }
        return total;
    }

    /** The suggested step for a violation with [reason] by someone who already has [points]. */
    public static Step suggest(double points, ModerationReason reason) {
        double after = points + (reason == null ? 1 : weight(reason));
        if (after < 3) return Step.WARN;
        if (after < 5) return Step.PAUSE_COMMENTS;
        if (after < 8) return Step.PAUSE_POSTING;
        return Step.SUSPEND;
    }
}
