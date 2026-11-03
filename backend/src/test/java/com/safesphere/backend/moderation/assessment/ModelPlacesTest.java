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

    /**
     * Every outbound HTTP call of the backend is pinned here. Phase 17B added exactly one more: the SMS provider
     * adapter (it delivers verification codes; it never talks to the moderation service and never logs). Phase 17G-3 added
     * the third and last: the optional Explore understanding client. It talks ONLY to our own internal service (the same
     * Python process as the moderation check), never to a provider; the provider key and address exist only in
     * moderation-service/kurinji_moderation/understand.py (see onlyUnderstandPy_namesTheProvider...).
     */
    @Test
    void onlyThePinnedClients_makeOutboundCalls() throws IOException {
        Pattern outbound = Pattern.compile("java\\.net\\.http|HttpClient|RestTemplate|WebClient|\"/v1/assess|\"/v1/understand");
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN.resolve("java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (outbound.matcher(Files.readString(file)).find()) found.add(file.getFileName().toString());
            }
        }
        assertThat(found).containsExactlyInAnyOrder("ModerationServiceClient.java", "Fast2SmsOtpSender.java",
                "UnderstandingClient.java");
        String sms = Files.readString(MAIN.resolve(Path.of("java", "com", "safesphere", "backend", "auth", "phone",
                "Fast2SmsOtpSender.java")));
        assertThat(sms).doesNotContain("/v1/assess").doesNotContain("/v1/understand").doesNotContain("Logger")
                .doesNotContain("log.");
        String moderation = Files.readString(MAIN.resolve(Path.of("java", "com", "safesphere", "backend", "moderation",
                "assessment", "ModerationServiceClient.java")));
        String understanding = Files.readString(MAIN.resolve(Path.of("java", "com", "safesphere", "backend", "explore",
                "UnderstandingClient.java")));
        assertThat(moderation).as("each client calls only its own route").doesNotContain("/v1/understand");
        assertThat(understanding).as("each client calls only its own route").doesNotContain("/v1/assess");
    }

    @Test
    void theClients_neverLogTheTextOrTheKey() throws IOException {
        for (Path file : List.of(
                MAIN.resolve(Path.of("java", "com", "safesphere", "backend", "moderation", "assessment",
                        "ModerationServiceClient.java")),
                MAIN.resolve(Path.of("java", "com", "safesphere", "backend", "explore", "UnderstandingClient.java")))) {
            String client = Files.readString(file);
            Matcher logs = Pattern.compile("log\\.(warn|info|debug|error|trace)\\(([^;]*);").matcher(client);
            int seen = 0;
            while (logs.find()) {
                seen++;
                assertThat(logs.group(2)).as(file.getFileName().toString()).doesNotContain("text").doesNotContain("key()")
                        .doesNotContain("body");
            }
            assertThat(seen).as("the client does log its failures, by kind").isPositive();
        }
    }

    /**
     * Phase 17G-3 (decision D-2): the provider's address, header and key belong to ONE file, the Python service's
     * understand.py. Neither the Java backend nor the app may name the provider or hold its key; no other Python file may open
     * a network connection of its own.
     */
    @Test
    void onlyUnderstandPy_namesTheProviderAndItsKey() throws IOException {
        Pattern provider = Pattern.compile("api\\.anthropic\\.com|api\\.groq\\.com|x-api-key|IRL_UNDERSTANDING_API_KEY|ANTHROPIC_API_KEY|GROQ_API_KEY|gsk_",
                Pattern.CASE_INSENSITIVE);
        List<String> pythonHits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SERVICE)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".py")).toList()) {
                String name = file.getFileName().toString();
                if (name.equals("test_understand.py")) continue;   // the stub-based tests name the header they check
                if (provider.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) pythonHits.add(name);
            }
        }
        // understand.py is the only caller of a provider and the only reader of the key variables (from_environment); the
        // smoke tool (run by the key's owner) names the variable in its messages only. server.py names nothing.
        assertThat(pythonHits).containsExactlyInAnyOrder("understand.py", "smoke_understand.py");
        String server = Files.readString(SERVICE.resolve(Path.of("kurinji_moderation", "server.py")), StandardCharsets.UTF_8);
        assertThat(server).doesNotContain("api.anthropic.com").doesNotContain("api.groq.com").doesNotContain("x-api-key")
                .doesNotContain("urllib").doesNotContain("API_KEY");

        List<String> elsewhere = new ArrayList<>();
        for (Path root : List.of(MAIN, Path.of("..", "android", "app", "src", "main"))) {
            if (!Files.isDirectory(root)) continue;   // the app folder is absent when only the backend is checked out
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).filter(ModelPlacesTest::isSourceOrConfig).toList()) {
                    if (provider.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
                        elsewhere.add(file.toString().replace('\\', '/'));
                    }
                }
            }
        }
        assertThat(elsewhere).as("the backend and the app never name the provider or hold its key").isEmpty();
    }

    /** Source and configuration text files (binary resources such as images and the hashed lexicon are skipped). */
    private static boolean isSourceOrConfig(Path file) {
        String name = file.toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".xml") || name.endsWith(".properties")
                || name.endsWith(".tsv") || name.endsWith(".sql") || name.endsWith(".json");
    }

    @Test
    void understandPy_isTheOnlyPythonFileThatOpensConnections() throws IOException {
        List<String> networked = new ArrayList<>();
        Pattern net = Pattern.compile("^\\s*(?:from|import)\\s+(urllib|ssl|socket)\\b", Pattern.MULTILINE);
        try (Stream<Path> files = Files.walk(SERVICE)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".py")).toList()) {
                String name = file.getFileName().toString();
                // tests (and tools/benchmark.py) start and call their own local server on 127.0.0.1 with http.client: not a reach-out
                if (name.startsWith("test_")) continue;
                if (net.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) networked.add(name);
            }
        }
        assertThat(networked).containsExactly("understand.py");
    }

    @Test
    void theService_usesOnlyThePythonStandardLibrary() throws IOException {
        // 17G-3 added urllib and ssl for understand.py (the optional Explore understanding); no other file may import them
        // (understandPy_isTheOnlyPythonFileThatOpensConnections).
        Set<String> stdlib = Set.of("argparse", "hashlib", "hmac", "http", "json", "math", "os", "random", "re", "sys",
                "threading", "time", "unicodedata", "io", "unittest", "contextlib", "shutil", "statistics", "urllib", "ssl");
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
