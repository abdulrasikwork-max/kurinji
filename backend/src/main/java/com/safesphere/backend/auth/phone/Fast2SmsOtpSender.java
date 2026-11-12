package com.safesphere.backend.auth.phone;

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

/**
 * Phase 17B (changed 2026-11-09): delivers codes through Fast2SMS's Quick SMS route (POST /dev/bulkV2, route=q, JSON body
 * {route, message, numbers}). Fast2SMS closed its old OTP route (route=otp). Quick SMS needs no DLT registration; the text
 * is ours, the sender is Fast2SMS's (a numeric / random sender, not a brand). It only DELIVERS: the code is made, stored,
 * expired and checked by this server.
 *
 * Needs, on the Fast2SMS account (not in code): a first top-up of at least Rs 100 (else status 999) and wallet balance
 * (else 416); about Rs 7 per SMS. Indian numbers only. The API key comes only from FAST2SMS_API_KEY.
 *
 * Never logs the key, the code, the message or the number; a refusal is reported by Fast2SMS's status code only.
 */
public class Fast2SmsOtpSender implements OtpSender {

    /** Plain ASCII on purpose: one English SMS holds 160 characters, one Unicode part only 70 (each part is billed). */
    static final String MESSAGE_TEMPLATE = "%s is your IRL verification code. Do not share it with anyone.";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private final URI endpoint;
    private final String apiKey;
    private final HttpClient http;
    private final ObjectMapper json;

    public Fast2SmsOtpSender(String endpoint, String apiKey, ObjectMapper json) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OTP_PROVIDER=FAST2SMS needs FAST2SMS_API_KEY");
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
    public void send(String phoneE164, String code) {
        if (code == null || !code.matches("^[0-9]{4,10}$")) throw new OtpDeliveryException("code not numeric");
        String body;
        try {
            body = json.writeValueAsString(Map.of("route", "q", "message", MESSAGE_TEMPLATE.formatted(code),
                    "numbers", PhoneNumbers.national(phoneE164)));
        } catch (IOException e) {
            throw new OtpDeliveryException("request not built");
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
            throw new OtpDeliveryException("Fast2SMS not reachable (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OtpDeliveryException("interrupted");
        }
        JsonNode answer = parse(response.body());
        boolean accepted = answer != null && answer.path("return").asBoolean(false);
        if (response.statusCode() / 100 != 2 || !accepted) {
            String status = answer == null ? "-" : answer.path("status_code").asText("-");
            throw new OtpDeliveryException("Fast2SMS refused (HTTP " + response.statusCode() + ", status " + status + ")");
        }
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
