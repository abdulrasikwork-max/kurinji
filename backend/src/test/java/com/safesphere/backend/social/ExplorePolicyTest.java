package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.social.policy.ExplorePolicy;
import com.safesphere.backend.social.policy.ExplorePolicy.Candidate;
import com.safesphere.backend.social.policy.ExplorePolicy.Hit;
import com.safesphere.backend.social.policy.ExplorePolicy.Query;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Phase 17G-1: which places Explore finds and their order. Pure: no database, no clock. */
class ExplorePolicyTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 1, 10, 0);

    private static Query query(Set<String> categories, Set<String> facets, String daypart) {
        return new Query("CHENNAI", categories, facets, daypart);
    }

    private static Candidate place(long id, String name, String category, Set<String> facets, Set<String> dayparts,
                                   int daysAgo) {
        return new Candidate(id, category, facets, dayparts, T0.minusDays(daysAgo), name);
    }

    @Test
    void requested_countsTheCategoryAsOne_theTagsAndTheDaypart() {
        assertThat(query(Set.of(), Set.of(), null).requested()).isZero();
        assertThat(query(Set.of("FOOD_CAFES"), Set.of(), null).requested()).isEqualTo(1);
        // Two categories are ONE requirement: a place has one category, so counting both would make a full match impossible.
        assertThat(query(Set.of("FOOD_CAFES", "NATURE_OUTDOORS"), Set.of("QUIET", "SCENIC"), "EVENING").requested())
                .isEqualTo(4);
    }

    @Test
    void nothingAsked_findsNothing() {
        assertThat(ExplorePolicy.rank(query(Set.of(), Set.of(), null),
                List.of(place(1, "a", "FOOD_CAFES", Set.of("QUIET"), Set.of("EVENING"), 1)))).isEmpty();
    }

    @Test
    void aPlaceWithNoMatchIsNotListed_andAFullMatchComesBeforeAPartialOne() {
        Query q = query(Set.of("FOOD_CAFES"), Set.of("QUIET", "SCENIC"), null); // requested = 3
        Candidate full = place(1, "full", "FOOD_CAFES", Set.of("QUIET", "SCENIC"), Set.of(), 30);
        Candidate partial = place(2, "partial", "FOOD_CAFES", Set.of("QUIET"), Set.of(), 1);
        Candidate other = place(3, "other", "HOBBIES_MAKING", Set.of("OPEN_LATE"), Set.of("NIGHT"), 1);
        List<Hit> hits = ExplorePolicy.rank(q, List.of(other, partial, full));
        assertThat(hits).extracting(h -> h.place().placeId()).containsExactly(1L, 2L);
        assertThat(hits.get(0).matched()).isEqualTo(3);
        assertThat(hits.get(0).requested()).isEqualTo(3);
        assertThat(hits.get(1).matched()).isEqualTo(2);
        assertThat(hits.get(0).matchedCategory()).isTrue();
        assertThat(hits.get(0).matchedFacets()).containsExactly("QUIET", "SCENIC");
    }

    @Test
    void daypartAndCategoryAndTagsAllCount() {
        Query q = query(Set.of("MUSIC_NIGHTLIFE"), Set.of("LIVELY"), "NIGHT");
        Hit hit = ExplorePolicy.rank(q, List.of(
                place(1, "club", "MUSIC_NIGHTLIFE", Set.of("LIVELY"), Set.of("NIGHT"), 2))).get(0);
        assertThat(hit.matched()).isEqualTo(3);
        assertThat(hit.matchedDaypart()).isTrue();
        assertThat(hit.matchedCategory()).isTrue();
    }

    @Test
    void ties_goToTheNewerVisibleExperience_thenTheName_thenTheId() {
        Query q = query(Set.of("FOOD_CAFES"), Set.of(), null);
        Candidate older = place(1, "alpha", "FOOD_CAFES", Set.of(), Set.of(), 10);
        Candidate newer = place(2, "zeta", "FOOD_CAFES", Set.of(), Set.of(), 2);
        assertThat(ExplorePolicy.rank(q, List.of(older, newer))).extracting(h -> h.place().placeId())
                .containsExactly(2L, 1L);
        Candidate b = place(4, "bravo", "FOOD_CAFES", Set.of(), Set.of(), 5);
        Candidate a = place(5, "alpha", "FOOD_CAFES", Set.of(), Set.of(), 5);
        assertThat(ExplorePolicy.rank(q, List.of(b, a))).extracting(h -> h.place().placeId()).containsExactly(5L, 4L);
        Candidate sameA = place(7, "same", "FOOD_CAFES", Set.of(), Set.of(), 5);
        Candidate sameB = place(6, "same", "FOOD_CAFES", Set.of(), Set.of(), 5);
        assertThat(ExplorePolicy.rank(q, List.of(sameA, sameB))).extracting(h -> h.place().placeId())
                .containsExactly(6L, 7L);
    }

    /**
     * "No popularity ranking": a place that carries MORE tags than were asked for gains nothing from them, so the extra
     * experiences/tags a busy place has can never lift it above an equal match.
     */
    @Test
    void extraTagsAndExtraDaypartsNeverLiftAPlace() {
        Query q = query(Set.of(), Set.of("QUIET"), null);
        Candidate busy = place(1, "busy", "FOOD_CAFES", Set.of("QUIET", "LIVELY", "SCENIC", "OPEN_LATE"),
                Set.of("MORNING", "AFTERNOON", "EVENING", "NIGHT"), 20);
        Candidate calm = place(2, "calm", "FOOD_CAFES", Set.of("QUIET"), Set.of("MORNING"), 1);
        List<Hit> hits = ExplorePolicy.rank(q, List.of(busy, calm));
        assertThat(hits).extracting(h -> h.matched()).containsExactly(1, 1);
        assertThat(hits).extracting(h -> h.place().placeId()).containsExactly(2L, 1L); // by recency, not by how much
    }

    @Test
    void atMostTwentyPlaces_andTheSameAnswerForAnyInputOrder() {
        Query q = query(Set.of("FOOD_CAFES"), Set.of(), null);
        List<Candidate> many = new ArrayList<>();
        for (int i = 0; i < 35; i++) many.add(place(i + 1, "p" + (100 + i), "FOOD_CAFES", Set.of(), Set.of(), i));
        List<Long> expected = ExplorePolicy.rank(q, many).stream().map(h -> h.place().placeId()).toList();
        assertThat(expected).hasSize(ExplorePolicy.MAX_RESULTS);
        Random random = new Random(17);
        for (int round = 0; round < 10; round++) {
            List<Candidate> shuffled = new ArrayList<>(many);
            Collections.shuffle(shuffled, random);
            assertThat(ExplorePolicy.rank(q, shuffled).stream().map(h -> h.place().placeId()).toList())
                    .isEqualTo(expected);
        }
    }
}
