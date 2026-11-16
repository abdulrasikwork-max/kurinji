package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.social.policy.MomentTimes;
import com.safesphere.backend.social.policy.MomentTimes.Day;
import com.safesphere.backend.social.policy.MomentTimes.Part;
import com.safesphere.backend.social.policy.MomentTimes.Rough;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/**
 * Phase N4: the rough time others get instead of a timestamp, in India time (docs/PHASE_N4.md §3). Stored times are
 * UTC; India is UTC+5:30, so the India day changes at 18:30 UTC.
 */
class MomentTimesTest {

    /** [hour]:[minute] India time on 16 November 2026, as the UTC value the database holds. */
    private static LocalDateTime india(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 11, day, hour, minute).minusHours(5).minusMinutes(30);
    }

    @Test
    void partsOfTheDay_inIndiaTime() {
        assertThat(MomentTimes.part(LocalTime.of(0, 0))).isEqualTo(Part.NIGHT);
        assertThat(MomentTimes.part(LocalTime.of(4, 59))).isEqualTo(Part.NIGHT);
        assertThat(MomentTimes.part(LocalTime.of(5, 0))).isEqualTo(Part.MORNING);
        assertThat(MomentTimes.part(LocalTime.of(11, 59))).isEqualTo(Part.MORNING);
        assertThat(MomentTimes.part(LocalTime.of(12, 0))).isEqualTo(Part.AFTERNOON);
        assertThat(MomentTimes.part(LocalTime.of(16, 59))).isEqualTo(Part.AFTERNOON);
        assertThat(MomentTimes.part(LocalTime.of(17, 0))).isEqualTo(Part.EVENING);
        assertThat(MomentTimes.part(LocalTime.of(20, 59))).isEqualTo(Part.EVENING);
        assertThat(MomentTimes.part(LocalTime.of(21, 0))).isEqualTo(Part.NIGHT);
        assertThat(MomentTimes.part(LocalTime.of(23, 59))).isEqualTo(Part.NIGHT);
    }

    @Test
    void todayAndYesterday_areIndiaCalendarDays_notUtcOnes() {
        LocalDateTime now = india(16, 10, 0);
        assertThat(MomentTimes.rough(india(16, 9, 15), now)).isEqualTo(new Rough(Day.TODAY, Part.MORNING));
        // 00:10 India time on the 16th is still the 15th in UTC (18:40): it is "today" for the community.
        assertThat(india(16, 0, 10).toLocalDate()).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat(MomentTimes.rough(india(16, 0, 10), now)).isEqualTo(new Rough(Day.TODAY, Part.NIGHT));
        assertThat(MomentTimes.rough(india(15, 23, 50), now)).isEqualTo(new Rough(Day.YESTERDAY, Part.NIGHT));
        assertThat(MomentTimes.rough(india(15, 18, 0), now)).isEqualTo(new Rough(Day.YESTERDAY, Part.EVENING));
        assertThat(MomentTimes.rough(india(15, 13, 0), now)).isEqualTo(new Rough(Day.YESTERDAY, Part.AFTERNOON));
    }

    @Test
    void aLiveMoment_isAlwaysTodayOrYesterday() {
        // 24 hours of life can never reach back further than yesterday.
        LocalDateTime since = india(15, 12, 0);
        assertThat(MomentTimes.rough(since, since.plusHours(24).minusSeconds(1)).day()).isEqualTo(Day.YESTERDAY);
        assertThat(MomentTimes.rough(since, since).day()).isEqualTo(Day.TODAY);
    }

    @Test
    void olderKeptMoments_getAWeekAMonthOrEarlier_withoutAPartOfTheDay() {
        LocalDateTime now = india(16, 10, 0);
        assertThat(MomentTimes.rough(india(14, 22, 0), now)).isEqualTo(new Rough(Day.THIS_WEEK, null));
        assertThat(MomentTimes.rough(now.minusDays(7).plusMinutes(1), now)).isEqualTo(new Rough(Day.THIS_WEEK, null));
        assertThat(MomentTimes.rough(now.minusDays(7), now)).isEqualTo(new Rough(Day.THIS_MONTH, null));
        assertThat(MomentTimes.rough(now.minusDays(30).plusMinutes(1), now)).isEqualTo(new Rough(Day.THIS_MONTH, null));
        assertThat(MomentTimes.rough(now.minusDays(30), now)).isEqualTo(new Rough(Day.EARLIER, null));
        assertThat(MomentTimes.rough(now.minusDays(400), now)).isEqualTo(new Rough(Day.EARLIER, null));
    }

    @Test
    void theDailyQuestionsDay_changesAtIndiaMidnight() {
        assertThat(MomentTimes.indiaDay(LocalDateTime.of(2026, 11, 16, 18, 29))).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(MomentTimes.indiaDay(LocalDateTime.of(2026, 11, 16, 18, 30))).isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(MomentTimes.INDIA.getId()).isEqualTo("Asia/Kolkata");
    }
}
