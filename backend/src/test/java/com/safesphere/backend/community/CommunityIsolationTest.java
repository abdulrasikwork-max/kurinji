package com.safesphere.backend.community;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Phase N3 invariants, enforced on the source: community membership (verification, bans, graduation) can never reach a
 * safety feature, and the community package never reads safety data. The social features know membership only through
 * their own small port (MembershipGate), never by importing the community package. Nothing in the community package
 * calls out, and its only scheduled work is the upkeep job (it only deletes and ends).
 */
class CommunityIsolationTest {

    private static final Path MAIN = Path.of("src", "main", "java", "com", "safesphere", "backend");

    private static final List<String> SAFETY = List.of("emergency", "emergencycontext", "escalation", "journey",
            "safetycontact", "circlealert");

    private static final Pattern SAFETY_REFERENCE =
            Pattern.compile("com\\.safesphere\\.backend\\.(" + String.join("|", SAFETY) + ")\\b");
    private static final Pattern COMMUNITY_REFERENCE = Pattern.compile("com\\.safesphere\\.backend\\.community\\b");

    @Test
    void safetyFeatures_neverDependOnCommunity() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String pkg : SAFETY) scan(MAIN.resolve(pkg), COMMUNITY_REFERENCE, violations);
        assertTrue(violations.isEmpty(), "safety features must not depend on community: " + violations);
    }

    @Test
    void community_neverReadsSafetyData() throws IOException {
        List<String> violations = new ArrayList<>();
        scan(MAIN.resolve("community"), SAFETY_REFERENCE, violations);
        assertTrue(violations.isEmpty(), "community must not reference safety features: " + violations);
    }

    @Test
    void social_knowsMembershipOnlyThroughItsPort() throws IOException {
        List<String> violations = new ArrayList<>();
        scan(MAIN.resolve("social"), COMMUNITY_REFERENCE, violations);
        assertTrue(violations.isEmpty(), "social must not import the community package: " + violations);
    }

    @Test
    void community_callsNothingOutside_andSchedulesOnlyItsUpkeep() throws IOException {
        List<String> violations = new ArrayList<>();
        scan(MAIN.resolve("community"), Pattern.compile("@Async|RestTemplate|WebClient|HttpClient|OtpSender"),
                violations);
        assertTrue(violations.isEmpty(), "community must not call out: " + violations);
        List<String> scheduled = new ArrayList<>();
        scan(MAIN.resolve("community"), Pattern.compile("@Scheduled"), scheduled);
        assertTrue(scheduled.size() <= 1, "at most one @Scheduled in community: " + scheduled);
        scheduled.forEach(s -> assertTrue(s.startsWith("CommunityUpkeepJob.java:"), "only CommunityUpkeepJob: " + s));
    }

    @Test
    void noPlainRollNumberColumn_existsInTheMigration() throws IOException {
        String v34 = Files.readString(MAIN.getParent().getParent().getParent().getParent()
                .resolve(Path.of("resources", "db", "migration", "V34__community_membership.sql")));
        String code = v34.replaceAll("(?m)--.*$", "");
        assertEquals(-1, code.indexOf("roll_number"), "V34 must not have a plain roll number column");
        assertTrue(code.contains("roll_key"), "the keyed form is stored");
    }

    private static void scan(Path root, Pattern forbidden, List<String> violations) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (forbidden.matcher(lines.get(i)).find()) violations.add(file.getFileName() + ":" + (i + 1));
                }
            }
        }
    }
}
