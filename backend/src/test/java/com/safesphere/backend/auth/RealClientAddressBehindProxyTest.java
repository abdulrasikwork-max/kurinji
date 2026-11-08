package com.safesphere.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * L1: with FORWARD_HEADERS_STRATEGY=native (the laptop + tunnel setting) each real client gets its OWN sign-in limit, so one
 * friend's typos cannot lock the others out. The request comes from loopback, the way nginx on the same host reaches the
 * backend; nginx is what fills X-Forwarded-For from Cloudflare's CF-Connecting-IP (deploy/nginx/irl-tunnel.conf.template).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "server.forward-headers-strategy=native",
        "app.auth.login-failures-per-address=3",
        "app.auth.login-failures-per-account=1000"
})
class RealClientAddressBehindProxyTest {

    @Value("${local.server.port}")
    private int port;

    @Test
    void eachForwardedAddressHasItsOwnFailureLimit() throws Exception {
        String friendA = "198.51.100.10";
        String friendB = "198.51.100.11";

        for (int i = 0; i < 3; i++) {
            assertThat(RealClientAddressSupport.failedSignInFrom(port, friendA)).isEqualTo(401);
        }
        assertThat(RealClientAddressSupport.failedSignInFrom(port, friendA)).as("friend A is over the limit").isEqualTo(429);
        assertThat(RealClientAddressSupport.failedSignInFrom(port, friendB)).as("friend B is not affected").isEqualTo(401);
    }
}
