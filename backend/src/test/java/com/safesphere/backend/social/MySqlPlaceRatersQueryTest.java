package com.safesphere.backend.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.safesphere.backend.social.entity.Connection;
import com.safesphere.backend.social.repository.ConnectionRepository;
import com.safesphere.backend.social.repository.PlaceVisitRepository;
import com.safesphere.backend.social.repository.PlaceVisitRepository.RaterRow;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 18A-1: the two queries behind the guarded place average, run on the REAL MySQL. The H2 tests cannot show whether
 * MySQL accepts the SQL that Hibernate writes for them (a counting subquery in the select list, two IN lists), so this
 * test runs both through the repositories and compares every row with the same question asked in plain SQL.
 *
 * <p>It only READS (a read-only transaction, no schema change, no Flyway) and it only runs when
 * SAFESPHERE_MYSQL_TESTS=true (plus DB_PASSWORD, optional DB_USERNAME / DB_URL). Point it at the development database,
 * never at the pilot.
 *
 * <pre>
 *   $env:SAFESPHERE_MYSQL_TESTS = "true"; $env:DB_PASSWORD = "..."; mvn -Dtest=MySqlPlaceRatersQueryTest test
 * </pre>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(readOnly = true)
@EnabledIfEnvironmentVariable(named = "SAFESPHERE_MYSQL_TESTS", matches = "true")
class MySqlPlaceRatersQueryTest {

    /** Later than any account, so the probation cut-off leaves every rater in and the rows can be compared in full. */
    private static final LocalDateTime FAR_CUTOFF = LocalDateTime.of(2999, 1, 1, 0, 0);

    @Autowired
    private PlaceVisitRepository visits;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private JdbcTemplate jdbc;

    private static java.util.TimeZone originalZone;

    @org.junit.jupiter.api.AfterAll
    static void restoreZone() {
        if (originalZone != null) {
            java.util.TimeZone.setDefault(originalZone);
        }
    }

    @DynamicPropertySource
    static void realMySql(DynamicPropertyRegistry registry) throws IOException {
        originalZone = java.util.TimeZone.getDefault();
        com.safesphere.backend.config.UtcTimeZone.install(); // what main() does before Spring starts (surefire runs in Asia/Kolkata)
        String url = shippedUrl();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("DB_USERNAME", "safesphere"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Test
    void raters_onMySql_givesTheSameRowsAsPlainSql() {
        assertTrue(jdbc.queryForObject("SELECT VERSION()", String.class).startsWith("8."), "this must be MySQL 8");

        List<Long> placeIds = jdbc.queryForList(
                "SELECT DISTINCT place_id FROM place_visits WHERE rating IS NOT NULL ORDER BY place_id", Long.class);
        // With no rating in the database the query still has to parse and run on MySQL.
        visits.raters(placeIds.isEmpty() ? -1L : placeIds.get(0), FAR_CUTOFF);

        for (long placeId : placeIds) {
            Set<String> viaRepository = visits.raters(placeId, FAR_CUTOFF).stream()
                    .map(MySqlPlaceRatersQueryTest::text)
                    .collect(Collectors.toCollection(TreeSet::new));
            Set<String> viaSql = new TreeSet<>();
            for (Map<String, Object> row : jdbc.queryForList(
                    "SELECT v.user_id, v.rating, u.created_at,"
                            + " (SELECT COUNT(*) FROM place_visits o WHERE o.user_id = v.user_id AND o.rating IS NOT NULL) AS rated"
                            + " FROM place_visits v JOIN users u ON u.id = v.user_id"
                            + " WHERE v.place_id = ? AND v.rating IS NOT NULL AND u.enabled = 1 AND u.erased_at IS NULL",
                    placeId)) {
                viaSql.add(row.get("user_id") + "|" + row.get("rating") + "|"
                        + ((LocalDateTime) row.get("created_at")).toEpochSecond(ZoneOffset.UTC) + "|" + row.get("rated"));
            }
            assertEquals(viaSql, viaRepository, "raters of place " + placeId);
        }

        // The probation cut-off: nobody's account is older than the year 1970.
        if (!placeIds.isEmpty()) {
            assertTrue(visits.raters(placeIds.get(0), LocalDateTime.of(1970, 1, 1, 0, 0)).isEmpty());
        }
    }

    @Test
    void acceptedAmong_onMySql_givesTheSameLinksAsPlainSql() {
        List<Long> everyone = new ArrayList<>(jdbc.queryForList(
                "SELECT user_low FROM connections UNION SELECT user_high FROM connections", Long.class));
        if (everyone.isEmpty()) {
            everyone.add(-1L);
        }
        Set<Long> viaRepository = connections.acceptedAmong(everyone).stream()
                .map(Connection::getId)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<Long> viaSql = new TreeSet<>(
                jdbc.queryForList("SELECT id FROM connections WHERE status = 'ACCEPTED'", Long.class));
        assertEquals(viaSql, viaRepository);

        // One person alone is never a pair.
        assertTrue(connections.acceptedAmong(List.of(everyone.get(0))).isEmpty());
    }

    private static String text(RaterRow row) {
        return row.userId() + "|" + row.rating() + "|" + row.accountCreatedAt().toEpochSecond(ZoneOffset.UTC) + "|"
                + row.placesRated();
    }

    private static String shippedUrl() throws IOException {
        String fromEnvironment = System.getenv("DB_URL");
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment;
        }
        String properties = Files.readString(Path.of("src", "main", "resources", "application.properties"));
        Matcher matcher = Pattern.compile("spring\\.datasource\\.url=\\$\\{DB_URL:([^}]+)}").matcher(properties);
        assertTrue(matcher.find(), "default datasource URL not found in application.properties");
        return matcher.group(1);
    }
}
