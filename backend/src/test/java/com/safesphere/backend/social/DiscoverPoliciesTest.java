package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.social.policy.ButterflyTally;
import com.safesphere.backend.social.policy.CityCatalog;
import com.safesphere.backend.social.policy.EditionPolicy;
import com.safesphere.backend.social.policy.EditionPolicy.Candidate;
import com.safesphere.backend.social.policy.EditionPolicy.Picked;
import com.safesphere.backend.social.policy.EditionPolicy.Reason;
import com.safesphere.backend.social.policy.EditionPolicy.Section;
import com.safesphere.backend.social.policy.ProfileLists.Interest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Phase 13C: the pure rules of Discover: city boxes (C6), the Edition week, theme and order (C7, C8), the tally (C2). */
class DiscoverPoliciesTest {

    // ---- CityAreas ----

    @Test
    void cityCentres_fallInTheirOwnCity() {
        assertThat(city(13.0827, 80.2707)).isEqualTo("CHENNAI");       // Chennai Central
        assertThat(city(12.9010, 80.2279)).isEqualTo("CHENNAI");       // Sholinganallur
        assertThat(city(11.0168, 76.9558)).isEqualTo("COIMBATORE");
        assertThat(city(9.9252, 78.1198)).isEqualTo("MADURAI");
        assertThat(city(10.7905, 78.7047)).isEqualTo("TIRUCHIRAPPALLI");
        assertThat(city(11.6643, 78.1460)).isEqualTo("SALEM");
        assertThat(city(8.7139, 77.7567)).isEqualTo("TIRUNELVELI");
        assertThat(city(12.9165, 79.1325)).isEqualTo("VELLORE");
        assertThat(city(11.3410, 77.7172)).isEqualTo("ERODE");
        assertThat(city(8.7642, 78.1348)).isEqualTo("THOOTHUKUDI");
        assertThat(city(10.7870, 79.1378)).isEqualTo("THANJAVUR");
        assertThat(city(11.9416, 79.8083)).isEqualTo("PUDUCHERRY");
        assertThat(city(12.9716, 77.5946)).isEqualTo("BENGALURU");
    }

    @Test
    void outsideEveryBox_isNoCity_andPhase17eCitiesAreFound() {
        assertThat(city(28.6139, 77.2090)).isEqualTo("DELHI");                // Phase 17E catalog
        assertThat(city(11.4102, 76.6950)).isEqualTo("OOTY");
        assertThat(CityCatalog.cityOf(GeoCell.encode(20.5, 79.0, 7))).isEmpty(); // open country
        assertThat(CityCatalog.cityOf(GeoCell.encode(0, 0, 7))).isEmpty();
    }

    @Test
    void other_isAPostCityOnly() {
        assertThat(CityCatalog.find(CityCatalog.OTHER)).isEmpty();
        assertThat(CityCatalog.listCity("other")).isEmpty();
        assertThat(CityCatalog.postCity("other")).contains("OTHER");
        assertThat(CityCatalog.listCity(" chennai ")).contains("CHENNAI");
        assertThat(CityCatalog.postCity("ATLANTIS")).isEmpty();
    }

    @Test
    void anAreaCellAndItsBlocks_agreeOnTheCity() {
        String block = GeoCell.encode(13.0500, 80.2500, 7);
        assertThat(CityCatalog.cityOf(block.substring(0, 6))).isEqualTo(CityCatalog.cityOf(block));
    }

    // ---- Edition week and theme ----

    @Test
    void weekStarts_mondayMidnightInChennai() {
        // Sunday 20:00 UTC is already Monday 01:30 in Chennai.
        assertThat(EditionPolicy.weekOf(Instant.parse("2026-10-25T20:00:00Z"))).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(EditionPolicy.weekOf(Instant.parse("2026-10-25T18:29:59Z"))).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(EditionPolicy.weekOf(Instant.parse("2026-10-23T10:00:00Z"))).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(EditionPolicy.startUtc(LocalDate.of(2026, 10, 26))).isEqualTo(LocalDateTime.of(2026, 10, 25, 18, 30));
    }

    @Test
    void theme_rotatesThroughEveryCategory_oncePerWeek() {
        LocalDate monday = LocalDate.of(2026, 10, 19);
        Set<Interest> seen = new HashSet<>();
        Interest previous = null;
        for (int week = 0; week < Interest.values().length; week++) {
            Interest theme = EditionPolicy.theme(monday.plusWeeks(week));
            assertThat(theme).isNotEqualTo(previous);
            seen.add(theme);
            previous = theme;
        }
        assertThat(seen).containsExactlyInAnyOrder(Interest.values());
        assertThat(EditionPolicy.theme(monday)).isEqualTo(EditionPolicy.theme(monday.plusWeeks(10)));
    }

    // ---- Order and reasons ----

    @Test
    void order_completenessAndInterests_thenNewest() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 20, 10, 0);
        List<Candidate<String>> list = new ArrayList<>();
        list.add(new Candidate<>("bare-new", false, false, false, false, false, t.plusHours(5), 1));
        list.add(new Candidate<>("complete-old", true, true, true, false, false, t, 2));
        list.add(new Candidate<>("interest", false, false, false, true, true, t.plusHours(1), 3));
        list.add(new Candidate<>("bare-old", false, false, false, false, false, t, 4));
        List<Picked<String>> picked = EditionPolicy.pick(Section.CITY, list, 10);
        assertThat(picked.stream().map(Picked::item))
                .containsExactly("complete-old", "interest", "bare-new", "bare-old");
        assertThat(EditionPolicy.pick(Section.CITY, list, 2)).hasSize(2);
    }

    @Test
    void reasons_sayWhyAndNothingElse() {
        Candidate<String> c = new Candidate<>("x", true, false, true, true, true, LocalDateTime.of(2026, 1, 1, 0, 0), 1);
        assertThat(EditionPolicy.reasons(Section.CITY, c)).containsExactly(Reason.IN_THIS_CITY, Reason.RECENT,
                Reason.HAS_PLACE, Reason.HAS_TIP, Reason.MATCHES_YOUR_INTERESTS, Reason.BEEN_HERE_TOO);
        Candidate<String> bare = new Candidate<>("y", false, false, false, false, false, LocalDateTime.of(2026, 1, 1, 0, 0), 2);
        assertThat(EditionPolicy.reasons(Section.ASKS, bare)).containsExactly(Reason.IN_THIS_CITY, Reason.OPEN_ASK);
        assertThat(EditionPolicy.reasons(Section.THEME, bare))
                .containsExactly(Reason.IN_THIS_CITY, Reason.THEME_OF_THE_WEEK);
    }

    // ---- Tally (C2) ----

    @Test
    void tally_hidesSmallNumbers() {
        for (long people = 0; people < ButterflyTally.FLOOR; people++) {
            assertThat(ButterflyTally.of(people)).isEqualTo(new ButterflyTally(null, true));
        }
        assertThat(ButterflyTally.of(3)).isEqualTo(new ButterflyTally(3, false));
        assertThat(ButterflyTally.of(41)).isEqualTo(new ButterflyTally(41, false));
    }

    private static String city(double lat, double lng) {
        return CityCatalog.cityOf(GeoCell.encode(lat, lng, 7)).orElse(null);
    }
}
