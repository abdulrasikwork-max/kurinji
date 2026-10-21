package com.safesphere.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The H2 tests cannot see the original bug (H2 does no second conversion), so this test talks to the REAL MySQL.
 * It only runs when SAFESPHERE_MYSQL_TESTS=true (plus DB_PASSWORD, optional DB_USERNAME / DB_URL), and it only
 * uses a TEMPORARY table that disappears with the connection: no real data is touched.
 *
 * <pre>
 *   $env:SAFESPHERE_MYSQL_TESTS = "true"; $env:DB_PASSWORD = "..."; mvn -Dtest=MySqlTimestampStorageTest test
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "SAFESPHERE_MYSQL_TESTS", matches = "true")
class MySqlTimestampStorageTest {

    private static final LocalDateTime UTC_WALL_CLOCK = LocalDateTime.of(2026, 10, 19, 12, 0, 0, 123_456_000);
    private static final String EXPECTED = "2026-10-19 12:00:00.123456";

    private TimeZone originalZone;

    @BeforeEach
    void rememberZone() {
        originalZone = TimeZone.getDefault();
    }

    @AfterEach
    void restoreZone() {
        TimeZone.setDefault(originalZone);
    }

    @Test
    void withTheShippedConfiguration_aUtcValueIsStoredUnchanged_evenOnAnIstMachine() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata")); // a developer PC in India...
        UtcTimeZone.install();                                     // ...what main() does before Spring starts

        String[] stored = storeAndReadBack(shippedUrl());

        assertEquals(EXPECTED, stored[0], "raw DATETIME text");
        assertEquals(UTC_WALL_CLOCK, LocalDateTime.parse(stored[1]), "value read back through JDBC");
    }

    @Test
    void theOriginalConfiguration_onAnIstMachine_wasStoringFiveHoursThirtyMinutesBehind() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata")); // no UtcTimeZone.install(): the old behaviour
        String oldUrl = shippedUrl().replace("connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                "serverTimezone=UTC");

        String[] stored = storeAndReadBack(oldUrl);

        assertEquals("2026-10-19 06:30:00.123456", stored[0], "the documented bug: 5 h 30 m behind UTC");
        assertEquals(UTC_WALL_CLOCK, LocalDateTime.parse(stored[1]), "...yet it read back 'correctly', hiding the bug");
    }

    /** Returns {raw text of the column, value read back via Timestamp.toLocalDateTime()}. */
    private static String[] storeAndReadBack(String url) throws SQLException {
        String user = System.getenv().getOrDefault("DB_USERNAME", "safesphere");
        try (Connection connection = DriverManager.getConnection(url, user, System.getenv("DB_PASSWORD"))) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TEMPORARY TABLE tz_probe (id INT NOT NULL, v DATETIME(6) NOT NULL)");
            }
            // Timestamp.valueOf + setTimestamp is the call Hibernate makes for a LocalDateTime field.
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO tz_probe VALUES (1, ?)")) {
                insert.setTimestamp(1, Timestamp.valueOf(UTC_WALL_CLOCK));
                insert.executeUpdate();
            }
            try (Statement statement = connection.createStatement();
                 ResultSet row = statement.executeQuery("SELECT CAST(v AS CHAR), v FROM tz_probe WHERE id = 1")) {
                assertTrue(row.next());
                return new String[] {row.getString(1), row.getTimestamp(2).toLocalDateTime().toString()};
            }
        }
    }

    /** The URL exactly as src/main/resources/application.properties ships it (or DB_URL when set). */
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
