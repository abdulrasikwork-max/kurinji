package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.social.policy.RatingPolicy;
import org.junit.jupiter.api.Test;

/** Phase 17G-2 (U5): the one rule for a place's public rating (pure). */
class RatingPolicyTest {

    @Test
    void nothingIsShown_untilFiveCountedRatings() {
        for (int raters = 0; raters < RatingPolicy.MIN_RATERS; raters++) {
            assertThat(RatingPolicy.display(raters, raters * 5L)).as(raters + " raters").isNull();
        }
        assertThat(RatingPolicy.display(5, 25)).isNotNull();
    }

    @Test
    void theAverageIsBayesian_priorThreePointFiveWorthFiveRatings() {
        // (3.5 * 5 + sum) / (5 + raters), one decimal, half up
        assertThat(RatingPolicy.display(5, 25)).isEqualTo("4.3");   // five 5s: 42.5 / 10 = 4.25 -> 4.3
        assertThat(RatingPolicy.display(5, 5)).isEqualTo("2.3");    // five 1s: 22.5 / 10 = 2.25 -> 2.3
        assertThat(RatingPolicy.display(5, 15)).isEqualTo("3.3");   // five 3s: 32.5 / 10 = 3.25 -> 3.3
        assertThat(RatingPolicy.display(10, 40)).isEqualTo("3.8");   // 57.5 / 15 = 3.833 -> 3.8
        assertThat(RatingPolicy.display(100, 400)).isEqualTo("4.0"); // 417.5 / 105 = 3.976 -> 4.0
    }

    @Test
    void aFewRatingsCannotSwingTheAverage_manyMoveItTowardsTheirOwnMean() {
        // five fives are held back by the prior; a thousand fives are nearly all the way there
        assertThat(Double.parseDouble(RatingPolicy.display(5, 25))).isLessThan(4.5);
        assertThat(Double.parseDouble(RatingPolicy.display(1000, 5000))).isGreaterThan(4.9);
        // one extra 1 among ten 5s moves it by well under a star
        double ten = Double.parseDouble(RatingPolicy.display(10, 50));
        double eleven = Double.parseDouble(RatingPolicy.display(11, 51));
        assertThat(ten - eleven).isLessThan(0.5);
    }

    @Test
    void impossibleInput_showsNothing() {
        assertThat(RatingPolicy.display(5, 4)).as("a sum below one star each").isNull();
        assertThat(RatingPolicy.display(5, 26)).as("a sum above five stars each").isNull();
        assertThat(RatingPolicy.display(-1, 0)).isNull();
        assertThat(RatingPolicy.display(Long.MAX_VALUE, Long.MAX_VALUE)).isNull();
    }

    @Test
    void theTextHasOneDecimal_andNoCountOrWords() {
        for (int sum = 5; sum <= 25; sum++) {
            assertThat(RatingPolicy.display(5, sum)).matches("[1-5]\\.[0-9]");
        }
    }

    @Test
    void aRatingIsOneToFive() {
        assertThat(RatingPolicy.isValid(1)).isTrue();
        assertThat(RatingPolicy.isValid(5)).isTrue();
        assertThat(RatingPolicy.isValid(0)).isFalse();
        assertThat(RatingPolicy.isValid(6)).isFalse();
        assertThat(RatingPolicy.isValid(-3)).isFalse();
        assertThat(RatingPolicy.isValid(null)).isFalse();
    }

    // ---- Phase 18A-1: independent raters and weights ------------------------------------------------------------------------

    private static RatingPolicy.Rater full(long id, int rating) {
        return new RatingPolicy.Rater(id, rating, true, true);
    }

    @Test
    void unconnectedEstablishedRaters_giveTheSameTextAsBefore() {
        var five = java.util.List.of(full(1, 5), full(2, 5), full(3, 5), full(4, 5), full(5, 5));
        assertThat(RatingPolicy.display(five, java.util.List.of())).isEqualTo(RatingPolicy.display(5, 25)).isEqualTo("4.3");
        assertThat(RatingPolicy.display(five, null)).isEqualTo("4.3");
    }

