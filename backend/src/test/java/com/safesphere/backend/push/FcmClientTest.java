package com.safesphere.backend.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.safesphere.backend.push.entity.PushType;
import com.safesphere.backend.push.service.FcmClient;
import com.safesphere.backend.push.service.PushSender;
import com.safesphere.backend.push.service.PushSender.Push;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * N7 (D26, D27, D35): the FCM HTTP v1 client against a local stub of Google's token endpoint and of FCM. The assertion is
 * a real RS256 service-account JWT; the message is data-only with the kind's priority, lifetime and collapse key; every
 * FCM answer maps to what the queue does; the access token is reused until it nearly expires.
 */
class FcmClientTest {

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private KeyPair keys;
    private Path credentials;
    private final List<String> tokenRequests = new CopyOnWriteArrayList<>();
    private final List<String> sendBodies = new CopyOnWriteArrayList<>();
    private final List<String> sendAuth = new CopyOnWriteArrayList<>();
    private final Deque<int[]> sendStatuses = new ArrayDeque<>();
    private final Deque<String> sendAnswers = new ArrayDeque<>();
    private final AtomicInteger tokenCalls = new AtomicInteger();

    @BeforeEach
    void start(@TempDir Path dir) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        credentials = dir.resolve("sa.json");
        Files.writeString(credentials, json.writeValueAsString(Map.of("type", "service_account", "project_id", "irl-test",
                "private_key_id", "kid1", "private_key", pem, "client_email", "push@irl-test.iam.gserviceaccount.com",
                "token_uri", "https://oauth2.googleapis.com/token")));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            tokenCalls.incrementAndGet();
            tokenRequests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"access_token\":\"ya29.test\",\"expires_in\":3600,\"token_type\":\"Bearer\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.createContext("/v1/projects/irl-test/messages:send", exchange -> {
            sendBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            sendAuth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            int[] status;
            String answer;
            synchronized (sendStatuses) {
                status = sendStatuses.isEmpty() ? new int[] {200, 0} : sendStatuses.pollFirst();
                answer = sendAnswers.isEmpty() ? "{\"name\":\"projects/irl-test/messages/1\"}" : sendAnswers.pollFirst();
            }
            if (status[1] > 0) exchange.getResponseHeaders().add("Retry-After", String.valueOf(status[1]));
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status[0], out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private FcmClient client() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new FcmClient(credentials, json, Clock.systemUTC(), base + "/token", base);
    }

    private void answer(int status, int retryAfter, String body) {
        synchronized (sendStatuses) {
            sendStatuses.add(new int[] {status, retryAfter});
            sendAnswers.add(body);
        }
    }

    private static Push push(PushType type) {
        return new Push("fcm-token-of-a-phone", type, "0a1b2c3d-0000-4000-8000-00000000000c",
                "11111111-2222-4333-8444-555555555555", type.highPriority(), 1800, "plan-0a1b");
    }

    @Test
    void signsAServiceAccountAssertion_andSendsADataOnlyMessage() throws Exception {
        PushSender.Outcome outcome = client().send(push(PushType.CIRCLE_SOS));
        assertThat(outcome.result()).isEqualTo(PushSender.Result.ACCEPTED);

        String form = tokenRequests.get(0);
        assertThat(form).contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer");
        String assertion = URLDecoder.decode(form.substring(form.indexOf("assertion=") + 10), StandardCharsets.UTF_8);
        SignedJWT jwt = SignedJWT.parse(assertion);
        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) keys.getPublic()))).isTrue();
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("kid1");
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("push@irl-test.iam.gserviceaccount.com");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("scope")).isEqualTo("https://www.googleapis.com/auth/firebase.messaging");

        assertThat(sendAuth.get(0)).isEqualTo("Bearer ya29.test");
        JsonNode message = json.readTree(sendBodies.get(0)).get("message");
        assertThat(message.get("token").asText()).isEqualTo("fcm-token-of-a-phone");
        assertThat(message.has("notification")).as("data-only: the phone writes the notification").isFalse();
        assertThat(json.convertValue(message.get("data"), Map.class)).containsOnlyKeys("t", "s", "m", "v");
        assertThat(message.get("android").get("priority").asText()).isEqualTo("HIGH");
        assertThat(message.get("android").get("ttl").asText()).isEqualTo("1800s");
        assertThat(message.get("android").get("collapse_key").asText()).isEqualTo("plan-0a1b");
        assertThat(message.get("android").has("notification")).isFalse();

        client(); // a new client has its own cache; the same client reuses its access token:
        FcmClient one = client();
        one.send(push(PushType.REPLY_TURN));
        one.send(push(PushType.REPLY_TURN));
        assertThat(tokenCalls.get()).isEqualTo(2);
        assertThat(json.readTree(sendBodies.get(1)).get("message").get("android").get("priority").asText()).isEqualTo("NORMAL");
    }

    @Test
    void everyFcmAnswerMapsToWhatTheQueueDoes() {
        FcmClient client = client();
        answer(404, 0, "{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\",\"details\":[{\"errorCode\":\"UNREGISTERED\"}]}}");
        assertThat(client.send(push(PushType.REPLY_TURN)).result()).isEqualTo(PushSender.Result.UNREGISTERED);
        answer(400, 0, "{\"error\":{\"code\":400,\"message\":\"The registration token is not a valid FCM registration token\","
                + "\"status\":\"INVALID_ARGUMENT\"}}");
        assertThat(client.send(push(PushType.REPLY_TURN)).result()).isEqualTo(PushSender.Result.UNREGISTERED);
        answer(400, 0, "{\"error\":{\"code\":400,\"message\":\"Invalid JSON payload\",\"status\":\"INVALID_ARGUMENT\"}}");
        assertThat(client.send(push(PushType.REPLY_TURN)).result()).as("our mistake never deletes tokens")
                .isEqualTo(PushSender.Result.FAILED);
        answer(403, 0, "{\"error\":{\"code\":403,\"details\":[{\"errorCode\":\"SENDER_ID_MISMATCH\"}]}}");
        assertThat(client.send(push(PushType.REPLY_TURN)).result()).isEqualTo(PushSender.Result.UNREGISTERED);
        answer(429, 30, "{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\"}}");
        PushSender.Outcome slow = client.send(push(PushType.REPLY_TURN));
        assertThat(slow.result()).isEqualTo(PushSender.Result.RETRY);
        assertThat(slow.retryAfter()).isEqualTo(Duration.ofSeconds(30));
        answer(503, 0, "{\"error\":{\"code\":503,\"status\":\"UNAVAILABLE\"}}");
        assertThat(client.send(push(PushType.REPLY_TURN)).result()).isEqualTo(PushSender.Result.RETRY);
        int before = tokenCalls.get();
        answer(401, 0, "{\"error\":{\"code\":401,\"status\":\"UNAUTHENTICATED\"}}");
        assertThat(client.send(push(PushType.REPLY_TURN)).result()).isEqualTo(PushSender.Result.AUTH);
        assertThat(client.send(push(PushType.REPLY_TURN)).result()).isEqualTo(PushSender.Result.ACCEPTED);
        assertThat(tokenCalls.get()).as("a refused token is fetched again").isEqualTo(before + 1);
        // No outcome code ever carries the token or the key.
        assertThat(slow.code()).doesNotContain("fcm-token").doesNotContain("ya29");
    }

    @Test
    void unusableCredentialsOrPlainHttpAreRefusedAtStartUp(@TempDir Path dir) throws Exception {
        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, "{\"project_id\":\"irl-test\",\"client_email\":\"x@y\",\"private_key\":\"nope\"}");
        assertThatThrownBy(() -> new FcmClient(broken, json, Clock.systemUTC()))
                .hasMessageContaining("private_key").hasMessageNotContaining("nope");
        assertThatThrownBy(() -> new FcmClient(credentials, json, Clock.systemUTC(), "http://evil.example/token", null))
                .hasMessageContaining("https");
    }
}
