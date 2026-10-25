package com.safesphere.backend.moderation.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Phase 13E, decision D2 (EXPERIENCE_PLATFORM_DESIGN.md §6.1, §11.8): learned models are allowed for content moderation
 * only, and only in two places: the Python moderation service and a plain-Kotlin linear model on the phone. This test
 * replaces the "no ML runtime" check of EvaluationIsolationTest (and the Android NoMlRuntimeTest) by pinning exactly
 * those places:
 *   - the backend has no ML library and no model or lexicon file: it only ASKS the service (one client) and DECIDES
 *     with a pure policy;
 *   - only ModerationServiceClient talks to the service, and nothing else in the moderation code calls out;
 *   - the service uses the Python standard library only (no third-party model runtime or AI API), and ships no model
 *     but the toy parity model of its tests;
 *   - what is stored never holds a self-harm signal or text.
 */
class ModelPlacesTest {

    static final Path MAIN = Path.of("src", "main");
    static final Path SERVICE = Path.of("..", "moderation-service");
    static final List<String> RUNTIMES = List.of("tensorflow", "onnx", "djl", "tribuo", "smile", "weka", "pytorch",
            "deeplearning4j", "xgboost", "lightgbm", "openai", "anthropic", "langchain", "huggingface");
    static final Set<String> MODEL_FILES = Set.of("onnx", "pt", "pth", "tflite", "pb", "gguf", "safetensors", "bin",
            "lin", "keys", "joblib", "pkl");

    @Test
    void theBackend_hasNoMlLibrary_andNoModelOrLexiconFile() throws IOException {
        String pom = Files.readString(Path.of("pom.xml")).toLowerCase(Locale.ROOT);
        for (String runtime : RUNTIMES) assertThat(pom).as("no ML runtime or AI API: " + runtime).doesNotContain(runtime);
        try (Stream<Path> files = Files.walk(MAIN)) {
            List<String> models = files.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                    .filter(n -> MODEL_FILES.contains(n.substring(n.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)))
                    .toList();
            assertThat(models).as("no model or lexicon in the backend").isEmpty();
        }
    }

    @Test
    void onlyTheClient_talksToTheModerationService() throws IOException {
        Pattern outbound = Pattern.compile("java\\.net\\.http|HttpClient|RestTemplate|WebClient|\"/v1/assess");
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN.resolve("java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (outbound.matcher(Files.readString(file)).find()) found.add(file.getFileName().toString());
            }
        }
        assertThat(found).containsExactly("ModerationServiceClient.java");
    }

    @Test
    void theClient_neverLogsTheTextOrTheKey() throws IOException {
        String client = Files.readString(MAIN.resolve(Path.of("java", "com", "safesphere", "backend", "moderation",
                "assessment", "ModerationServiceClient.java")));
        Matcher logs = Pattern.compile("log\\.(warn|info|debug|error|trace)\\(([^;]*);").matcher(client);
        while (logs.find()) {
            assertThat(logs.group(2)).doesNotContain("text").doesNotContain("key()").doesNotContain("body");
        }
    }

    @Test
    void theService_usesOnlyThePythonStandardLibrary() throws IOException {
        Set<String> stdlib = Set.of("argparse", "hashlib", "hmac", "http", "json", "math", "os", "random", "re", "sys",
                "threading", "time", "unicodedata", "io", "unittest", "contextlib", "shutil", "statistics");
        Pattern imports = Pattern.compile("^\\s*(?:from\\s+([\\w.]+)\\s+import|import\\s+([\\w.]+))", Pattern.MULTILINE);
        List<String> outside = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SERVICE)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".py")).toList()) {
                Matcher m = imports.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    String module = (m.group(1) != null ? m.group(1) : m.group(2)).split("\\.")[0];
                    boolean own = module.isEmpty() || module.equals("kurinji_moderation") || module.startsWith("test_")
                            || module.equals("make_vectors");
                    if (!own && !stdlib.contains(module)) outside.add(file.getFileName() + ": " + module);
                }
            }
        }
        assertThat(outside).as("standard library only").isEmpty();
    }

    @Test
    void theService_shipsNoModel_butTheToyParityModel() throws IOException {
        List<String> models = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SERVICE)) {
            files.filter(Files::isRegularFile).map(p -> SERVICE.relativize(p).toString().replace('\\', '/'))
                    .filter(n -> n.endsWith(".lin") || n.endsWith(".onnx") || n.endsWith(".pt") || n.endsWith(".bin"))
                    .forEach(models::add);
        }
        assertThat(models).containsExactly("data/vectors/linear-test-model.lin");
    }

    @Test
    void nothingStored_canHoldSelfHarmOrText() throws IOException {
        String entity = Files.readString(MAIN.resolve(Path.of("java", "com", "safesphere", "backend", "moderation",
                "assessment", "ContentAssessment.java")));
        assertThat(entity.toLowerCase(Locale.ROOT)).doesNotContain("selfharm").doesNotContain("self_harm")
                .doesNotContain("private string text").doesNotContain("private string body");
        String v24 = Files.readString(MAIN.resolve(Path.of("resources", "db", "migration", "V24__ai_moderation.sql")));
        String table = v24.substring(v24.indexOf("CREATE TABLE content_assessments"), v24.indexOf(") ENGINE"));
        assertThat(table).doesNotContain("SELF_HARM").doesNotContain(" TEXT ").doesNotContain(" body ");
    }
}