    @Test
    void fiveFriendsRatingAFriendsPlace_areOneVoice_andShowNothing() {
        var five = java.util.List.of(full(1, 5), full(2, 5), full(3, 5), full(4, 5), full(5, 5));
        // a chain 1-2-3-4-5: connected through each other, so one group
        var chain = java.util.List.of(new RatingPolicy.Link(1, 2), new RatingPolicy.Link(2, 3), new RatingPolicy.Link(3, 4),
                new RatingPolicy.Link(4, 5));
        assertThat(RatingPolicy.display(five, chain)).isNull();
        // a link to somebody who did not rate this place joins nothing
        assertThat(RatingPolicy.display(five, java.util.List.of(new RatingPolicy.Link(1, 99)))).isEqualTo("4.3");
    }

    @Test
    void aGroupAddsOneVoice_howeverManyMembersItHas() {
        // four independent people say 2; a group of six friends says 5: five voices, the six count as one
        var raters = new java.util.ArrayList<RatingPolicy.Rater>();
        for (long id = 1; id <= 4; id++) raters.add(full(id, 2));
        var links = new java.util.ArrayList<RatingPolicy.Link>();
        for (long id = 10; id < 16; id++) {
            raters.add(full(id, 5));
            if (id > 10) links.add(new RatingPolicy.Link(10, id));
        }
        assertThat(RatingPolicy.display(raters, links)).isEqualTo("3.1");   // (17.5 + 8 + 5) / 10 = 3.05
        assertThat(RatingPolicy.display(raters, java.util.List.of())).isEqualTo("3.7"); // unlinked: (17.5 + 8 + 30) / 15 = 3.7
    }

    @Test
    void aNewOrOnePlaceAccount_countsHalf_neverZero() {
        var young = new java.util.ArrayList<RatingPolicy.Rater>();
        for (long id = 1; id <= 5; id++) young.add(new RatingPolicy.Rater(id, 5, false, true));
        assertThat(RatingPolicy.display(young, null)).isEqualTo("4.0");     // (17.5 + 12.5) / 7.5
        var narrow = new java.util.ArrayList<RatingPolicy.Rater>();
        for (long id = 1; id <= 5; id++) narrow.add(new RatingPolicy.Rater(id, 5, true, false));
        assertThat(RatingPolicy.display(narrow, null)).isEqualTo("4.0");
        var both = new java.util.ArrayList<RatingPolicy.Rater>();
        for (long id = 1; id <= 5; id++) both.add(new RatingPolicy.Rater(id, 5, false, false));
        assertThat(RatingPolicy.display(both, null)).as("half, not a quarter").isEqualTo("4.0");
    }

    @Test
    void insideAGroup_theEstablishedMemberWeighsMore_andTheGroupSpeaksWithItsStrongestWeight() {
        var raters = new java.util.ArrayList<RatingPolicy.Rater>();
        for (long id = 1; id <= 4; id++) raters.add(full(id, 3));
        raters.add(full(10, 1));                                            // established friend says 1
        raters.add(new RatingPolicy.Rater(11, 5, false, false));            // their new one-place friend says 5
        var link = java.util.List.of(new RatingPolicy.Link(10, 11));
        // the group's voice = (1*1 + 0.5*5) / 1.5 = 2.33, weight 1: (17.5 + 12 + 2.33) / 10 = 3.18
        assertThat(RatingPolicy.display(raters, link)).isEqualTo("3.2");
    }

    @Test
    void impossibleRaters_showNothing() {
        assertThat(RatingPolicy.display(java.util.List.of(), null)).isNull();
        assertThat(RatingPolicy.display(null, null)).isNull();
        var bad = java.util.List.of(full(1, 5), full(2, 5), full(3, 5), full(4, 5), full(5, 9));
        assertThat(RatingPolicy.display(bad, null)).isNull();
        var twice = java.util.List.of(full(1, 5), full(1, 5), full(3, 5), full(4, 5), full(5, 5));
        assertThat(RatingPolicy.display(twice, null)).isNull();
    }
}
