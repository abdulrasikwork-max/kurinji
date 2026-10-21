package com.safesphere.backend.intelligence.service;

import com.safesphere.backend.community.service.CommunityService.VisibleSignal;
import com.safesphere.backend.community.service.SignalVisibility.VisibleSet;
import com.safesphere.backend.config.IntelligenceProperties;
import com.safesphere.backend.config.IntelligenceProperties.Rules2Mode;
import com.safesphere.backend.intelligence.dto.IntelligenceDtos.AreaPicture;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.CellPatterns;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Evidence;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Model;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Picture;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Phase 8 Safety Intelligence (docs/PHASE8_DESIGN.md). Turns the VISIBLE signals that CommunityService already
 * selected (the one definition of "what anyone may see": no pending, quarantined, removed or retired signal can reach
 * here) into an area picture with AreaIntelligencePolicy, under the ACTIVE rule set (Phase 9D: rules-1.1, or rules-2
 * once the user promotes it).
 *
 * Decision support only. This class, and the whole intelligence package, has no path to emergencies, Safe Walk,
 * the Safety Circle, evidence or moderation of people (IntelligenceIsolationTest), stores nothing and logs no cell.
 */
@Service
public class AreaIntelligenceService {

    private static final Logger log = LoggerFactory.getLogger(AreaIntelligenceService.class);

    private final PatternService patterns;
    private final IntelligenceProperties properties;

    public AreaIntelligenceService(PatternService patterns, IntelligenceProperties properties) {
        this.patterns = patterns;
        this.properties = properties;
    }

    /** The rule set that produces what people see (rules-2 only when app.intelligence.rules-2-mode=ACTIVE). */
    public Model activeModel() {
        return properties.rules2Mode() == Rules2Mode.ACTIVE ? Model.RULES_2 : Model.RULES_1_1;
    }

    /**
     * The picture of [cells] (the viewer's neighbourhood) around [focus]. Returns null if the rules fail: the signals
     * already on their way to the phone must never be withheld because intelligence failed. (Database reads happen
     * BEFORE the guarded part: a failed read fails the request like any other, and never leaves the read-only
     * transaction marked for rollback behind a swallowed exception.)
     */
    public AreaPicture neighbourhood(String focus, List<String> cells, VisibleSet visible) {
        Map<String, CellPatterns> patterns = patterns(cells);
        Model model = activeModel();
        try {
            Picture picture = AreaIntelligencePolicy.neighbourhood(model, focus, cells, evidence(visible.signals()),
                    patterns, visible.people());
            return AreaPicture.of(model.version(), picture);
        } catch (RuntimeException e) {
            // Never the cell or anything about the signals: only that it happened.
            log.warn("Area picture omitted: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** What pattern memory (8C) says about these cells: only "recurring or not". */
    Map<String, CellPatterns> patterns(List<String> cells) {
        return patterns.recurringIn(cells);
    }

    /** The rules' view of visible signals (also used by the 9A simulation to measure the production rules). */
    public static List<Evidence> evidence(Collection<VisibleSignal> visible) {
        return visible.stream().map(v -> new Evidence(v.signal().getCategory(), v.signal().getCell(),
                v.assessment().confidence(), v.assessment().independentFreshness(), v.assessment().disputed(),
                Set.copyOf(v.assessment().independentSupporters())))
                .toList();
    }
}
