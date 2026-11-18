package com.safesphere.backend.push.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * N7 (D27 = N-119): the ONE Firebase Cloud Messaging client (HTTP v1), the backend's third outbound client
 * (ModelPlacesTest). No Firebase library: the OAuth 2.0 service-account assertion is signed with Nimbus JOSE (already on
 * the classpath through the OAuth2 resource server) and sent with the JDK HttpClient.
 *
 * Sends DATA-ONLY messages (no notification block: the phone builds the notification itself, D26), with the kind's
 * priority, lifetime and collapse key. Never logs (the dispatcher logs counts and status codes); never puts the token,
 * the key or a message in an exception text.
 */
public class FcmClient implements PushSender {

    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String DEFAULT_FCM_BASE = "https://fcm.googleapis.com";
    private static final Pattern PROJECT = Pattern.compile("^[a-z0-9-]{3,64}$");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final String projectId;
    private final String clientEmail;
    private final String keyId;
    private final RSAPrivateKey privateKey;
    private final URI tokenUri;
    private final URI sendUri;
    private final ObjectMapper json;
    private final Clock clock;
    private final HttpClient http;

    private String accessToken;
    private Instant accessTokenExpires = Instant.EPOCH;

    /** Production: everything from the service-account JSON file. */
    public FcmClient(Path credentialsFile, ObjectMapper json, Clock clock) {
        this(credentialsFile, json, clock, null, null);
    }

