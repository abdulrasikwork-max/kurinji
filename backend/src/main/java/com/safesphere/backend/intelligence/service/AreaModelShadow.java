package com.safesphere.backend.intelligence.service;

import com.safesphere.backend.community.service.SignalVisibility;
import com.safesphere.backend.community.service.SignalVisibility.VisibleSet;
import com.safesphere.backend.config.IntelligenceProperties;
import com.safesphere.backend.config.IntelligenceProperties.Rules2Mode;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.CellPatterns;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.CellPicture;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Evidence;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Model;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 9D-2 (docs/PHASE9.md §19): the rules-2 candidate in SHADOW. From the clean-up job, at most once per
 * app.intelligence.rules-2-shadow-interval, it computes every area cell that has a visible signal under BOTH the active
 * rules (rules-1.1) and rules-2, and logs only how many areas would read differently. Nothing it computes is shown,
 * stored or returned; the log line holds counts only (never a cell, a category or an account). Its purpose is the
 * evidence a promotion would need: how often rules-2 changes what people are told, measured on real data instead of
 * guessed. In memory only: after a restart it simply runs once more.
 */
@Component
public class AreaModelShadow {

    private static final Logger log = LoggerFactory.getLogger(AreaModelShadow.class);

    /** One comparison (counts only): areas compared, and how many rules-2 would show less / more cautiously. */
    public record Comparison(int areas, int lessCautious, int moreCautious) {
        static final Comparison SKIPPED = new Comparison(0, 0, 0);
    }

    private final SignalVisibility visibility;
    private final PatternService patterns;
    private final IntelligenceProperties properties;
    private Instant lastRun;

    public AreaModelShadow(SignalVisibility visibility, PatternService patterns, IntelligenceProperties properties) {
        this.visibility = visibility;
        this.patterns = patterns;
        this.properties = properties;
    }

    /** Called by the clean-up job. Runs only in SHADOW mode, and at most once per interval. */
    @Transactional(readOnly = true)
    public synchronized Comparison run(LocalDateTime now) {
        if (properties.rules2Mode() != Rules2Mode.SHADOW) return Comparison.SKIPPED;
        Instant at = now.toInstant(ZoneOffset.UTC);
        // A last run "in the future" means the clock was set back (NTP correction): run rather than pause for hours.
        if (lastRun != null && !at.isBefore(lastRun) && at.isBefore(lastRun.plus(properties.rules2ShadowInterval()))) {
            return Comparison.SKIPPED;
        }
        lastRun = at;
        Comparison comparison = compare(now);
        if (comparison.areas() > 0) {
            log.info("Area rules shadow {} vs {}: {} area(s) compared, {} would read less cautiously, {} more cautiously",
                    Model.RULES_2.version(), Model.RULES_1_1.version(), comparison.areas(), comparison.lessCautious(),
                    comparison.moreCautious());
        }
        return comparison;
    }

    /** The comparison itself, over every area cell with a visible signal (also read by the 9A simulation). */
    @Transactional(readOnly = true)
    public Comparison compare(LocalDateTime now) {
        VisibleSet set = visibility.setEverywhere(now);
        List<String> cells = List.copyOf(new TreeSet<>(set.signals().stream()
                .map(v -> v.signal().getAreaCell()).toList()));
        if (cells.isEmpty()) return new Comparison(0, 0, 0);
        Map<String, CellPatterns> recurring = patterns.recurringIn(cells);
        List<Evidence> evidence = AreaIntelligenceService.evidence(set.signals());
        List<CellPicture> active = AreaIntelligencePolicy.cells(Model.RULES_1_1, cells, evidence, recurring, set.people());
        List<CellPicture> candidate = AreaIntelligencePolicy.cells(Model.RULES_2, cells, evidence, recurring, set.people());
        int less = 0;
        int more = 0;
        for (int i = 0; i < cells.size(); i++) {
            int difference = candidate.get(i).concern().compareTo(active.get(i).concern());
            if (difference < 0) less++;
            if (difference > 0) more++;
        }
        return new Comparison(cells.size(), less, more);
    }
}
