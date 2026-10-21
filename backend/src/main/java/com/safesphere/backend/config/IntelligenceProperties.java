package com.safesphere.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The app.intelligence.* settings (Phase 8, docs/PHASE8_DESIGN.md §14.4). Every value has a safe default, and invalid
 * combinations stop the server at start-up (like a short JWT secret) instead of producing odd intelligence.
 *
 * @param patternsEnabled  pattern memory (8C) on or off; off simply removes "recurring" from every picture
 * @param patternHalfLife  a pattern value halves after this long without new episodes
 * @param patternCap       the most a pattern value can reach (the V14 CHECK allows at most 4)
 * @param patternRecurring value from which an area's concerns count as RECURRING (needs >= 3 episodes)
 * @param patternForget    below this value the row is deleted (the area is forgotten)
 * @param patternMinGap    at most one episode per area and group in this period
 * @param regionRequestsPerDay      region packs one person may download in 24 h (8D)
 * @param regionDistinctPerDay      DIFFERENT regions one person may download in 24 h (8D)
 * @param regionDistinctNewAccount  the same for an account younger than the community probation (8D)
 * @param regionMaxSignals          above this many confirmed signals a region pack says "incomplete" (8D)
 * @param rules2Mode                the rules-2 candidate (9D-2): OFF = not computed; SHADOW (default) = compared with
 *                                  the active rules-1.1 over every area with visible signals and only COUNTED in the
 *                                  log; ACTIVE = it produces the pictures and packs (only by the user's decision)
 * @param rules2ShadowInterval      the shadow comparison reads every visible signal, so at most once per this period
 */
@ConfigurationProperties(prefix = "app.intelligence")
public record IntelligenceProperties(Boolean patternsEnabled, Duration patternHalfLife, Double patternCap,
                                     Double patternRecurring, Double patternForget, Duration patternMinGap,
                                     Integer regionRequestsPerDay, Integer regionDistinctPerDay,
                                     Integer regionDistinctNewAccount, Integer regionMaxSignals,
                                     Rules2Mode rules2Mode, Duration rules2ShadowInterval) {

    /** How the rules-2 candidate runs (docs/PHASE9.md §19). */
    public enum Rules2Mode { OFF, SHADOW, ACTIVE }

    public IntelligenceProperties {
        if (rules2Mode == null) rules2Mode = Rules2Mode.SHADOW;
        if (rules2ShadowInterval == null) rules2ShadowInterval = Duration.ofHours(1);
        if (rules2ShadowInterval.isNegative()) throw new IllegalStateException("app.intelligence.* values are invalid");
        if (patternsEnabled == null) patternsEnabled = true;
        if (patternHalfLife == null) patternHalfLife = Duration.ofDays(60);
        if (patternCap == null) patternCap = 4.0;
        if (patternRecurring == null) patternRecurring = 2.5;
        if (patternForget == null) patternForget = 0.25;
        if (patternMinGap == null) patternMinGap = Duration.ofDays(7);
        if (regionRequestsPerDay == null) regionRequestsPerDay = 20;
        if (regionDistinctPerDay == null) regionDistinctPerDay = 6;
        if (regionDistinctNewAccount == null) regionDistinctNewAccount = 2;
        if (regionMaxSignals == null) regionMaxSignals = 500;
        if (patternHalfLife.compareTo(Duration.ofDays(1)) < 0 || patternMinGap.isNegative()
                || !(patternForget > 0 && patternForget < 1 && 1 <= patternRecurring
                    && patternRecurring <= patternCap && patternCap <= 4)
                || regionRequestsPerDay < 1 || regionDistinctPerDay < 1 || regionDistinctNewAccount < 1
                || regionDistinctNewAccount > regionDistinctPerDay || regionMaxSignals < 1) {
            throw new IllegalStateException("app.intelligence.* values are invalid");
        }
    }
}
