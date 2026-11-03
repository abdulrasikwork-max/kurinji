package com.safesphere.backend.explore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.config.ExploreUnderstandingProperties;
import com.safesphere.backend.social.entity.PostEnums.Daypart;
import com.safesphere.backend.social.policy.ExploreFacets;
import com.safesphere.backend.social.policy.ProfileLists.Interest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 17G-3: the ONLY place in the backend that asks for the optional understanding of a typed Explore request
 * (UnderstandingIsolationTest). It talks to our own internal service, never to a provider: the provider key lives only in
 * that service's environment. It sends {text} and nothing else (no person, no place, no post), with the shared internal
 * key, and waits at most app.explore.understanding.timeout. Whatever comes back is read FAIL-CLOSED against the closed lists
 * (categories, tags, day parts): unknown entries are dropped, extras are capped, anything unexpected is "unavailable". It
 * never logs the text or the key; a failure is logged with its kind only.
 */
@Component
public class UnderstandingClient {

    /** What a call came to. */
    public sealed interface Outcome permits Understood, Off, Unavailable {
    }

    /** The chips. [daypart] is null when none was understood. Lists hold closed-list codes only. */
    public record Understood(List<String> categories, List<String> facets, String daypart) implements Outcome {
    }

    /** The service has no provider key: smart understanding does not exist on this server. */
    public record Off() implements Outcome {
    }

    /** The service could not give a usable answer in time (the caller opens its breaker). */
    public record Unavailable() implements Outcome {
    }

    static final int MAX_CATEGORIES = 2;
    static final int MAX_FACETS = ExploreFacets.MAX_PER_QUERY;

    private static final Logger log = LoggerFactory.getLogger(UnderstandingClient.class);

    private final ExploreUnderstandingProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;

    public UnderstandingClient(ExploreUnderstandingProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    }

    /** Asks the service to turn [text] into chips. Never throws. */
    public Outcome understand(String text) {
        try {
            byte[] body = json.writeValueAsBytes(Map.of("text", text));
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.url() + "/v1/understand"))
                    .timeout(properties.timeout())
                    .header("Content-Type", "application/json")
                    .header("X-Moderation-Key", properties.key())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 503 && isOff(response.body())) return new Off();
            if (response.statusCode() != 200) {
                log.warn("Understanding service answered {}; the standard understanding is used", response.statusCode());
                return new Unavailable();
            }
            return parse(json.readTree(new String(response.body(), StandardCharsets.UTF_8)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Unavailable();
        } catch (IOException | RuntimeException e) {
            log.warn("Understanding service unavailable ({}); the standard understanding is used",
                    e.getClass().getSimpleName());
            return new Unavailable();
        }
    }

    /** Whether the service holds a provider key (GET /v1/health "understanding": "UP"). False on any doubt. */
    public boolean serviceHasKey() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.url() + "/v1/health"))
                    .timeout(properties.timeout()).GET().build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return response.statusCode() == 200
                    && "UP".equals(json.readTree(new String(response.body(), StandardCharsets.UTF_8))
                    .path("understanding").asText(""));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private boolean isOff(byte[] body) {
        try {
            return "off".equals(json.readTree(new String(body, StandardCharsets.UTF_8)).path("error").asText(""));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Fail-closed reading of one answer. */
    static Outcome parse(JsonNode root) {
        if (root == null || !root.isObject()) return new Unavailable();
        List<String> categories = closed(root.path("categories"), MAX_CATEGORIES, name -> {
            try {
                Interest.valueOf(name);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        });
        List<String> facets = closed(root.path("facets"), MAX_FACETS, ExploreFacets::isValid);
        String daypart = null;
        if (root.path("daypart").isTextual()) {
            try {
                daypart = Daypart.valueOf(root.path("daypart").asText()).name();
            } catch (IllegalArgumentException e) {
                daypart = null;
            }
        }
        return new Understood(List.copyOf(categories), List.copyOf(facets), daypart);
    }

    private static List<String> closed(JsonNode array, int max, java.util.function.Predicate<String> listed) {
        List<String> out = new ArrayList<>();
        if (!array.isArray()) return out;
        for (JsonNode item : array) {
            if (out.size() >= max) break;
            if (!item.isTextual()) continue;
            String code = item.asText();
            if (listed.test(code) && !out.contains(code)) out.add(code);
        }
        return out;
    }
}
