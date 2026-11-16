package com.safesphere.backend.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.safesphere.backend.community.service.CommunityKeys;
import com.safesphere.backend.config.CommunityProperties;
import com.safesphere.backend.config.JwtProperties;
import com.safesphere.backend.config.ProductionGuard;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Phase N3: the community keys (HMACs never reveal the input; AES-GCM refuses anything changed). */
class CommunityKeysTest {

    private static final String JWT = "jwt-secret-for-this-test-only-0123456789";
    private static final Path ABSOLUTE = Path.of("id-photos").toAbsolutePath();

    private static CommunityKeys keys(String secret) {
        return new CommunityKeys(new CommunityProperties(secret, null, null, null, null, null, null, null, null, null,
                null), new JwtProperties(JWT, "https://safesphere.example", Duration.ofHours(1)));
    }

    @Test
    void rollKeys_areStable_keyed_andNeverTheInput() {
        CommunityKeys a = keys("community-secret-A-0123456789abcdef");
        CommunityKeys b = keys("community-secret-B-0123456789abcdef");
        String key = a.rollKey("MIET", "E1236003");
        assertThat(key).matches("^[0-9a-f]{64}$").doesNotContain("1236003");
        assertThat(a.rollKey("MIET", "E1236003")).isEqualTo(key);
        assertThat(b.rollKey("MIET", "E1236003")).as("another secret, another key").isNotEqualTo(key);
        assertThat(a.rollKey("MIET", "E1236004")).isNotEqualTo(key);
        assertThat(a.phoneKey("+919876543210")).isNotEqualTo(a.rollKey("MIET", "+919876543210"));
        assertThat(a.codeHash("IN_PERSON", "ABCD2345")).isNotEqualTo(a.codeHash("RELINK", "ABCD2345"));
    }

    @Test
    void withoutItsOwnSecret_aDevelopmentKeyDerivedFromTheJwtSecretIsUsed_differentFromAnyOwnSecret() {
        assertThat(keys(null).rollKey("MIET", "E1236003")).isEqualTo(keys("").rollKey("MIET", "E1236003"))
                .isNotEqualTo(keys(JWT).rollKey("MIET", "E1236003"));
    }

    @Test
    void photos_roundTrip_andAnyChangeOrAnotherNameOrKeyFails() {
        CommunityKeys a = keys("community-secret-A-0123456789abcdef");
        byte[] plain = "a jpeg".getBytes(StandardCharsets.UTF_8);
        String name = "0b8f3b7e-2c1a-4a8e-9a55-1c2d3e4f5a6b";
        byte[] sealed = a.encrypt(plain, name);
        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("a jpeg");
        assertThat(a.encrypt(plain, name)).as("a fresh nonce each time").isNotEqualTo(sealed);
        assertThat(a.decrypt(sealed, name)).isEqualTo(plain);
        assertThatThrownBy(() -> a.decrypt(sealed, "1b8f3b7e-2c1a-4a8e-9a55-1c2d3e4f5a6b"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> keys("community-secret-B-0123456789abcdef").decrypt(sealed, name))
                .isInstanceOf(IllegalStateException.class);
        byte[] changed = sealed.clone();
        changed[changed.length - 1] ^= 1;
        assertThatThrownBy(() -> a.decrypt(changed, name)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aShortSecretIsRefused_andProductionNeedsItsOwnSecretAndAnAbsoluteFolder() {
        assertThatThrownBy(() -> keys("too-short")).isInstanceOf(IllegalStateException.class);
        assertThat(ProductionGuard.communityProblems(null, JWT, ABSOLUTE)).hasSize(1);
        assertThat(ProductionGuard.communityProblems(JWT, JWT, ABSOLUTE)).hasSize(1);
        assertThat(ProductionGuard.communityProblems("community-secret-A-0123456789abcdef", JWT, Path.of("id")))
                .hasSize(1);
        assertThat(ProductionGuard.communityProblems("community-secret-A-0123456789abcdef", JWT,
                ABSOLUTE)).isEmpty();
    }
}
