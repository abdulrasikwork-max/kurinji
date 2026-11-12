package com.safesphere.backend.social.service;

import com.safesphere.backend.config.SocialProperties;
import com.safesphere.backend.social.policy.RatingPolicy;
import com.safesphere.backend.social.repository.PlaceVisitRepository;
import com.safesphere.backend.social.repository.ConnectionRepository;
import com.safesphere.backend.social.repository.PlaceVisitRepository.RaterRow;
import java.util.List;
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
    private final ConnectionRepository connections;
    private final SocialProperties properties;
    private final Clock clock;
    private final Map<Long, Memo> memo = new HashMap<>();

    public PlaceRatings(PlaceVisitRepository visits, ConnectionRepository connections, SocialProperties properties, Clock clock) {
        this.visits = visits;
        this.connections = connections;
        this.properties = properties;
        this.clock = clock;
    }

    /** The place's rating text ("4.3") or null (not enough ratings yet). */
    public synchronized String textFor(long placeId) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), DailyStateService.INDIA);
        Memo known = memo.get(placeId);
        if (known != null && known.day().equals(today)) return known.text();
        LocalDateTime cutoff = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).minus(properties.probation());
        String text = compute(placeId, cutoff);
        if (memo.size() >= MAX_MEMO) memo.clear();
        memo.put(placeId, new Memo(today, text));
        return text;
    }

    /**
     * Phase 18A-1: the counted ratings with their weights, and which raters are connected to each other; RatingPolicy turns
     * them into the one text. Nothing read here is kept or returned: only the text is.
     */
    private String compute(long placeId, LocalDateTime cutoff) {
        List<RaterRow> rows = visits.raters(placeId, cutoff);
        if (rows.size() < RatingPolicy.MIN_RATERS) return null;
        LocalDateTime establishedBefore = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .minusDays(RatingPolicy.ESTABLISHED_AFTER_DAYS);
        List<RatingPolicy.Rater> raters = rows.stream().map(row -> new RatingPolicy.Rater(row.userId(), row.rating(),
                !row.accountCreatedAt().isAfter(establishedBefore),
                row.placesRated() >= RatingPolicy.BROAD_FROM_PLACES)).toList();
        List<RatingPolicy.Link> links = connections.acceptedAmong(rows.stream().map(RaterRow::userId).toList()).stream()
                .map(c -> new RatingPolicy.Link(c.getUserLow(), c.getUserHigh())).toList();
        return RatingPolicy.display(raters, links);
    }
}
