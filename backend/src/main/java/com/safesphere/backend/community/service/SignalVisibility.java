package com.safesphere.backend.community.service;

import com.safesphere.backend.community.entity.ModerationState;
import com.safesphere.backend.community.entity.PlaceSignal;
import com.safesphere.backend.community.entity.SignalContribution;
import com.safesphere.backend.community.repository.PlaceSignalRepository;
import com.safesphere.backend.community.repository.SignalContributionRepository;
import com.safesphere.backend.community.service.CommunityService.VisibleSignal;
import com.safesphere.backend.community.service.Independence.Linkage;
import com.safesphere.backend.community.service.SignalPolicy.Assessment;
import com.safesphere.backend.community.service.TrustService.Snapshot;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * THE definition of "what anyone may see" (Phase 8; moved here from CommunityService in Phase 9C so that moderation's
 * coordination check can read the same set without depending on CommunityService, which depends on moderation):
 * moderation NONE and visible by SignalPolicy under the private trust snapshot. Pending (delayed), quarantined, removed,
 * retired and expired signals never appear. CommunityService.visibleIn delegates here; nothing else decides visibility.
 */
@Component
public class SignalVisibility {

    private final PlaceSignalRepository signals;
    private final SignalContributionRepository contributions;
    private final TrustService trust;

    public SignalVisibility(PlaceSignalRepository signals, SignalContributionRepository contributions,
                            TrustService trust) {
        this.signals = signals;
        this.contributions = contributions;
        this.trust = trust;
    }

    /**
     * The visible signals plus Phase 7's PEOPLE-level support linkage among everyone behind them (Phase 9D: rules that
     * count people across several signals, rules-2, need it; it comes from the same snapshot, so no extra query).
     * Server-internal: it carries account keys and is never serialised.
     */
    public record VisibleSet(List<VisibleSignal> signals, Linkage people) {
        static final VisibleSet EMPTY = new VisibleSet(List.of(), Linkage.NONE);
    }

    /** Visible signals in these area cells. */
    @Transactional(readOnly = true)
    public List<VisibleSignal> in(Collection<String> areaCells, LocalDateTime now) {
        return setIn(areaCells, now).signals();
    }

    /** The same, with the people-level linkage (Phase 9D). */
    @Transactional(readOnly = true)
    public VisibleSet setIn(Collection<String> areaCells, LocalDateTime now) {
        return of(signals.findByAreaCellIn(areaCells), now);
    }

    /** Every visible signal (for the periodic coordination check; never returned to a client). */
    @Transactional(readOnly = true)
    public List<VisibleSignal> everywhere(LocalDateTime now) {
        return setEverywhere(now).signals();
    }

    /** The same, with the people-level linkage (Phase 9D: the periodic rules-2 shadow comparison). */
    @Transactional(readOnly = true)
    public VisibleSet setEverywhere(LocalDateTime now) {
        return of(signals.findByModeration(ModerationState.NONE), now);
    }

    private VisibleSet of(List<PlaceSignal> candidates, LocalDateTime now) {
        List<PlaceSignal> found = candidates.stream().filter(PlaceSignal::isUnmoderated).toList();
        if (found.isEmpty()) return VisibleSet.EMPTY;
        Set<Long> ids = found.stream().map(PlaceSignal::getId).collect(Collectors.toSet());
        Map<Long, List<SignalContribution>> bySignal = contributions.findBySignalIdIn(ids).stream()
                .collect(Collectors.groupingBy(SignalContribution::getSignalId));
        Snapshot snapshot = trust.snapshot(bySignal.values().stream().flatMap(List::stream).toList(), now);
        List<VisibleSignal> visible = new ArrayList<>();
        for (PlaceSignal signal : found) {
            List<SignalContribution> list = bySignal.getOrDefault(signal.getId(), List.of());
            Assessment assessment = snapshot.assess(signal, list, now);
            if (assessment.visible()) visible.add(new VisibleSignal(signal, list, assessment));
        }
        return new VisibleSet(List.copyOf(visible), snapshot.support());
    }
}
