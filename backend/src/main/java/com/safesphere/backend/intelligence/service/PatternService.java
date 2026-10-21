package com.safesphere.backend.intelligence.service;

import com.safesphere.backend.community.entity.SignalCategory.SignalGroup;
import com.safesphere.backend.config.IntelligenceProperties;
import com.safesphere.backend.intelligence.entity.AreaPattern;
import com.safesphere.backend.intelligence.repository.AreaPatternRepository;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.CellPatterns;
import com.safesphere.backend.intelligence.service.PatternPolicy.Settings;
import com.safesphere.backend.intelligence.service.PatternPolicy.State;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pattern memory (Phase 8C, docs/PHASE8_DESIGN.md §7.4). CommunityService tells it when a signal ENDED NATURALLY after
 * being confirmed (an episode); a moderator removal makes it forget the area; the clean-up job forgets faded rows.
 * Reads give only "recurring or not" per area cell. Nothing here knows which signal or person formed a pattern.
 */
@Service
public class PatternService {

    private final AreaPatternRepository patterns;
    private final IntelligenceProperties properties;
    private final Clock clock;
    private final Settings settings;

    public PatternService(AreaPatternRepository patterns, IntelligenceProperties properties, Clock clock) {
        this.patterns = patterns;
        this.properties = properties;
        this.clock = clock;
        this.settings = new Settings(properties.patternHalfLife(), properties.patternCap(),
                properties.patternRecurring(), properties.patternForget(), properties.patternMinGap());
    }

    public boolean enabled() {
        return properties.patternsEnabled();
    }

    /** One finished, confirmed, unmoderated episode of [group] in [areaCell]. Havens have no memory. */
    @Transactional
    public void episode(String areaCell, SignalGroup group) {
        if (!enabled() || group == SignalGroup.HAVEN) return;
        LocalDate today = today();
        AreaPattern row = patterns.findById(new AreaPattern.Key(areaCell, group.name()))
                .orElseGet(() -> new AreaPattern(areaCell, group.name()));
        State current = row.getDecayedOn() == null ? null : state(row);
        State next = PatternPolicy.admit(current, today, settings);
        if (next == current) return; // within the weekly cap: the burst already counted
        row.update(next.value(), next.decayedOn(), next.nextEpisodeOn());
        patterns.save(row);
    }

    /** A moderator removed a signal here: the area's memory for that group goes too (no lingering stigma). */
    @Transactional
    public void forget(String areaCell, SignalGroup group) {
        if (group == SignalGroup.HAVEN) return;
        patterns.forget(areaCell, group.name());
    }

    /** Which of [areaCells] have RECURRING incidents / conditions today. Empty when switched off. */
    @Transactional(readOnly = true)
    public Map<String, CellPatterns> recurringIn(Collection<String> areaCells) {
        if (!enabled() || areaCells.isEmpty()) return Map.of();
        LocalDate today = today();
        Map<String, boolean[]> flags = new HashMap<>();
        for (AreaPattern row : patterns.findByKeyAreaCellIn(areaCells)) {
            if (!PatternPolicy.recurring(state(row), today, settings)) continue;
            boolean[] f = flags.computeIfAbsent(row.getKey().getAreaCell(), k -> new boolean[2]);
            if (SignalGroup.INCIDENT.name().equals(row.getKey().getSignalGroup())) f[0] = true; else f[1] = true;
        }
        Map<String, CellPatterns> result = new HashMap<>();
        flags.forEach((cell, f) -> result.put(cell, new CellPatterns(f[0], f[1])));
        return result;
    }

    /** Deletes rows that have faded below the forget threshold (also all rows when switched off). Returns how many. */
    @Transactional
    public int forgetFaded() {
        LocalDate today = today();
        List<AreaPattern> faded = new ArrayList<>();
        for (AreaPattern row : patterns.findAll()) {
            if (!enabled() || PatternPolicy.forgotten(state(row), today, settings)) faded.add(row);
        }
        patterns.deleteAll(faded);
        return faded.size();
    }

    private static State state(AreaPattern row) {
        return new State(row.getValue(), row.getDecayedOn(), row.getNextEpisodeOn());
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
