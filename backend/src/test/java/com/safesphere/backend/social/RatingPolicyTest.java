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
}
