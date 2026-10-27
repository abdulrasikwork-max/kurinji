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
 * OTP route, success, Fast2SMS refusals (KYC 996, wallet 999), HTTP errors and an unreadable answer.
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
    void sendsTheOtpRoute_withTheNationalNumber_andTheKeyInTheHeader() throws Exception {
        sender().send("+919876543210", "482913");

        JsonNode sent = json.readTree(body.get());
        assertThat(sent.get("route").asText()).isEqualTo("otp");
        assertThat(sent.get("variables_values").asText()).isEqualTo("482913");
        assertThat(sent.get("numbers").asText()).isEqualTo("9876543210");
        assertThat(key.get()).isEqualTo("test-key");
    }

    @Test
    void aFast2SmsRefusal_isReportedByItsStatusCodeOnly() {
        answer = "{\"return\":false,\"status_code\":996,\"message\":\"Before using OTP SMS API, complete KYC.\"}";
        status = 400;
        assertThatThrownBy(() -> sender().send("+919876543210", "482913"))
                .isInstanceOf(OtpSender.OtpDeliveryException.class)
                .hasMessageContaining("996").hasMessageNotContaining("482913").hasMessageNotContaining("9876543210");

        answer = "{\"return\":false,\"status_code\":999,\"message\":\"Complete single transaction of minimum 100 INR\"}";
        status = 200; // Fast2SMS may answer 200 with return=false
        assertThatThrownBy(() -> sender().send("+919876543210", "482913")).hasMessageContaining("999");
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
