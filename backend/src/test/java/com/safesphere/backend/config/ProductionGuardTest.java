package com.safesphere.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Phase 17J: the prod profile refuses development conveniences, and only the prod profile. */
class ProductionGuardTest {

    private static final String GOOD_URL =
            "jdbc:mysql://db.internal:3306/safesphere?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC"
                    + "&forceConnectionTimeZoneToSession=true";
    private static final String SECRET = "x".repeat(40);
    private static final Path ABSOLUTE = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();

    private static List<String> check(String provider, String key, String secret, String url, Path evidence, Path media) {
        return ProductionGuard.problems(provider, key, secret, url, evidence, media);
    }

    @Test
    void a_complete_production_setup_passes() {
        assertThat(check("FAST2SMS", "key", SECRET, GOOD_URL, ABSOLUTE, ABSOLUTE)).isEmpty();
        assertThat(check("fast2sms", "key", SECRET, GOOD_URL.replace("VERIFY_IDENTITY", "REQUIRED"), ABSOLUTE, ABSOLUTE))
                .isEmpty();
    }

    @Test
    void the_development_otp_sink_is_refused() {
        assertThat(check("DEV_FILE", "", SECRET, GOOD_URL, ABSOLUTE, ABSOLUTE)).hasSize(1)
                .allMatch(m -> m.contains("OTP_PROVIDER"));
    }

    @Test
    void a_missing_sms_key_or_otp_secret_is_refused() {
        assertThat(check("FAST2SMS", " ", SECRET, GOOD_URL, ABSOLUTE, ABSOLUTE)).anyMatch(m -> m.contains("FAST2SMS_API_KEY"));
        assertThat(check("FAST2SMS", "key", "", GOOD_URL, ABSOLUTE, ABSOLUTE)).anyMatch(m -> m.contains("OTP_SECRET"));
        assertThat(check("FAST2SMS", "key", null, GOOD_URL, ABSOLUTE, ABSOLUTE)).anyMatch(m -> m.contains("OTP_SECRET"));
    }

    @Test
    void a_database_connection_without_tls_is_refused() {
        String dev = "jdbc:mysql://localhost:3306/safesphere?useSSL=false&allowPublicKeyRetrieval=true"
                + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        for (String url : new String[] {dev, "jdbc:mysql://db/safesphere", "jdbc:mysql://db/s?sslMode=DISABLED",
                "jdbc:mysql://db/s?sslMode=PREFERRED", "jdbc:mysql://db/s?useSSL=false&sslMode=REQUIRED", "", null}) {
            assertThat(check("FAST2SMS", "key", SECRET, url, ABSOLUTE, ABSOLUTE))
                    .as("url %s", url).anyMatch(m -> m.contains("DB_URL"));
        }
    }

    @Test
    void relative_storage_folders_are_refused() {
        List<String> problems = check("FAST2SMS", "key", SECRET, GOOD_URL, Path.of("evidence-store"),
                Path.of("social-media-store"));
        assertThat(problems).anyMatch(m -> m.contains("EVIDENCE_STORAGE_DIR"));
        assertThat(problems).anyMatch(m -> m.contains("SOCIAL_MEDIA_DIR"));
    }

    @Test
    void every_problem_is_reported_at_once() {
        assertThat(check("DEV_FILE", "", "", "", Path.of("a"), Path.of("b"))).hasSize(5);
    }

    @Test
    void the_guard_is_only_active_with_the_prod_profile() {
        Profile profile = ProductionGuard.class.getAnnotation(Profile.class);
        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("prod");
        assertThat(ProductionGuard.class.getAnnotation(Component.class)).isNotNull();
    }

    @Test
    void the_prod_properties_trust_no_forwarding_header_by_default_and_end_requests_gracefully() throws Exception {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("src/main/resources/application-prod.properties"))) {
            p.load(in);
        }
        assertThat(p.getProperty("server.forward-headers-strategy")).contains(":none}");
        assertThat(p.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(p.getProperty("server.error.include-stacktrace")).isEqualTo("never");
        assertThat(p.getProperty("spring.jpa.show-sql")).isEqualTo("false");
        // secrets never appear in the profile file
        assertThat(p.stringPropertyNames()).noneMatch(k -> k.toLowerCase().contains("password") || k.endsWith(".key"));
    }
}
