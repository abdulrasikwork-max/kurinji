package com.safesphere.backend.social.service;

import com.safesphere.backend.config.SocialProperties;
import com.safesphere.backend.social.policy.RatingPolicy;
import com.safesphere.backend.social.repository.PlaceVisitRepository;
import com.safesphere.backend.social.repository.PlaceVisitRepository.Totals;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Phase 17G-2 (U5): the public rating of a place, computed at most ONCE per IST day per place. The first read of a day reads the
 * counted ratings and keeps the answer (a text, or "none") until the next IST day, so a rating given today changes what others see
 * tomorrow at the earliest - which blunts "what was the average before and after one more rating?" inference. In memory by design
 * (no new scheduled job, nothing stored): a restart recomputes, an accepted trade-off documented in PHASE17G2.md. Counts only
 * accounts past probation (RatingPolicy decides the rest). Returns text only: never a count.
 */
@Component
public class PlaceRatings {

    /** More places than this in the memo: it is cleared (the next reads recompute), so memory stays bounded. */
    static final int MAX_MEMO = 20_000;

    private record Memo(LocalDate day, String text) {
    }

    private final PlaceVisitRepository visits;
    private final SocialProperties properties;
    private final Clock clock;
    private final Map<Long, Memo> memo = new HashMap<>();

    public PlaceRatings(PlaceVisitRepository visits, SocialProperties properties, Clock clock) {
        this.visits = visits;
        this.properties = properties;
        this.clock = clock;
    }

    /** The place's rating text ("4.3") or null (not enough ratings yet). */
    public synchronized String textFor(long placeId) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), DailyStateService.INDIA);
        Memo known = memo.get(placeId);
        if (known != null && known.day().equals(today)) return known.text();
        LocalDateTime cutoff = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).minus(properties.probation());
        Totals totals = visits.totals(placeId, cutoff);
        String text = RatingPolicy.display(totals.raters(), totals.sum());
        if (memo.size() >= MAX_MEMO) memo.clear();
        memo.put(placeId, new Memo(today, text));
        return text;
    }
}
