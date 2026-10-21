package com.safesphere.backend.intelligence;

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
 * Phase 8 safety rule, enforced on the source (docs/PHASE8_DESIGN.md §10, §18): Safety Intelligence is decision
 * support. It has NO code path to emergencies, Safe Walk, escalation, the Safety Circle, circle alerts, the emergency
 * context packet, evidence or moderation of people, and none of those reads it. Community data is not correlated with
 * them on the server. A source scan (like TimeConventionsTest) also catches fully qualified names, not only imports.
 */
class IntelligenceIsolationTest {

    private static final Path MAIN = Path.of("src", "main", "java", "com", "safesphere", "backend");

    /** Packages that hold safety features or personal safety data. */
    private static final List<String> SAFETY = List.of("emergency", "emergencycontext", "escalation", "journey",
            "safetycontact", "circlealert", "evidence");

    private static final Pattern SAFETY_REFERENCE =
            Pattern.compile("com\\.safesphere\\.backend\\.(" + String.join("|", SAFETY) + ")\\b");
    private static final Pattern MODERATION_REFERENCE = Pattern.compile("com\\.safesphere\\.backend\\.moderation\\b");
    private static final Pattern INTELLIGENCE_REFERENCE = Pattern.compile("com\\.safesphere\\.backend\\.intelligence\\b");

    @Test
    void intelligence_neverReachesSafetyFeaturesOrModerationOfPeople() throws IOException {
        List<String> violations = new ArrayList<>();
        scan(MAIN.resolve("intelligence"), SAFETY_REFERENCE, violations);
        scan(MAIN.resolve("intelligence"), MODERATION_REFERENCE, violations);
        assertTrue(violations.isEmpty(), "intelligence must not reference: " + violations);
    }

    @Test
    void safetyFeatures_neverReadIntelligence() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String pkg : SAFETY) scan(MAIN.resolve(pkg), INTELLIGENCE_REFERENCE, violations);
        assertTrue(violations.isEmpty(), "safety features must not depend on intelligence: " + violations);
    }

    @Test
    void community_staysUncorrelatedWithSafetyFeatures() throws IOException {
        List<String> violations = new ArrayList<>();
        scan(MAIN.resolve("community"), SAFETY_REFERENCE, violations);
        assertTrue(violations.isEmpty(), "community must not reference safety features: " + violations);
    }

    @Test
    void intelligence_hasNoScheduledWork_andSendsNothing() throws IOException {
        List<String> violations = new ArrayList<>();
        scan(MAIN.resolve("intelligence"), Pattern.compile("@Scheduled|@Async|RestTemplate|WebClient|HttpClient"),
                violations);
        assertTrue(violations.isEmpty(), "intelligence must not schedule, push or call out: " + violations);
    }

    /** Phase 9D: the rules-2 shadow comparison reads and counts; it cannot write, hide, or open anything. */
    @Test
    void theRulesTwoShadow_onlyReadsAndCounts() throws IOException {
        List<String> violations = new ArrayList<>();
        Path shadow = MAIN.resolve("intelligence").resolve("service").resolve("AreaModelShadow.java");
        assertTrue(Files.exists(shadow));
        scan(shadow, Pattern.compile("\\.save|\\.delete|\\.flush|Repository|@Modifying|quarantine|ModerationCase"
                + "|readOnly = false"), violations);
        assertTrue(violations.isEmpty(), "the shadow must only read and count: " + violations);
        String source = Files.readString(shadow);
        assertTrue(source.contains("@Transactional(readOnly = true)"), "its database access is read-only");
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
