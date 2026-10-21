package com.safesphere.backend.intelligence.service;

import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.community.service.CommunityService;
import com.safesphere.backend.community.service.CommunityService.VisibleSignal;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.community.service.SignalPolicy.Confidence;
import com.safesphere.backend.community.service.SignalVisibility.VisibleSet;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Model;
import com.safesphere.backend.config.CommunityProperties;
import com.safesphere.backend.config.IntelligenceProperties;
import com.safesphere.backend.intelligence.dto.IntelligenceDtos.RegionArea;
import com.safesphere.backend.intelligence.dto.IntelligenceDtos.RegionPack;
import com.safesphere.backend.intelligence.dto.IntelligenceDtos.RegionSignal;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Information;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Region packs (Phase 8D, docs/PHASE8_DESIGN.md §13.2, §16): what a phone needs to brief a walk ON THE PHONE, so the
 * server never receives a route, a start or a destination: only one ~5 km region at a time, within a small daily
 * budget (RegionQueryLimiter). The pack holds per-area pictures (the active rules, AreaIntelligenceService.activeModel)
 * and the CONFIRMED signals of the region at the block precision they are public at anyway; single reports and signal
 * ids are never included.
 */
@Service
public class RegionIntelligenceService {

    private final CommunityService community;
    private final PatternService patterns;
    private final RegionQueryLimiter limiter;
    private final IntelligenceProperties properties;
    private final CommunityProperties communityProperties;
    private final UserRepository users;
    private final AreaIntelligenceService intelligence;

    public RegionIntelligenceService(CommunityService community, PatternService patterns, RegionQueryLimiter limiter,
                                     IntelligenceProperties properties, CommunityProperties communityProperties,
                                     UserRepository users, AreaIntelligenceService intelligence) {
        this.intelligence = intelligence;
        this.community = community;
        this.patterns = patterns;
        this.limiter = limiter;
        this.properties = properties;
        this.communityProperties = communityProperties;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public RegionPack region(long userId, String region) {
        LocalDateTime now = community.currentTime();
        User user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        boolean newAccount = user.getCreatedAt().plus(communityProperties.newAccountProbation()).isAfter(now);
        limiter.check(userId, region, newAccount);

        List<String> cells = GeoCell.children(region);
        VisibleSet set = community.visibleSetIn(cells, now);
        List<VisibleSignal> visible = set.signals();
        Model model = intelligence.activeModel();
        List<RegionArea> areas = AreaIntelligencePolicy.cells(model, cells, AreaIntelligenceService.evidence(visible),
                        patterns.recurringIn(cells), set.people()).stream()
                .filter(p -> p.information() != Information.NONE)
                .map(RegionArea::of)
                .toList();
        List<RegionSignal> confirmed = visible.stream()
                .filter(v -> v.assessment().confidence() != Confidence.LOW)
                .map(v -> new RegionSignal(v.signal().getCategory(), v.signal().getCategory().group(),
                        v.signal().getCell(), v.assessment().confidence(), v.assessment().independentFreshness(),
                        v.assessment().disputed()))
                .sorted(Comparator.comparing(RegionSignal::cell).thenComparing(RegionSignal::category))
                .toList();
        boolean complete = confirmed.size() <= properties.regionMaxSignals();
        return new RegionPack(region, model.version(), complete, areas,
                complete ? confirmed : List.of());
    }
}
