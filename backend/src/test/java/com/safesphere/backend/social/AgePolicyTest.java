package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.social.policy.AgePolicy;
import com.safesphere.backend.social.policy.AgePolicy.Result;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Phase 13A (D6): the 18+ rule, including birthdays, 29 February and impossible dates. */
class AgePolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    @Test
    void eighteenthBirthdayToday_isAdult_andTheDayBefore_isNot() {
        assertThat(AgePolicy.check(LocalDate.of(2008, 10, 22), TODAY)).isEqualTo(Result.ADULT);
        assertThat(AgePolicy.check(LocalDate.of(2008, 10, 23), TODAY)).isEqualTo(Result.UNDER_AGE);
    }

    @Test
    void bornOn29February_turns18OnFirstMarchInAYearWithout29February() {
        LocalDate born = LocalDate.of(2008, 2, 29);
        assertThat(AgePolicy.check(born, LocalDate.of(2026, 2, 28))).isEqualTo(Result.UNDER_AGE);
        assertThat(AgePolicy.check(born, LocalDate.of(2026, 3, 1))).isEqualTo(Result.ADULT);
    }

    @Test
    void futureDates_missingDates_andAgesOver120_areInvalid() {
        assertThat(AgePolicy.check(TODAY.plusDays(1), TODAY)).isEqualTo(Result.INVALID);
        assertThat(AgePolicy.check(null, TODAY)).isEqualTo(Result.INVALID);
        assertThat(AgePolicy.check(TODAY.minusYears(121), TODAY)).isEqualTo(Result.INVALID);
        assertThat(AgePolicy.check(TODAY.minusYears(120), TODAY)).isEqualTo(Result.ADULT);
    }

    @Test
    void bornToday_isUnderAge() {
        assertThat(AgePolicy.check(TODAY, TODAY)).isEqualTo(Result.UNDER_AGE);
    }
}
