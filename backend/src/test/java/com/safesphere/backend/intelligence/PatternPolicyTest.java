package com.safesphere.backend.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.safesphere.backend.config.IntelligenceProperties;
import com.safesphere.backend.intelligence.service.PatternPolicy;
import com.safesphere.backend.intelligence.service.PatternPolicy.Settings;
import com.safesphere.backend.intelligence.service.PatternPolicy.State;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Phase 8C pattern memory, with the real defaults (60-day half-life, cap 4, recurring 2.5, forget 0.25, weekly). */
class PatternPolicyTest {

    static final Settings DEFAULTS = new Settings(Duration.ofDays(60), 4, 2.5, 0.25, Duration.ofDays(7));
    static final LocalDate DAY0 = LocalDate.of(2026, 10, 1);

    private static State admitOn(State state, int day) {
        return PatternPolicy.admit(state, DAY0.plusDays(day), DEFAULTS);
    }

    @Test
    void decay_halvesEverySixtyDays() {
        State two = new State(2, DAY0, DAY0);
        assertEquals(2.0, PatternPolicy.valueOn(two, DAY0, DEFAULTS), 1e-9);
        assertEquals(1.0, PatternPolicy.valueOn(two, DAY0.plusDays(60), DEFAULTS), 1e-9);
        assertEquals(0.5, PatternPolicy.valueOn(two, DAY0.plusDays(120), DEFAULTS), 1e-9);
    }

    @Test
    void theFirstEpisode_startsAtOne_andBlocksTheNextWeek() {
        State first = admitOn(null, 0);
        assertEquals(1.0, first.value());
        assertEquals(DAY0, first.decayedOn());
        assertEquals(DAY0.plusDays(7), first.nextEpisodeOn());
        assertSame(first, admitOn(first, 6), "a burst within the week is ONE episode");
        assertTrue(admitOn(first, 7).value() > 1.9);
    }

    @Test
    void recurring_needsThreeEpisodes_neverTwo() {
        State s = admitOn(null, 0);
        s = admitOn(s, 7);
        assertFalse(PatternPolicy.recurring(s, DAY0.plusDays(7), DEFAULTS), "two weekly episodes are not a pattern");
        s = admitOn(s, 14);
        assertTrue(PatternPolicy.recurring(s, DAY0.plusDays(14), DEFAULTS));
        assertEquals(2.773, s.value(), 0.001);
    }

    @Test
    void monthlyEpisodes_becomeRecurringAtTheFourth() {
        State s = null;
        for (int i = 0; i < 3; i++) s = admitOn(s, i * 30);
        assertFalse(PatternPolicy.recurring(s, DAY0.plusDays(60), DEFAULTS));
        s = admitOn(s, 90);
        assertTrue(PatternPolicy.recurring(s, DAY0.plusDays(90), DEFAULTS));
    }

    @Test
    void theValueIsCapped() {
        State s = null;
        for (int i = 0; i < 40; i++) s = admitOn(s, i * 7);
        assertTrue(s.value() <= 4.0);
        assertEquals(4.0, s.value(), 0.6);
    }

    @Test
    void oneEpisodeIsForgottenAfterTwoHalfLives_aFullRowAfterAboutEightMonths() {
        State one = admitOn(null, 0);
        assertFalse(PatternPolicy.forgotten(one, DAY0.plusDays(119), DEFAULTS));
        assertTrue(PatternPolicy.forgotten(one, DAY0.plusDays(121), DEFAULTS));
        State full = new State(4, DAY0, DAY0);
        assertFalse(PatternPolicy.forgotten(full, DAY0.plusDays(239), DEFAULTS));
        assertTrue(PatternPolicy.forgotten(full, DAY0.plusDays(241), DEFAULTS));
    }

    @Test
    void storedValuesHaveThreeDecimals_likeTheColumn() {
        State s = admitOn(admitOn(null, 0), 7);
        assertEquals(s.value(), Math.round(s.value() * 1000) / 1000.0, 0.0);
    }

    @Test
    void invalidSettings_stopTheServer() {
        assertThrows(IllegalStateException.class, () -> new IntelligenceProperties(true, null, 5.0, null, null, null,
                null, null, null, null, null, null), "the V14 CHECK allows at most 4");
        assertThrows(IllegalStateException.class, () -> new IntelligenceProperties(true, null, null, 0.5, null, null,
                null, null, null, null, null, null), "recurring must be at least one episode");
        assertThrows(IllegalStateException.class, () -> new IntelligenceProperties(true, null, null, null, null, null,
                null, 2, 3, null, null, null), "a new account cannot get more regions than anyone else");
        assertThrows(IllegalStateException.class, () -> new IntelligenceProperties(true, null, null, null, null, null,
                null, null, null, null, null, Duration.ofMinutes(-1)), "a negative shadow interval");
        IntelligenceProperties defaults = new IntelligenceProperties(null, null, null, null, null, null, null, null,
                null, null, null, null);
        assertTrue(defaults.patternsEnabled());
        assertEquals(Duration.ofDays(60), defaults.patternHalfLife());
        assertEquals(IntelligenceProperties.Rules2Mode.SHADOW, defaults.rules2Mode(), "9D: rules-2 is never active by default");
    }
}
