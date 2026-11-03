package com.safesphere.backend.social.policy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Phase 17G-1 (D-2: models produce evidence, a deterministic policy decides): which PLACES an Explore request finds and in
 * what order. Pure and side-effect free; the service only gathers the candidates the viewer may see (the one visibility
 * rule) and calls this. The rules, each pinned by ExplorePolicyTest:
 *
 * <ul>
 *   <li>A request has at most one "category" requirement (any of the categories asked for), up to four tags and an
 *       optional time of day. {@code requested} = (categories asked ? 1 : 0) + tags + (time of day ? 1 : 0). A place has
 *       one category, so counting each category separately would make a full match impossible.</li>
 *   <li>A place matches the category requirement if its category is one asked for, a tag if any experience the viewer
 *       can read at that place carries it, the time of day likewise. A place with no match is not listed.</li>
 *   <li>Order: more matches first, then the place whose newest readable experience became visible most recently, then the
 *       place's normalised name, then its id. Nothing here counts experiences, people, reactions or ratings: adding more
 *       posts to a place can never move it up. That is the "no popularity ranking" rule.</li>
 *   <li>At most {@link #MAX_RESULTS} places; the list always ends.</li>
 * </ul>
 */
public final class ExplorePolicy {

    public static final int MAX_RESULTS = 20;

    /** The structured request: every field is from a closed list (checked before it gets here). */
    public record Query(String city, Set<String> categories, Set<String> facets, String daypart) {

        /** How many things the person asked for. */
        public int requested() {
            return (categories.isEmpty() ? 0 : 1) + facets.size() + (daypart == null ? 0 : 1);
        }
    }

    /**
     * A place as the viewer may know it: from the experiences they can read at it only. [newestVisible] is the
     * {@code visible_from} of the newest of those (used only to break ties, never shown); [normalizedName] the tie-break after.
     */
    public record Candidate(long placeId, String category, Set<String> facets, Set<String> dayparts,
                            LocalDateTime newestVisible, String normalizedName) {
    }

    /** One listed place and what matched. */
    public record Hit(Candidate place, boolean matchedCategory, List<String> matchedFacets, boolean matchedDaypart,
                      int requested, int matched) {
    }

    private ExplorePolicy() {
    }

    /** The places to show for [query], best match first, at most {@link #MAX_RESULTS}. Empty when nothing was asked. */
    public static List<Hit> rank(Query query, List<Candidate> candidates) {
        int requested = query.requested();
        if (requested == 0) return List.of();
        List<Hit> hits = new ArrayList<>();
        for (Candidate place : candidates) {
            boolean category = !query.categories().isEmpty() && query.categories().contains(place.category());
            // Facets in the order of the request's own (sorted) set, so the answer is the same on every run.
            List<String> facets = new ArrayList<>(new TreeSet<>(query.facets()));
            facets.retainAll(place.facets());
            boolean daypart = query.daypart() != null && place.dayparts().contains(query.daypart());
            int matched = (category ? 1 : 0) + facets.size() + (daypart ? 1 : 0);
            if (matched > 0) hits.add(new Hit(place, category, List.copyOf(facets), daypart, requested, matched));
        }
        hits.sort(Comparator.<Hit>comparingInt(Hit::matched).reversed()
                .thenComparing((Hit h) -> h.place().newestVisible(), Comparator.reverseOrder())
                .thenComparing(h -> h.place().normalizedName())
                .thenComparingLong(h -> h.place().placeId()));
        return hits.size() <= MAX_RESULTS ? List.copyOf(hits) : List.copyOf(hits.subList(0, MAX_RESULTS));
    }
}