    /** Tests: the same, with the token and FCM addresses pointed at a local stub. */
    public FcmClient(Path credentialsFile, ObjectMapper json, Clock clock, String tokenUriOverride, String fcmBaseOverride) {
        JsonNode credentials;
        try {
            credentials = json.readTree(Files.readString(credentialsFile, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("FCM_CREDENTIALS_FILE cannot be read as a service-account JSON");
        }
        this.projectId = credentials.path("project_id").asText("");
        this.clientEmail = credentials.path("client_email").asText("");
        this.keyId = credentials.path("private_key_id").asText("");
        if (!PROJECT.matcher(projectId).matches() || clientEmail.isBlank()) {
            throw new IllegalStateException("FCM_CREDENTIALS_FILE has no usable project_id / client_email");
        }
        this.privateKey = parsePrivateKey(credentials.path("private_key").asText(""));
        String tokenUriText = tokenUriOverride != null ? tokenUriOverride
                : credentials.path("token_uri").asText(DEFAULT_TOKEN_URI);
        this.tokenUri = URI.create(tokenUriText);
        String base = fcmBaseOverride != null ? fcmBaseOverride : DEFAULT_FCM_BASE;
        this.sendUri = URI.create(base + "/v1/projects/" + projectId + "/messages:send");
        requireHttps(tokenUri, tokenUriOverride != null);
        requireHttps(sendUri, fcmBaseOverride != null);
        this.json = json;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Outcome send(Push push) {
        String bearer;
        try {
            bearer = accessToken();
        } catch (AuthFailure e) {
            return Outcome.of(Result.AUTH, e.code);
        } catch (IOException e) {
            return Outcome.of(Result.RETRY, "token-" + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.of(Result.RETRY, "interrupted");
        }
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(sendUri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + bearer)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body(push), StandardCharsets.UTF_8))
                    .build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Outcome.of(Result.RETRY, e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.of(Result.RETRY, "interrupted");
        }
        return outcome(response.statusCode(), response.body(), response.headers().firstValue("Retry-After").orElse(null));
    }

    @Override
    public String describe() {
        return "FCM (project " + projectId + ")";
    }

    /** The request body: data-only, the kind's priority, lifetime and collapse key (FcmClientTest pins it). */
    String body(Push push) {
        Map<String, Object> android = new LinkedHashMap<>();
        android.put("priority", push.highPriority() ? "HIGH" : "NORMAL");
        android.put("ttl", push.ttlSeconds() + "s");
        android.put("collapse_key", push.collapseKey());
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("token", push.token());
        message.put("data", push.data());
        message.put("android", android);
        try {
            return json.writeValueAsString(Map.of("message", message));
        } catch (IOException e) {
            throw new IllegalStateException("push body not built");
        }
    }

    /** FCM's answer → what the queue does. */
    Outcome outcome(int status, String body, String retryAfter) {
        if (status / 100 == 2) return Outcome.accepted();
        String errorCode = errorCode(body);
        String message = errorMessage(body);
        if (status == 404 || "UNREGISTERED".equals(errorCode)) return Outcome.of(Result.UNREGISTERED, status + "/UNREGISTERED");
        if (status == 403 && "SENDER_ID_MISMATCH".equals(errorCode)) {
            return Outcome.of(Result.UNREGISTERED, status + "/SENDER_ID_MISMATCH");
        }
        if (status == 400) {
            // An invalid token is the phone's problem (delete it); any other 400 is ours: never delete tokens for it.
            boolean badToken = message.toLowerCase(java.util.Locale.ROOT).contains("registration token");
            return Outcome.of(badToken ? Result.UNREGISTERED : Result.FAILED, "400/" + (errorCode.isEmpty() ? "-" : errorCode));
        }
        if (status == 401 || status == 403) {
            synchronized (this) {
                accessToken = null;
            }
            return Outcome.of(Result.AUTH, status + "/" + (errorCode.isEmpty() ? "-" : errorCode));
        }
        if (status == 429 || status / 100 == 5) {
            return new Outcome(Result.RETRY, retryAfter(retryAfter), status + "/" + (errorCode.isEmpty() ? "-" : errorCode));
        }
        return Outcome.of(Result.FAILED, String.valueOf(status));
    }

    private synchronized String accessToken() throws IOException, InterruptedException, AuthFailure {
        Instant now = clock.instant();
        if (accessToken != null && now.isBefore(accessTokenExpires.minusSeconds(60))) return accessToken;
        String assertion = assertion(now);
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(tokenUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 400 || response.statusCode() == 401 || response.statusCode() == 403) {
            throw new AuthFailure("token-" + response.statusCode());
        }
        if (response.statusCode() / 100 != 2) throw new IOException("token endpoint " + response.statusCode());
        JsonNode answer = json.readTree(response.body());
        String token = answer.path("access_token").asText("");
        long expiresIn = answer.path("expires_in").asLong(0);
        if (token.isBlank() || expiresIn <= 0) throw new AuthFailure("token-unusable");
        accessToken = token;
        accessTokenExpires = now.plusSeconds(expiresIn);
        return accessToken;
    }

    /** The service-account JWT (RS256, one hour), as Google's OAuth 2.0 server-to-server flow expects. */
    String assertion(Instant now) throws AuthFailure {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(clientEmail)
                .audience(tokenUri.toString())
                .claim("scope", SCOPE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .build();
        JWSHeader.Builder header = new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT);
        if (!keyId.isBlank()) header.keyID(keyId);
        SignedJWT jwt = new SignedJWT(header.build(), claims);
        try {
            jwt.sign(new RSASSASigner(privateKey));
        } catch (JOSEException e) {
            throw new AuthFailure("sign");
        }
        return jwt.serialize();
    }

    private String errorCode(String body) {
        try {
            JsonNode error = json.readTree(body == null ? "" : body).path("error");
            for (JsonNode detail : error.path("details")) {
                String code = detail.path("errorCode").asText("");
                if (!code.isEmpty()) return code;
            }
            return error.path("status").asText("");
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private String errorMessage(String body) {
        try {
            return json.readTree(body == null ? "" : body).path("error").path("message").asText("");
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static Duration retryAfter(String header) {
        if (header == null) return null;
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds > 0 && seconds < 3600 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static RSAPrivateKey parsePrivateKey(String pem) {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IllegalArgumentException | GeneralSecurityException | ClassCastException e) {
            throw new IllegalStateException("FCM_CREDENTIALS_FILE has no usable private_key");
        }
    }

    private static void requireHttps(URI uri, boolean testOverride) {
        boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
        if (!"https".equals(uri.getScheme()) && !(testOverride && local)) {
            throw new IllegalStateException("FCM addresses must be https");
        }
    }

    /** Our credentials were refused, or the key could not sign. */
    static final class AuthFailure extends Exception {
        final String code;

        AuthFailure(String code) {
            super(code);
            this.code = code;
        }
    }
}
