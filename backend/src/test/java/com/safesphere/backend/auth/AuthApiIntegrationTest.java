package com.safesphere.backend.auth;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end tests of the HTTP API: real controllers, real security filter chain, real BCrypt and JWT,
 * an in-memory database. They cover registration, hashing, login, JWT checks and the protected endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthApiIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ISSUER = "https://safesphere.example";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private JwtDecoder jwtDecoder;

    @BeforeEach
    void cleanDatabase() {
        userRepository.deleteAll();
    }

    // ---------------------------------------------------------------- registration

    /** V25: sign-up creates an unverified account and starts NO session (no token of any kind in the answer). */
    @Test
    void register_createsUnverifiedAccount_withoutAnySession() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("Asha Devi", "Asha@Example.com", PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VERIFICATION_REQUIRED"))
                .andExpect(jsonPath("$.email").value("asha@example.com"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.user").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        User saved = userRepository.findByEmail("asha@example.com").orElseThrow();
        assertFalse(saved.isEmailVerified());
    }

    @Test
    void register_storesBcryptHash_notThePlainPassword() throws Exception {
        register("Asha Devi", "asha@example.com", PASSWORD);

        User saved = userRepository.findByEmail("asha@example.com").orElseThrow();
        assertNotEquals(PASSWORD, saved.getPasswordHash());
        assertTrue(saved.getPasswordHash().startsWith("$2"), "expected a BCrypt hash");
        assertTrue(passwordEncoder.matches(PASSWORD, saved.getPasswordHash()));
        assertFalse(passwordEncoder.matches("some-other-password", saved.getPasswordHash()));
    }

    @Test
    void register_normalizesEmailToLowerCase() throws Exception {
        register("Asha Devi", "Asha@Example.COM", PASSWORD);

        assertTrue(userRepository.findByEmail("asha@example.com").isPresent());
    }

    @Test
    void register_duplicateEmail_returns409_evenWithDifferentCase() throws Exception {
        register("Asha Devi", "asha@example.com", PASSWORD);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("Other Person", "ASHA@example.com", PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("An account with this email already exists"));
    }

    @Test
    void register_invalidInput_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("", "not-an-email", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void register_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void register_passwordLongerThan72Bytes_returns400() throws Exception {
        // 72 characters passes @Size; 72 two-byte characters is 144 bytes, which BCrypt cannot handle.
        String tooManyBytes = "é".repeat(72);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("Asha Devi", "asha@example.com", tooManyBytes)))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- login

    @Test
    void login_withCorrectCredentials_returnsJwtWithUserIdAndExpiry() throws Exception {
        register("Asha Devi", "asha@example.com", PASSWORD);
        Long userId = userRepository.findByEmail("asha@example.com").orElseThrow().getId();

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("asha@example.com", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", not(emptyOrNullString())))
                .andExpect(jsonPath("$.user.email").value("asha@example.com"))
                .andReturn();

        String token = tokenFrom(result);
        Jwt jwt = jwtDecoder.decode(token);
        assertEquals(String.valueOf(userId), jwt.getSubject());
        assertEquals(ISSUER, jwt.getIssuer().toString());
        assertTrue(jwt.getExpiresAt().isAfter(Instant.now()));
        // The token must not carry anything sensitive.
        assertFalse(jwt.getClaims().containsKey("password"));
        assertFalse(jwt.getClaims().containsKey("passwordHash"));
    }

    @Test
    void login_isCaseInsensitiveForEmail() throws Exception {
        register("Asha Devi", "asha@example.com", PASSWORD);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("ASHA@Example.com", PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void login_wrongPassword_returns401_withGenericMessage() throws Exception {
        register("Asha Devi", "asha@example.com", PASSWORD);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("asha@example.com", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void login_unknownEmail_returnsExactlyTheSame401AsWrongPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("nobody@example.com", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void login_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    // ---------------------------------------------------------------- protected endpoint

    @Test
    void me_withValidToken_returnsProfileWithoutSecrets() throws Exception {
        String token = register("Asha Devi", "asha@example.com", PASSWORD);

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Asha Devi"))
                .andExpect(jsonPath("$.email").value("asha@example.com"))
                .andExpect(jsonPath("$.createdAt", not(emptyOrNullString())))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
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
        register("Asha Devi", "asha@example.com", PASSWORD);
        Long userId = userRepository.findByEmail("asha@example.com").orElseThrow().getId();
        Instant now = Instant.now();
        String expired = sign(jwtEncoder, userId, now.minus(2, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS));

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_withTokenSignedByAnotherKey_returns401() throws Exception {
        register("Asha Devi", "asha@example.com", PASSWORD);
        Long userId = userRepository.findByEmail("asha@example.com").orElseThrow().getId();
        byte[] otherKey = "a-completely-different-secret-key-0123456789".getBytes(StandardCharsets.UTF_8);
        JwtEncoder attackerEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(otherKey, "HmacSHA256")));
        Instant now = Instant.now();
        String forged = sign(attackerEncoder, userId, now, now.plus(1, ChronoUnit.HOURS));

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_withValidTokenOfDeletedUser_returns401() throws Exception {
        String token = register("Asha Devi", "asha@example.com", PASSWORD);
        userRepository.deleteAll();

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownUrl_withoutToken_isRejectedBeforeRouting() throws Exception {
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Registers a user through the real endpoint, marks the e-mail verified (V25; the link flow itself is in
     * EmailVerificationApiIntegrationTest), signs in and returns the JWT.
     */
    private String register(String name, String email, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(name, email, password)))
                .andExpect(status().isCreated());
        com.safesphere.backend.support.TestAccounts.markVerified(mockMvc, email);
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        return tokenFrom(result);
    }

    private String tokenFrom(MvcResult result) throws Exception {
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("accessToken").asText();
    }

    private String registerJson(String name, String email, String password) throws Exception {
        return objectMapper.writeValueAsString(
                java.util.Map.of("name", name, "email", email, "password", password,
                        "dateOfBirth", com.safesphere.backend.support.TestAccounts.ADULT_BIRTH_DATE));
    }

    private String loginJson(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of("email", email, "password", password));
    }

    private static String sign(JwtEncoder encoder, Long userId, Instant issuedAt, Instant expiresAt) {
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
