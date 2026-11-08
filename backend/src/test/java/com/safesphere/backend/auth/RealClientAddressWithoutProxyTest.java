package com.safesphere.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * L1: the DEFAULT (no trusted proxy, forward-headers-strategy=none) is unchanged: X-Forwarded-For is ignored, because any
 * client could forge it to dodge the limits. Both "addresses" below are the same TCP peer, so they share one limit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "server.forward-headers-strategy=none",
        "app.auth.login-failures-per-address=3",
        "app.auth.login-failures-per-account=1000"
})
class RealClientAddressWithoutProxyTest {

    @Value("${local.server.port}")
    private int port;

    @Test
    void aForgedForwardedAddressDoesNotEscapeTheLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(RealClientAddressSupport.failedSignInFrom(port, "198.51.100.10")).isEqualTo(401);
        }
        assertThat(RealClientAddressSupport.failedSignInFrom(port, "198.51.100.10")).isEqualTo(429);
        assertThat(RealClientAddressSupport.failedSignInFrom(port, "203.0.113.77"))
                .as("a different forged address shares the same peer limit").isEqualTo(429);
    }
}
