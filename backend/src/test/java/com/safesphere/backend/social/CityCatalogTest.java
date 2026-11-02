package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.social.policy.CityCatalog;
import com.safesphere.backend.social.policy.CityCatalog.City;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Phase 17E (D-5): the ONE city catalog. The file the backend reads, the rows V28 seeds into the cities table and the
 * copy bundled in the app must be the same; codes fit the VARCHAR(20) columns and the code CHECK; boxes are valid and
 * never overlap (a place is in at most one city); the 12 launch cities keep their 13C boxes (places already use them).
 */
class CityCatalogTest {

    private static final Path FILE = Path.of("src/main/resources/cities/cities-v1.tsv");
    private static final Path V28 = Path.of("src/main/resources/db/migration/V28__social_redesign.sql");
    private static final Path APP_COPY = Path.of("../android/app/src/main/resources/cities/cities-v1.tsv");
    private static final Pattern ROW = Pattern.compile(
            "^\\s*\\('([A-Z]+)', '([^']+)', '([^']+)', ([0-9.]+), ([0-9.]+), ([0-9.]+), ([0-9.]+)\\),?\\s*$");

    @Test
    void codesAreUpperCaseLetters_atMost20_unique_andNeverOther() {
        Set<String> seen = new HashSet<>();
        for (City city : CityCatalog.all()) {
            assertThat(city.code()).matches("[A-Z]{2,20}").isNotEqualTo(CityCatalog.OTHER);
            assertThat(seen.add(city.code())).as(city.code()).isTrue();
            assertThat(city.name()).isNotBlank().hasSizeLessThanOrEqualTo(40);
            assertThat(city.state()).isNotBlank().hasSizeLessThanOrEqualTo(40);
        }
        assertThat(CityCatalog.all()).hasSizeGreaterThanOrEqualTo(60);
    }

    @Test
    void boxesAreValid_inIndia_andNeverOverlap() {
        List<City> all = CityCatalog.all();
        for (City c : all) {
            assertThat(c.latMin()).isLessThan(c.latMax());
            assertThat(c.lngMin()).isLessThan(c.lngMax());
            assertThat(c.latMin()).isBetween(6.0, 38.0);
            assertThat(c.lngMax()).isBetween(68.0, 98.0);
        }
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                City a = all.get(i);
                City b = all.get(j);
                boolean overlap = a.latMin() < b.latMax() && b.latMin() < a.latMax()
                        && a.lngMin() < b.lngMax() && b.lngMin() < a.lngMax();
                assertThat(overlap).as(a.code() + " and " + b.code()).isFalse();
            }
        }
    }

    @Test
    void theLaunchCities_keepTheirBoxes() {
        assertBox("CHENNAI", 12.75, 13.35, 79.95, 80.35);
        assertBox("BENGALURU", 12.75, 13.20, 77.35, 77.85);
        assertBox("PUDUCHERRY", 11.85, 12.05, 79.70, 79.90);
        assertThat(CityCatalog.cityOf(GeoCell.encode(13.0827, 80.2707, 7))).contains("CHENNAI");
        assertThat(CityCatalog.cityOf(GeoCell.encode(19.0760, 72.8777, 7))).contains("MUMBAI");
        assertThat(CityCatalog.cityOf(GeoCell.encode(9.9312, 76.2673, 7))).contains("KOCHI");
    }

    @Test
    void theMigrationSeeds_exactlyTheCatalog_plusOther() throws IOException {
        String sql = Files.readString(V28, StandardCharsets.UTF_8);
        List<City> seeded = new ArrayList<>();
        for (String line : sql.split("\\R")) {
            Matcher m = ROW.matcher(line);
            if (m.matches()) {
                seeded.add(new City(m.group(1), m.group(2), m.group(3), Double.parseDouble(m.group(4)),
                        Double.parseDouble(m.group(5)), Double.parseDouble(m.group(6)), Double.parseDouble(m.group(7)),
                        List.of()));
            }
        }
        List<City> catalog = CityCatalog.all();
        assertThat(seeded).hasSameSizeAs(catalog);
        for (int i = 0; i < catalog.size(); i++) {
            City a = catalog.get(i);
            City b = seeded.get(i);
            assertThat(b.code()).isEqualTo(a.code());
            assertThat(b.name()).isEqualTo(a.name());
            assertThat(b.state()).isEqualTo(a.state());
            assertThat(new double[] {b.latMin(), b.latMax(), b.lngMin(), b.lngMax()})
                    .containsExactly(a.latMin(), a.latMax(), a.lngMin(), a.lngMax());
            // DECIMAL(5,2): the file never has more than 2 decimals.
            for (double v : new double[] {a.latMin(), a.latMax(), a.lngMin(), a.lngMax()}) {
                assertThat(BigDecimal.valueOf(v).stripTrailingZeros().scale()).isLessThanOrEqualTo(2);
            }
        }
        assertThat(sql).contains("('OTHER', 'Another city', NULL, NULL, NULL, NULL, NULL)");
    }

    @Test
    void theAppBundlesTheSameFile() throws IOException {
        assertThat(Files.exists(APP_COPY)).as("the app's copy of the catalog").isTrue();
        assertThat(Files.readAllBytes(APP_COPY)).isEqualTo(Files.readAllBytes(FILE));
        try (InputStream in = Files.newInputStream(APP_COPY)) {
            assertThat(CityCatalog.parse(in)).hasSameSizeAs(CityCatalog.all());
        }
    }

    private static void assertBox(String code, double latMin, double latMax, double lngMin, double lngMax) {
        City city = CityCatalog.find(code).orElseThrow();
        assertThat(new double[] {city.latMin(), city.latMax(), city.lngMin(), city.lngMax()})
                .containsExactly(latMin, latMax, lngMin, lngMax);
    }
}
