package com.safesphere.backend.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.safesphere.backend.security.ActiveAccountCheck;
import com.safesphere.backend.user.repository.UserRepository;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Creates the two JWT tools from one secret key: an encoder (signs new tokens at login) and a
 * decoder (verifies the signature, expiry and issuer of every incoming token).
 * Algorithm: HS256 (HMAC with SHA-256), a symmetric key that only the server knows.
 */
@Configuration
public class JwtConfig {

    private static final int MIN_SECRET_BYTES = 32;

    @Bean
    public SecretKey jwtSecretKey(JwtProperties properties) {
        String secret = properties.secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            // Fail at startup rather than run with a weak key.
            throw new IllegalStateException(
                    "JWT_SECRET must be set and at least " + MIN_SECRET_BYTES + " characters long");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey, JwtProperties properties, UserRepository users) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // Default validators check expiry ("exp") and "nbf"; the issuer validator checks "iss". They run first, so the
        // database is only asked about tokens this server really signed and that have not expired.
        // Phase 10A: then the account check (exists, enabled, not erased, credentials stamp current).
        OAuth2TokenValidator<Jwt> standard = JwtValidators.createDefaultWithIssuer(properties.issuer());
        ActiveAccountCheck account = new ActiveAccountCheck(users);
        // In order, not side by side (DelegatingOAuth2TokenValidator would run both): an expired token never reaches
        // the database, and an expired token of a deleted account is "expired", not "deleted".
        decoder.setJwtValidator(token -> {
            OAuth2TokenValidatorResult first = standard.validate(token);
            return first.hasErrors() ? first : account.validate(token);
        });
        return decoder;
    }
}
