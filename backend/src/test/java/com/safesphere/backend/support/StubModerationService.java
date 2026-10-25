package com.safesphere.backend.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Phase 13E: a stand-in for the Python moderation service in backend tests (the real one has its own tests and the
 * live check). Markers in the text choose the answer, with the flagged span on the marker itself:
 *   [THREAT] severe targeted threat · [INSULT] targeted insult · [QUOTED] a quoted threat · [STRONG] untargeted
 *   profanity · [SELF] self-harm · [UNKNOWN] codes it must drop · [500] an error · [SLOW] longer than the timeout ·
 *   [GARBAGE] not JSON. [down] answers 503 to everything (an outage).
 */
public final class StubModerationService {

    public static final String KEY = "stub-moderation-key-0123456789";

    public record Seen(String kind, String text, String key, List<String> fields) {
    }

    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    public final List<Seen> seen = new CopyOnWriteArrayList<>();
    public volatile boolean down;

    public StubModerationService() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/v1/assess", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody().readAllBytes());
            List<String> fields = new ArrayList<>();
            request.fieldNames().forEachRemaining(fields::add);
            String text = request.path("text").asText();
            seen.add(new Seen(request.path("kind").asText(), text,
                    exchange.getRequestHeaders().getFirst("X-Moderation-Key"), fields));
            int status = 200;
            String body;
            if (down) {
                status = 503;
                body = "{}";
            } else if (text.contains("[500]")) {
                status = 500;
                body = "{}";
            } else if (text.contains("[GARBAGE]")) {
                body = "{not json";
            } else {
                if (text.contains("[SLOW]")) sleep(1500);
                body = json.writeValueAsString(answer(text));
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.setExecutor(Executors.newCachedThreadPool()); // a slow answer must not block the next request
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    private static Map<String, Object> answer(String text) {
        List<Map<String, Object>> evidence = new ArrayList<>();
        mark(text, "[THREAT]", "THREAT", 3, true, false, evidence);
        mark(text, "[INSULT]", "HARASSMENT", 2, true, false, evidence);
        mark(text, "[QUOTED]", "THREAT", 3, true, true, evidence);
        mark(text, "[STRONG]", "PROFANITY", 2, false, false, evidence);
        mark(text, "[UNKNOWN]", "MIND_READING", 9, true, false, evidence);
        return Map.of("versions", Map.of("normaliser", "norm-1", "lexicon", "lex-1", "model", "-"),
                "script", "LATIN", "evidence", evidence, "strongLanguage", text.contains("[STRONG]"),
                "selfHarm", text.contains("[SELF]"), "modelScores", Map.of());
    }

    private static void mark(String text, String marker, String category, int severity, boolean targeted,
                             boolean quoted, List<Map<String, Object>> evidence) {
        int at = text.indexOf(marker);
        if (at < 0) return;
        int start = text.codePointCount(0, at);
        evidence.add(Map.of("category", category, "severity", severity, "targeted", targeted, "quoted", quoted,
                "start", start, "end", start + marker.length(), "source", "LEXICON"));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
