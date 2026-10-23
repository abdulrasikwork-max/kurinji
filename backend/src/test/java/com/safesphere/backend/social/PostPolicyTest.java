package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.social.entity.PostEnums.Audience;
import com.safesphere.backend.social.entity.PostEnums.City;
import com.safesphere.backend.social.entity.PostEnums.PostType;
import com.safesphere.backend.social.entity.PostEnums.Precision;
import com.safesphere.backend.social.policy.PostDelay;
import com.safesphere.backend.social.policy.PostPolicy;
import com.safesphere.backend.social.policy.PostPolicy.Draft;
import com.safesphere.backend.social.policy.TimeBands;
import com.safesphere.backend.social.policy.TimeBands.Band;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Optional;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** Phase 13B: PostPolicy (every type x precision x place/city combination, lengths, months), TimeBands, PostDelay. */
class PostPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 12, 0);
    private static final YearMonth THIS_MONTH = YearMonth.of(2026, 10);

    @Test
    void everyTypeAndPrecision_withPlaceAndCityCombinations() {
        for (PostType type : PostType.values()) {
            YearMonth month = type == PostType.EXPERIENCE ? THIS_MONTH : null;
            String body = "A long enough body for an Ask too";
            for (Precision precision : Precision.values()) {
                for (boolean hasPlace : new boolean[] {false, true}) {
                    for (City city : new City[] {null, City.CHENNAI}) {
                        boolean valid = switch (precision) {
                            case EXACT, AREA -> hasPlace && city == null;
                            case CITY -> !hasPlace && city != null;
                            case NONE -> !hasPlace && city == null;
                        };
                        Optional<String> result = PostPolicy.check(
                                new Draft(type, body, null, precision, hasPlace, city, month, Audience.EVERYONE, 0), NOW);
                        assertThat(result.isEmpty()).as(type + " " + precision + " place=" + hasPlace + " city=" + city)
                                .isEqualTo(valid);
                    }
                }
            }
        }
    }

    @Test
    void experience_lengthsTipAndMonth() {
        assertThat(experience("x", null, THIS_MONTH)).isEmpty();
        assertThat(experience("", null, THIS_MONTH)).isPresent();
        assertThat(experience("x".repeat(5000), null, THIS_MONTH)).isEmpty();
        assertThat(experience("x".repeat(5001), null, THIS_MONTH)).isPresent();
        assertThat(experience("x", "t".repeat(300), THIS_MONTH)).isEmpty();
        assertThat(experience("x", "t".repeat(301), THIS_MONTH)).isPresent();
        assertThat(experience("x", null, null)).contains("Choose the month it happened");
        assertThat(experience("x", null, YearMonth.of(1949, 12))).isPresent();
        assertThat(experience("x", null, YearMonth.of(2026, 9))).isEmpty();
    }

    @Test
    void month_isNeverInTheFuture_butAllowsTheNextMonthJustAfterMidnightInIndia() {
        // 31 Oct 19:00 UTC = 1 Nov 00:30 in India: "November" is this month there.
        LocalDateTime lastEvening = LocalDateTime.of(2026, 10, 31, 19, 0);
        assertThat(TimeBands.isAllowedMonth(YearMonth.of(2026, 11), lastEvening)).isTrue();
        assertThat(TimeBands.isAllowedMonth(YearMonth.of(2026, 11), NOW)).isFalse();
        assertThat(TimeBands.isAllowedMonth(YearMonth.of(2026, 12), lastEvening)).isFalse();
    }

    @Test
    void ask_needsAQuestion_noTip_noMonth() {
        assertThat(ask("Anyone been?", null, null)).isEmpty();
        assertThat(ask("Is it ok?", null, null)).isPresent();                  // 9 characters
        assertThat(ask("x".repeat(1000), null, null)).isEmpty();
        assertThat(ask("x".repeat(1001), null, null)).isPresent();
        assertThat(ask("Anyone been there?", "tip", null)).contains("A question has no tip");
        assertThat(ask("Anyone been there?", null, THIS_MONTH)).contains("A question has no month");
    }

    @Test
    void missingChoices_andTooManyPhotos() {
        assertThat(PostPolicy.check(new Draft(PostType.EXPERIENCE, "x", null, Precision.NONE, false, null, THIS_MONTH,
                null, 0), NOW)).contains("Choose who can see this");
        assertThat(PostPolicy.check(new Draft(PostType.EXPERIENCE, "x", null, Precision.NONE, false, null, THIS_MONTH,
                Audience.ONLY_ME, 6), NOW)).isEmpty();
        assertThat(PostPolicy.check(new Draft(PostType.EXPERIENCE, "x", null, Precision.NONE, false, null, THIS_MONTH,
                Audience.ONLY_ME, 7), NOW)).isPresent();
    }

    @Test
    void edits_mayOnlyNarrow() {
        assertThat(PostPolicy.isNarrowerOrSame(Audience.EVERYONE, Audience.CONNECTIONS)).isTrue();
        assertThat(PostPolicy.isNarrowerOrSame(Audience.CONNECTIONS, Audience.CONNECTIONS)).isTrue();
        assertThat(PostPolicy.isNarrowerOrSame(Audience.ONLY_ME, Audience.CONNECTIONS)).isFalse();
        assertThat(PostPolicy.isNarrowerOrSame(Precision.EXACT, Precision.NONE)).isTrue();
        assertThat(PostPolicy.isNarrowerOrSame(Precision.CITY, Precision.AREA)).isFalse();
    }

    @Test
    void bodyCleaning_keepsLineBreaks_dropsInvisibleCharacters() {
        assertThat(PostPolicy.cleanBody("  First line  \r\n\r\n\r\n\r\nSecond​  line \u0007 ")).isEqualTo(
                "First line\n\nSecond line");
        assertThat(PostPolicy.cleanBody(null)).isNull();
    }

    @Test
    void bands_atTheirEdges() {
        assertThat(TimeBands.band(NOW, NOW)).isEqualTo(Band.THIS_WEEK);
        assertThat(TimeBands.band(NOW.minusDays(7).plusSeconds(1), NOW)).isEqualTo(Band.THIS_WEEK);
        assertThat(TimeBands.band(NOW.minusDays(7), NOW)).isEqualTo(Band.THIS_MONTH);
        assertThat(TimeBands.band(NOW.minusDays(30).plusSeconds(1), NOW)).isEqualTo(Band.THIS_MONTH);
        assertThat(TimeBands.band(NOW.minusDays(30), NOW)).isEqualTo(Band.EARLIER);
    }

    @Test
    void delay_onlyForExact_andAlwaysWithinItsRange() {
        SplittableRandom random = new SplittableRandom(13);
        Duration min = Duration.ofHours(1);
        Duration max = Duration.ofHours(3);
        for (Precision precision : new Precision[] {Precision.AREA, Precision.CITY, Precision.NONE}) {
            assertThat(PostDelay.delay(precision, min, max, random)).isZero();
        }
        Duration shortest = max;
        Duration longest = min;
        for (int i = 0; i < 2000; i++) {
            Duration d = PostDelay.delay(Precision.EXACT, min, max, random);
            assertThat(d).isBetween(min, max);
            if (d.compareTo(shortest) < 0) shortest = d;
            if (d.compareTo(longest) > 0) longest = d;
        }
        assertThat(shortest).isLessThan(Duration.ofMinutes(70));   // the whole range is used
        assertThat(longest).isGreaterThan(Duration.ofMinutes(170));
    }

    private static Optional<String> experience(String body, String tip, YearMonth month) {
        return PostPolicy.check(new Draft(PostType.EXPERIENCE, body, tip, Precision.NONE, false, null, month,
                Audience.EVERYONE, 0), NOW);
    }

    private static Optional<String> ask(String body, String tip, YearMonth month) {
        return PostPolicy.check(new Draft(PostType.ASK, body, tip, Precision.NONE, false, null, month,
                Audience.EVERYONE, 0), NOW);
    }
}
