package com.safesphere.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the "all stored time is UTC" convention (docs/TIMESTAMPS.md) so a careless line cannot bring the
 * 5 h 30 m shift back: no code may ask for "now in the machine's zone".
 */
class TimeConventionsTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");
    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("LocalDateTime\\.now\\(\\s*\\)"),
            Pattern.compile("LocalDate\\.now\\(\\s*\\)"),
            Pattern.compile("LocalTime\\.now\\(\\s*\\)"),
            Pattern.compile("ZonedDateTime\\.now\\(\\s*\\)"),
            Pattern.compile("OffsetDateTime\\.now\\(\\s*\\)"),
            Pattern.compile("Clock\\.systemDefaultZone"),
            Pattern.compile("ZoneId\\.systemDefault"),
            Pattern.compile("TimeZone\\.getDefault"),
            Pattern.compile("new\\s+java\\.util\\.Date\\(|new\\s+Date\\(\\s*\\)"),
            Pattern.compile("Timestamp\\.valueOf"),
            Pattern.compile("Calendar\\.getInstance\\(\\s*\\)"));

    @Test
    void productionCodeNeverUsesTheMachinesTimeZone() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String code = stripLineComment(lines.get(i));
                    for (Pattern pattern : FORBIDDEN) {
                        if (pattern.matcher(code).find()) {
                            violations.add(file + ":" + (i + 1) + " uses " + pattern.pattern());
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "Machine-zone time found (use ZoneOffset.UTC or the Clock bean): " + violations);
    }

    @Test
    void everyLocalDateTimeNowNamesUtcExplicitly() throws IOException {
        Pattern nowCall = Pattern.compile("LocalDateTime\\.now\\(([^)]*)\\)");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    var matcher = nowCall.matcher(stripLineComment(lines.get(i)));
                    while (matcher.find()) {
                        String argument = matcher.group(1).trim();
                        if (!argument.equals("ZoneOffset.UTC") && !argument.equals("clock")) {
                            violations.add(file + ":" + (i + 1) + " LocalDateTime.now(" + argument + ")");
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "LocalDateTime.now must take ZoneOffset.UTC or the Clock bean: " + violations);
    }

    @Test
    void databaseColumnsAreDatetimeNeverTimestamp() throws IOException {
        // MySQL TIMESTAMP columns are converted using the session time zone; DATETIME(6) holding UTC is our rule.
        Pattern bareDatetime = Pattern.compile("\\bDATETIME\\b(?!\\(6\\))", Pattern.CASE_INSENSITIVE);
        Pattern timestampType = Pattern.compile("^\\s*\\w+\\s+TIMESTAMP\\b", Pattern.CASE_INSENSITIVE);
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".sql")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String code = lines.get(i).replaceAll("--.*$", "");
                    if (timestampType.matcher(code).find() || bareDatetime.matcher(code).find()) {
                        violations.add(file.getFileName() + ":" + (i + 1) + " " + code.trim());
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "Use DATETIME(6) (UTC) for time columns: " + violations);
    }

    @Test
    void testsRunInANonUtcZoneSoZoneAssumptionsAreCaught() {
        // Configured in pom.xml (surefire argLine). If this fails, the zone-sensitivity of the suite was lost.
        assertEquals("Asia/Kolkata", TimeZone.getDefault().getID());
    }

    @Test
    void installUtcMakesUtcTheDefaultZoneAndRestoresCleanly() {
        TimeZone before = TimeZone.getDefault();
        try {
            UtcTimeZone.install();
            assertEquals("UTC", TimeZone.getDefault().getID());
        } finally {
            TimeZone.setDefault(before);
        }
    }

    private static String stripLineComment(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
            return "";
        }
        int comment = line.indexOf("//");
        return comment < 0 ? line : line.substring(0, comment);
    }
}
