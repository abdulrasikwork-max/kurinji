package com.safesphere.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Plain unit test (no Spring): the app must refuse to start with a weak or missing JWT secret. */
class JwtConfigTest {

    private final JwtConfig config = new JwtConfig();

    @Test
    void rejectsMissingSecret() {
        assertThrows(IllegalStateException.class,
                () -> config.jwtSecretKey(new JwtProperties(null, "issuer", Duration.ofHours(1))));
    }

    @Test
    void rejectsShortSecret() {
        assertThrows(IllegalStateException.class,
                () -> config.jwtSecretKey(new JwtProperties("too-short", "issuer", Duration.ofHours(1))));
    }

    @Test
    void acceptsSecretOfAtLeast32Bytes() {
        String secret = "a".repeat(32);
        assertDoesNotThrow(() -> config.jwtSecretKey(new JwtProperties(secret, "issuer", Duration.ofHours(1))));
    }
}
