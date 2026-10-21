package com.safesphere.backend.evaluation.simulation;

import com.safesphere.backend.community.entity.PlaceSignal;
import com.safesphere.backend.community.entity.SignalCategory;
import com.safesphere.backend.community.entity.SignalContribution;
import com.safesphere.backend.community.repository.PlaceSignalRepository;
import com.safesphere.backend.community.service.CommunityService;
import com.safesphere.backend.community.service.CommunityService.VisibleSignal;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.community.service.Independence.Linkage;
import com.safesphere.backend.community.service.SignalPolicy.Confidence;
import com.safesphere.backend.community.service.SignalPolicy.Freshness;
import com.safesphere.backend.community.service.TrustService;
import com.safesphere.backend.community.service.TrustService.Snapshot;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Evidence;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every VISIBLE signal (the same set CommunityService.visibleIn gives Safety Intelligence) and the people who currently
 * count behind it, plus the Phase 7 support linkage and weights: counts for the simulation's reports. (The 9C/9D
 * candidate-rule prototypes that used to read it are production code now.) Built in memory for one measurement and
 * thrown away; nothing here is stored.
 */
record SupportSnapshot(List<Entry> signals, Linkage support, Map<Long, Integer> weights) {

    /** One visible signal: who counts behind it (opaque account keys) and its public assessment. */
    record Entry(long id, SignalCategory category, String cell, Confidence confidence, Freshness independentFreshness,
                 boolean disputed, Set<Long> supporters) {

        String areaCell() {
            return cell.substring(0, GeoCell.AREA_PRECISION);
        }

        boolean confirmed() {
            return confidence != Confidence.LOW;
        }

        Evidence evidence() {
            return new Evidence(category, cell, confidence, independentFreshness, disputed);
        }
    }

    static SupportSnapshot of(CommunityService community, TrustService trust, PlaceSignalRepository signals,
                              LocalDateTime now) {
        Set<String> areas = signals.findAll().stream().map(PlaceSignal::getAreaCell).collect(Collectors.toSet());
        List<VisibleSignal> visible = community.visibleIn(areas, now);
        List<SignalContribution> all = visible.stream().flatMap(v -> v.contributions().stream()).toList();
        Snapshot snapshot = trust.snapshot(all, now);
        Map<Long, Integer> weights = new HashMap<>();
        List<Entry> entries = visible.stream().map(v -> {
            PlaceSignal s = v.signal();
            Set<Long> supporters = v.contributions().stream()
                    .filter(c -> c.getKind().supports() && !c.getCountsFrom().isAfter(now)
                            && c.getCountsFrom().isAfter(now.minus(s.getCategory().lifetime()))
                            && snapshot.weight(c.getUserId()) > 0)
                    .map(SignalContribution::getUserId).collect(Collectors.toSet());
            supporters.forEach(id -> weights.put(id, snapshot.weight(id)));
            return new Entry(s.getId(), s.getCategory(), s.getCell(), v.assessment().confidence(),
                    v.assessment().independentFreshness(), v.assessment().disputed(), supporters);
        }).toList();
        return new SupportSnapshot(entries, snapshot.support(), weights);
    }

    List<Entry> in(String areaCell) {
        return signals.stream().filter(e -> e.areaCell().equals(areaCell)).toList();
    }
}
