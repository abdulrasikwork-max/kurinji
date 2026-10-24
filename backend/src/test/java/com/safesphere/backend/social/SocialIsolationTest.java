package com.safesphere.backend.social;

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
 * Phase 13A rule, enforced on the source (EXPERIENCE_PLATFORM_DESIGN.md §4.8, §5): the social features never read
 * safety data (emergencies, journeys, escalations, the Safety Circle, circle alerts, the context packet, evidence), and
 * the safety features never depend on the social features. Connections are never trusted people. A source scan also
 * catches fully qualified names, not only imports (the IntelligenceIsolationTest method).
 */
class SocialIsolationTest {

    private static final Path MAIN = Path.of("src", "main", "java", "com", "safesphere", "backend");

    private static final List<String> SAFETY = List.of("emergency", "emergencycontext", "escalation", "journey",
            "safetycontact", "circlealert", "evidence");

    private static final Pattern SAFETY_REFERENCE =
            Pattern.compile("com\\.safesphere\\.backend\\.(" + String.join("|", SAFETY) + ")\\b");
    private static final Pattern SOCIAL_REFERENCE = Pattern.compile("com\\.safesphere\\.backend\\.social\\b");

    @Test
    void social_neverReadsSafetyData() throws IOException {
        List<String> violations = new ArrayList<>();
        scan(MAIN.resolve("social"), SAFETY_REFERENCE, violations);
        assertTrue(violations.isEmpty(), "social must not reference safety features: " + violations);
    }

    @Test
    void safetyFeatures_neverDependOnSocial() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String pkg : SAFETY) scan(MAIN.resolve(pkg), SOCIAL_REFERENCE, violations);
        assertTrue(violations.isEmpty(), "safety features must not depend on social: " + violations);
    }

    /**
     * Phase 13D (M2): the content side never depends on moderation. Moderation reads and writes social (state columns,
     * pauses); social only reads its own columns, so posts and people work without the moderation code.
     */
    @Test
    void social_neverReferencesModeration() throws IOException {
        List<String> violations = new ArrayList<>();
        scan(MAIN.resolve("social"), Pattern.compile("com\\.safesphere\\.backend\\.moderation\\b"), violations);
        assertTrue(violations.isEmpty(), "social must not reference moderation: " + violations);
    }

    /** Phase 13D: a social pause can never reach a safety feature (they never read social, nor moderation). */
    @Test
    void safetyFeatures_neverReadModeration() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String pkg : SAFETY) {
            scan(MAIN.resolve(pkg), Pattern.compile("com\\.safesphere\\.backend\\.moderation\\b"), violations);
        }
        assertTrue(violations.isEmpty(), "safety features must not depend on moderation: " + violations);
    }

    @Test
    void social_callsNothingOutside() throws IOException {
        List<String> violations = new ArrayList<>();
        scan(MAIN.resolve("social"), Pattern.compile("@Async|RestTemplate|WebClient|HttpClient"), violations);
        assertTrue(violations.isEmpty(), "social must not call out: " + violations);
    }

    /** Phase 13B (Q10): exactly one scheduled task, the photo clean-up (it only deletes). */
    @Test
    void social_onlyScheduledWork_isThePhotoCleanUp() throws IOException {
        List<String> found = new ArrayList<>();
        scan(MAIN.resolve("social"), Pattern.compile("@Scheduled"), found);
        assertEquals(1, found.size(), "one @Scheduled in social: " + found);
        assertTrue(found.get(0).startsWith("SocialMediaCleanupJob.java:"), "only SocialMediaCleanupJob: " + found);
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
