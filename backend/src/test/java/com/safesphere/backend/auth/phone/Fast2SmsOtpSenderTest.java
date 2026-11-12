package com.safesphere.backend.auth.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Phase 17B: the Fast2SMS adapter against a local stub (never the real API, no key, no SMS): the request shape of the
 * Quick SMS route (route=q, changed 2026-11-09 after Fast2SMS closed route=otp), success, Fast2SMS refusals (wallet 416,
 * spam 995, old API 990, first top-up 999), HTTP errors and an unreadable answer.
 */
class Fast2SmsOtpSenderTest {

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> key = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer = "{\"return\":true,\"request_id\":\"abc\",\"message\":[\"SMS sent successfully.\"]}";

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dev/bulkV2", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            key.set(exchange.getRequestHeaders().getFirst("authorization"));
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream o = exchange.getResponseBody()) {
                o.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private Fast2SmsOtpSender sender() {
        return new Fast2SmsOtpSender("http://127.0.0.1:" + server.getAddress().getPort() + "/dev/bulkV2",
                "test-key", json);
    }

    @Test
    void sendsTheQuickSmsRoute_withTheNationalNumber_andTheKeyInTheHeader() throws Exception {
        sender().send("+919876543210", "482913");

        JsonNode sent = json.readTree(body.get());
        assertThat(sent.get("route").asText()).isEqualTo("q");
        assertThat(sent.get("message").asText()).isEqualTo("482913 is your IRL verification code. Do not share it with anyone.");
        assertThat(sent.get("numbers").asText()).isEqualTo("9876543210");
        assertThat(key.get()).isEqualTo("test-key");
        // exactly these three fields: no old OTP-route field, no sender id / template (Quick SMS needs none)
        assertThat(sent.fieldNames()).toIterable().containsExactlyInAnyOrder("route", "message", "numbers");
    }

    @Test
    void theMessage_isPlainAscii_oneSms_hasTheCodeOnce_andNeverTheNumber() throws Exception {
        sender().send("+919876543210", "1234567890"); // the longest code the sender accepts

        String text = json.readTree(body.get()).get("message").asText();
        assertThat(text).hasSizeLessThanOrEqualTo(160); // one English SMS = one billed part
        assertThat(text.chars().allMatch(c -> c >= 32 && c < 127)).isTrue(); // no Unicode: it would cut a part to 70 characters
        assertThat(text).contains("IRL").contains("Do not share it");
        assertThat(text.split("1234567890", -1)).hasSize(2); // the code appears exactly once
        assertThat(text).doesNotContain("9876543210").doesNotContain("http");
    }

    @Test
    void aFast2SmsRefusal_isReportedByItsStatusCodeOnly() {
        // 400 with a status code: wallet empty, spam block, old API, first top-up missing
        answer = "{\"return\":false,\"status_code\":416,\"message\":\"You don't have sufficient wallet balance\"}";
        status = 400;
        assertThatThrownBy(() -> sender().send("+919876543210", "482913"))
                .isInstanceOf(OtpSender.OtpDeliveryException.class)
                .hasMessageContaining("416").hasMessageNotContaining("482913").hasMessageNotContaining("9876543210");

        answer = "{\"return\":false,\"status_code\":995,\"message\":\"Spamming detected\"}";
        assertThatThrownBy(() -> sender().send("+919876543210", "482913")).hasMessageContaining("995");

        answer = "{\"return\":false,\"status_code\":990,\"message\":\"You're hitting old API\"}";
        assertThatThrownBy(() -> sender().send("+919876543210", "482913")).hasMessageContaining("990");

        answer = "{\"return\":false,\"status_code\":999,\"message\":\"Complete single transaction of minimum 100 INR\"}";
        status = 200; // Fast2SMS may answer 200 with return=false
        assertThatThrownBy(() -> sender().send("+919876543210", "482913")).hasMessageContaining("999");
    }

    @Test
    void anHttp200WithoutReturnTrue_isNotSuccess() {
        answer = "{\"message\":[\"queued\"]}"; // no "return": true
        assertThatThrownBy(() -> sender().send("+919876543210", "482913"))
                .isInstanceOf(OtpSender.OtpDeliveryException.class).hasMessageContaining("200");
    }

    @Test
    void aRefusalNeverEchoesTheMessageTheBodyOrTheKey() {
        answer = "{\"return\":false,\"status_code\":407,\"message\":\"Invalid words used in message 482913 9876543210\"}";
        status = 400;
        assertThatThrownBy(() -> sender().send("+919876543210", "482913"))
                .isInstanceOf(OtpSender.OtpDeliveryException.class)
                .hasMessageContaining("407").hasMessageNotContaining("Invalid words").hasMessageNotContaining("482913")
                .hasMessageNotContaining("9876543210").hasMessageNotContaining("test-key");
    }

    @Test
    void anUnreadableAnswer_orAServerError_isARefusal() {
        answer = "<html>oops</html>";
        status = 502;
        assertThatThrownBy(() -> sender().send("+919876543210", "482913"))
                .isInstanceOf(OtpSender.OtpDeliveryException.class).hasMessageContaining("502");
    }

    @Test
    void refusesToStartWithoutAKey_orOverPlainHttpToAnotherHost() {
        assertThatThrownBy(() -> new Fast2SmsOtpSender("https://www.fast2sms.com/dev/bulkV2", " ", json))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Fast2SmsOtpSender("http://www.fast2sms.com/dev/bulkV2", "k", json))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void neverSendsANonNumericCode() {
        assertThatThrownBy(() -> sender().send("+919876543210", "12ab56"))
                .isInstanceOf(OtpSender.OtpDeliveryException.class);
        assertThat(body.get()).isNull();
    }
}
