package com.safesphere.backend.moderation.assessment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.config.ModerationAiProperties;
import com.safesphere.backend.moderation.assessment.Evidence.Answer;
import com.safesphere.backend.moderation.assessment.Evidence.Category;
import com.safesphere.backend.moderation.assessment.Evidence.Item;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 13E (E3, E4, E7): the ONLY place in the backend that talks to the moderation service (AssessmentIsolationTest).
 * It sends {kind, text} and nothing else (no person, no post id, no place), with the shared key, and waits at most
 * app.moderation.ai.timeout. Anything unexpected is "unavailable" (empty): a non-200 answer, a timeout, malformed JSON,
 * an unknown version. Unknown categories, impossible severities or offsets are dropped (fail closed: never more
 * evidence than was really sent). It never logs a text or the key; a failure is logged with the exception class only.
 */
@Component
public class ModerationServiceClient {

    static final Pattern VERSION = Pattern.compile("^[a-z]+-[0-9]+([.][0-9]+)?$");
    static final int MAX_ITEMS = 20;

    private static final Logger log = LoggerFactory.getLogger(ModerationServiceClient.class);

    private final ModerationAiProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;

    public ModerationServiceClient(ModerationAiProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    }

    /** The evidence for [text], or empty when the service could not answer properly in time. */
    public Optional<Answer> assess(String kind, String text) {
        if (!properties.active()) return Optional.empty();
        try {
            byte[] body = json.writeValueAsBytes(Map.of("kind", kind, "text", text));
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.url() + "/v1/assess"))
                    .timeout(properties.timeout())
                    .header("Content-Type", "application/json")
                    .header("X-Moderation-Key", properties.key())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.warn("Moderation service answered {}; the content is handled as unchecked", response.statusCode());
                return Optional.empty();
            }
            return parse(json.readTree(new String(response.body(), StandardCharsets.UTF_8)),
                    text.codePointCount(0, text.length()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            log.warn("Moderation service unavailable ({}); the content is handled as unchecked",
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Fail-closed reading of one answer; [length] = the text's length in code points. */
    static Optional<Answer> parse(JsonNode root, int length) {
        if (root == null || !root.isObject()) return Optional.empty();
        JsonNode versions = root.path("versions");
        String normaliser = versions.path("normaliser").asText("");
        String lexicon = versions.path("lexicon").asText("");
        String model = versions.path("model").asText("-");
        if (!VERSION.matcher(normaliser).matches() || !VERSION.matcher(lexicon).matches()
                || !(model.equals("-") || VERSION.matcher(model).matches())) {
            return Optional.empty();
        }
        List<Item> items = new ArrayList<>();
        for (JsonNode node : root.path("evidence")) {
            if (items.size() >= MAX_ITEMS) break;
            Category category = category(node.path("category").asText(""));
            JsonNode severity = node.path("severity");
            JsonNode start = node.path("start");
            JsonNode end = node.path("end");
            if (category == null || !severity.isInt() || !start.isInt() || !end.isInt()) continue;
            int s = start.asInt();
            int e = end.asInt();
            if (severity.asInt() < 0 || severity.asInt() > 3 || s < 0 || e <= s || e > length) continue;
            items.add(new Item(category, severity.asInt(), node.path("targeted").asBoolean(false),
                    node.path("quoted").asBoolean(false), s, e));
        }
        Map<Category, Double> scores = new EnumMap<>(Category.class);
        root.path("modelScores").fields().forEachRemaining(entry -> {
            Category category = category(entry.getKey());
            double value = entry.getValue().asDouble(-1);
            if (category != null && entry.getValue().isNumber() && value >= 0 && value <= 1) scores.put(category, value);
        });
        return Optional.of(new Answer(normaliser, lexicon, model, List.copyOf(items),
                root.path("strongLanguage").asBoolean(false), root.path("selfHarm").asBoolean(false), scores));
    }

    private static Category category(String name) {
        try {
            return Category.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
