package com.safesphere.backend.common.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The ONE Fast2SMS adapter (Phase 17B; moved here in N6 so verification codes and Safety Circle alerts share it, and the
 * backend keeps exactly two outbound clients, ModelPlacesTest). Quick SMS route: POST /dev/bulkV2, header
 * "authorization", JSON {route: "q", message, numbers}. No DLT registration; Fast2SMS's numeric / random sender.
 *
 * Needs, on the Fast2SMS account (not in code): a first top-up of at least Rs 100 (else status 999) and wallet balance
 * (else 416); about Rs 7 per SMS; Indian numbers only. The API key comes only from FAST2SMS_API_KEY.
 *
 * Never logs the key, the message or the number; a refusal is reported by Fast2SMS's status code only. Delivery reports
 * are not read (N6-B2: not built; "accepted by the provider" is never shown as "delivered").
 */
public class Fast2SmsClient implements SmsGateway {

    private static final Pattern E164 = Pattern.compile("^\\+91[6-9][0-9]{9}$");
    private static final Pattern REFERENCE = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private final URI endpoint;
    private final String apiKey;
    private final HttpClient http;
    private final ObjectMapper json;

    public Fast2SmsClient(String endpoint, String apiKey, ObjectMapper json) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("The FAST2SMS provider needs FAST2SMS_API_KEY");
        }
        this.endpoint = URI.create(endpoint);
        if (!"https".equals(this.endpoint.getScheme()) && !"localhost".equals(this.endpoint.getHost())
                && !"127.0.0.1".equals(this.endpoint.getHost())) {
            throw new IllegalStateException("app.otp.fast2sms-url must be https");
        }
        this.apiKey = apiKey.trim();
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String send(String phoneE164, String text) {
        if (phoneE164 == null || !E164.matcher(phoneE164).matches()) throw new SmsRefusedException("not an Indian mobile");
        if (text == null || text.isBlank()) throw new SmsRefusedException("empty message");
        String body;
        try {
            body = json.writeValueAsString(Map.of("route", "q", "message", text, "numbers", phoneE164.substring(3)));
        } catch (IOException e) {
            throw new SmsRefusedException("request not built");
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("authorization", apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SmsRefusedException("Fast2SMS not reachable (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SmsRefusedException("interrupted");
        }
        JsonNode answer = parse(response.body());
        boolean accepted = answer != null && answer.path("return").asBoolean(false);
        if (response.statusCode() / 100 != 2 || !accepted) {
            String status = answer == null ? "-" : answer.path("status_code").asText("-");
            throw new SmsRefusedException("Fast2SMS refused (HTTP " + response.statusCode() + ", status " + status + ")");
        }
        String reference = answer.path("request_id").asText("");
        return REFERENCE.matcher(reference).matches() ? reference : null;
    }

    @Override
    public String describe() {
        return "FAST2SMS (" + endpoint.getHost() + ")";
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? null : json.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }
}
