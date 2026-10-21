package com.safesphere.backend.intelligence.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Pattern memory (Phase 8C, docs/PHASE8_DESIGN.md §7.4), pure and deterministic (PatternPolicyTest). An area cell
 * remembers ONE number per group (incidents, conditions): a decaying count of finished, independently corroborated,
 * unmoderated episodes. Nothing else: no episode list, no times of reports, no people, no link to any signal.
 *
 *   decay      value(today) = value * 2^(-days since decayedOn / half-life)          (lazy: applied when read)
 *   admit      at most one episode per [minGap] (a burst of signals is ONE episode):
 *              value = min(cap, value(today) + 1), next episode no earlier than today + minGap
 *   recurring  value(today) >= [recurring]   (with the defaults 2.5: three episodes within weeks to months)
 *   forgotten  value(today) <  [forget]      (the row is deleted; one episode is forgotten after ~2 half-lives,
 *              a full row after ~log2(cap / forget) half-lives, about 240 days with the defaults)
 *
 * A pattern is CONTEXT: AreaIntelligencePolicy never lets it raise a level on its own.
 */
public final class PatternPolicy {

    /** One row of area_patterns. Dates are UTC days; they are never returned to any client. */
    public record State(double value, LocalDate decayedOn, LocalDate nextEpisodeOn) {
    }

    /** The tuning (from app.intelligence.*). */
    public record Settings(Duration halfLife, double cap, double recurring, double forget, Duration minGap) {
    }

    private PatternPolicy() {
    }

    public static double valueOn(State state, LocalDate today, Settings settings) {
        long days = Math.max(0, ChronoUnit.DAYS.between(state.decayedOn(), today));
        double halfLifeDays = Math.max(1, settings.halfLife().toDays());
        return state.value() * Math.pow(2, -days / halfLifeDays);
    }

    /**
     * A finished episode in this area and group. [current] null = the area has no row. Returns the new state, or
     * [current] unchanged when the weekly cap has not passed yet (the burst counts once).
     */
    public static State admit(State current, LocalDate today, Settings settings) {
        if (current != null && today.isBefore(current.nextEpisodeOn())) return current;
        double before = current == null ? 0 : valueOn(current, today, settings);
        double value = round3(Math.min(settings.cap(), before + 1));
        return new State(value, today, today.plusDays(Math.max(0, settings.minGap().toDays())));
    }

    public static boolean recurring(State state, LocalDate today, Settings settings) {
        return valueOn(state, today, settings) >= settings.recurring();
    }

    public static boolean forgotten(State state, LocalDate today, Settings settings) {
        return valueOn(state, today, settings) < settings.forget();
    }

    /** DECIMAL(5,3) in MySQL: store exactly what was computed. */
    static double round3(double value) {
        return Math.round(value * 1000d) / 1000d;
    }
}
