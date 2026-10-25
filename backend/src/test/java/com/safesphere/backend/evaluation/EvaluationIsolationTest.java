package com.safesphere.backend.evaluation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Phase 9B rules, enforced on the source (docs/PHASE9_DESIGN.md §9 PR1/PR6/PR9, §18, Appendix A), like
 * IntelligenceIsolationTest:
 *   - the evaluation package never reads safety features (SOS, Safe Walk, Safety Circle, evidence) or moderation of
 *     people, and none of those reads it;
 *   - nothing that decides what people see (intelligence, the community policies) reads a counter: measurement can
 *     never feed back into a picture, a confidence or a threshold by itself;
 *   - it schedules nothing, sends nothing and calls nothing outside the server;
 *   - V15 has no column that could hold a person, a place or an address, and its CHECKs keep cells and ids out.
 * (Until Phase 13E it also checked "no machine-learning runtime in the backend"; that rule now lives, unchanged for the
 * backend, in moderation/assessment/ModelPlacesTest.)
 */
class EvaluationIsolationTest {

    private static final Path MAIN = Path.of("src", "main", "java", "com", "safesphere", "backend");
    private static final Path V15 = Path.of("src", "main", "resources", "db", "migration",
            "V15__evaluation_counters.sql");

    private static final List<String> SAFETY = List.of("emergency", "emergencycontext", "escalation", "journey",
            "safetycontact", "circlealert", "evidence");
    private static final Pattern SAFETY_REFERENCE =
            Pattern.compile("com\\.safesphere\\.backend\\.(" + String.join("|", SAFETY) + ")\\b");
    private static final Pattern EVALUATION_REFERENCE = Pattern.compile("com\\.safesphere\\.backend\\.evaluation\\b");

    @Test
    void evaluation_neverReachesSafetyFeaturesOrModeration() throws IOException {
        List<String> violations = new ArrayList<>();
        scan(MAIN.resolve("evaluation"), SAFETY_REFERENCE, violations);
        scan(MAIN.resolve("evaluation"), Pattern.compile("com\\.safesphere\\.backend\\.moderation\\b"), violations);
        assertTrue(violations.isEmpty(), "evaluation must not reference: " + violations);
    }

    @Test
    void safetyFeaturesAndIntelligence_neverReadEvaluation() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String pkg : SAFETY) scan(MAIN.resolve(pkg), EVALUATION_REFERENCE, violations);
        scan(MAIN.resolve("intelligence"), EVALUATION_REFERENCE, violations);
        assertTrue(violations.isEmpty(), "must not depend on evaluation: " + violations);
    }

    /** Community and moderation may only REPORT events (OutcomeRecorder, CaseTrigger); the admin report is the reader. */
    @Test
    void outsideEvaluation_onlyTheRecorderIsUsed_andOnlyTheAdminPathReads() throws IOException {
        Pattern use = Pattern.compile("com\\.safesphere\\.backend\\.evaluation\\.([\\w.]+)");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")
                    && !p.startsWith(MAIN.resolve("evaluation"))).toList()) {
                Matcher m = use.matcher(Files.readString(file));
                while (m.find()) {
                    String what = m.group(1);
                    boolean reports = what.equals("service.OutcomeRecorder")
                            || what.equals("service.OutcomeRecorder.SignalEnd")
                            || what.equals("service.CaseTrigger") || what.equals("service.WeeklySnapshot");
                    boolean adminReport = (what.equals("service.EvaluationService")
                            || what.equals("dto.EvaluationDtos.Report"))
                            && (file.endsWith("ModerationService.java") || file.endsWith("AdminController.java"));
                    if (!reports && !adminReport) violations.add(file.getFileName() + " uses " + what);
                }
            }
        }
        assertTrue(violations.isEmpty(), "only the recorder outside evaluation, the report only for ADMIN: " + violations);
    }

    @Test
    void theCounterStore_isUsedOnlyInsideEvaluation_andTheRecorderNeverReads() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")
                    && !p.startsWith(MAIN.resolve("evaluation"))).toList()) {
                if (Files.readString(file).contains("EvaluationCounterRepository")) {
                    violations.add(file.getFileName().toString());
                }
            }
        }
        assertTrue(violations.isEmpty(), "the counter table is read and written only in evaluation: " + violations);
        String recorder = Files.readString(MAIN.resolve("evaluation").resolve("service").resolve("OutcomeRecorder.java"));
        for (String read : new String[] {"counters.find", "counters.exists", "counters.count", "counters.get"}) {
            assertFalse(recorder.contains(read), "the recorder only adds: " + read);
        }
    }

    @Test
    void evaluation_hasNoScheduledWork_andSendsNothing() throws IOException {
        List<String> violations = new ArrayList<>();
        scan(MAIN.resolve("evaluation"), Pattern.compile("@Scheduled|@Async|RestTemplate|WebClient|HttpClient|Socket"),
                violations);
        assertTrue(violations.isEmpty(), "evaluation must not schedule, push or call out: " + violations);
    }

    @Test
    void v15_hasNoPersonalColumn_andItsChecksKeepCellsAndIdsOut() throws IOException {
        String sql = Files.readString(V15);
        String table = sql.substring(sql.indexOf("CREATE TABLE evaluation_counters"), sql.indexOf(") ENGINE"));
        List<String> columns = new ArrayList<>();
        for (String line : table.split("\n")) {
            Matcher column = Pattern.compile("^\\s{4}([a-z_]+)\\s+(DATE|VARCHAR|INT)").matcher(line);
            if (column.find()) columns.add(column.group(1));
        }
        assertTrue(columns.equals(List.of("week_start", "metric", "dimension", "model_version", "occurrences")),
                "exactly the designed columns: " + columns);
        for (String column : columns) {
            assertFalse(column.toLowerCase(Locale.ROOT).matches(".*(user|cell|lat|lng|lon|ip|email|signal|case|time|"
                    + "account|name|text).*"), "no personal or place column: " + column);
        }
        assertTrue(table.contains("REGEXP_LIKE(dimension, '^(-|[A-Z_]+([.][A-Z_]+)?)$', 'c')"),
                "a dimension is upper case only (no geohash, no digits)");
        assertTrue(table.contains("REGEXP_LIKE(model_version, '^(-|[a-z]+-[0-9]+([.][0-9]+)?)$', 'c')"));
        assertTrue(table.contains("DAYOFWEEK(week_start) = 2"), "weeks only");
        assertTrue(table.contains("occurrences >= 0"));
    }

    // Phase 13E (D2): the "no machine-learning runtime in the backend" check moved to ModelPlacesTest, which pins exactly
    // where a model may run (the Python moderation service; the plain-Kotlin linear model on the phone).

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
