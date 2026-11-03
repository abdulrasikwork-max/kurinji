package com.safesphere.backend.explore;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A local stand-in for the internal Python service (and, behind it, the provider): answers what a test sets and records
 * what it was sent. Nothing here ever reaches a network beyond 127.0.0.1.
 */
final class StubUnderstandingService implements AutoCloseable {

    static final String EMPTY_ANSWER = "{\"categories\":[],\"facets\":[],\"daypart\":null}";
    static final String HEALTH_UP = "{\"status\":\"UP\",\"understanding\":\"UP\"}";

    record Received(String path, String key, String body) {
    }

    volatile int status = 200;
    volatile String body = EMPTY_ANSWER;
    volatile String health = HEALTH_UP;
    volatile long delayMillis = 0;
    final List<Received> received = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    StubUnderstandingService() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/v1/understand", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-Moderation-Key"), request));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            reply(exchange, status, body);
        });
        server.createContext("/v1/health", exchange -> reply(exchange, 200, health));
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void reset() {
        status = 200;
        body = EMPTY_ANSWER;
        health = HEALTH_UP;
        delayMillis = 0;
        received.clear();
    }

    private static void reply(HttpExchange exchange, int code, String payload) throws IOException {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(code, data.length);
            try (var out = exchange.getResponseBody()) {
                out.write(data);
            }
        } catch (IOException ignored) {
            // the caller gave up (the timeout test)
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
