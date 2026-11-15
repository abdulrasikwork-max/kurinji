package com.safesphere.backend.auth;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.safesphere.backend.support.TestAccounts;
import com.safesphere.backend.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end tests of the token side of the API: real controllers, real security filter chain, real JWT, an in-memory
 * database. Accounts are made through the real sign-up (Phase N2; the code flow itself is PhoneSignInApiIntegrationTest).
 * They cover the token a sign-up returns and every way a token is refused on a protected endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthApiIntegrationTest {

    private static final String ISSUER = "https://safesphere.example";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private JwtDecoder jwtDecoder;

    @Test
    void signUp_returnsAJwtWithTheUserIdAndExpiry_andNothingSecret() throws Exception {
        JsonNode session = signUp("Asha Devi");
        long userId = session.get("user").get("id").asLong();

        Jwt jwt = jwtDecoder.decode(session.get("accessToken").asText());
        assertEquals(String.valueOf(userId), jwt.getSubject());
        assertEquals(ISSUER, jwt.getIssuer().toString());
        assertTrue(jwt.getExpiresAt().isAfter(Instant.now()));
        for (String secret : new String[] {"password", "passwordHash", "phone", "phoneE164", "email"}) {
            assertFalse(jwt.getClaims().containsKey(secret), secret);
        }
    }

    @Test
    void me_withValidToken_returnsTheAccountWithoutSecrets() throws Exception {
        String token = signUp("Asha Devi").get("accessToken").asText();

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Asha Devi"))
                .andExpect(jsonPath("$.createdAt", not(emptyOrNullString())))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.phoneE164").doesNotExist());
    }

    @Test
    void me_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void me_withGarbageToken_returns401() throws Exception {
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_withExpiredToken_returns401() throws Exception {
        long userId = signUp("Asha Devi").get("user").get("id").asLong();
        Instant now = Instant.now();
        String expired = sign(jwtEncoder, userId, now.minus(2, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS));

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_withTokenSignedByAnotherKey_returns401() throws Exception {
        long userId = signUp("Asha Devi").get("user").get("id").asLong();
        byte[] otherKey = "a-completely-different-secret-key-0123456789".getBytes(StandardCharsets.UTF_8);
        JwtEncoder attackerEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(otherKey, "HmacSHA256")));
        Instant now = Instant.now();
        String forged = sign(attackerEncoder, userId, now, now.plus(1, ChronoUnit.HOURS));

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_withValidTokenOfDeletedUser_returns401() throws Exception {
        JsonNode session = signUp("Asha Devi");
        userRepository.deleteById(session.get("user").get("id").asLong());

        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.get("accessToken").asText()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownUrl_withoutToken_isRejectedBeforeRouting() throws Exception {
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode signUp(String name) throws Exception {
        return TestAccounts.registerVerifiedAndLogin(mockMvc, objectMapper, name,
                "auth." + UUID.randomUUID() + "@example.com");
    }

    private static String sign(JwtEncoder encoder, long userId, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(userId))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
