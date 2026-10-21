package com.safesphere.backend.account;

import static com.safesphere.backend.support.TestAccounts.PASSWORD;
import static com.safesphere.backend.support.TestAccounts.bearer;
import static com.safesphere.backend.support.TestAccounts.registerAndGetToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Phase 10A.4 "session continuity": token renewal (a long SOS never loses its server connection to an expiring token)
 * and the rule that only the sign-in that started an SOS ends it without the account password.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class SessionContinuityApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtEncoder jwtEncoder;

    @BeforeEach
    @AfterEach
    void resetClock() {
        clock.reset();
    }

    // ---- renewal --------------------------------------------------------------------------------------------

    @Test
    void renewal_keepsTheSignIn_andGivesAWorkingToken() throws Exception {
        String token = registerAndGetToken(mockMvc, objectMapper, email());
        JsonNode first = claims(token);
        assertThat(first.get("sid").asText()).matches("[0-9a-f-]{36}");
        assertThat(first.get("auth_time").asLong()).isPositive();

        String renewed = json(renew(token).andExpect(status().isOk())).get("accessToken").asText();
        JsonNode second = claims(renewed);
        assertThat(second.get("sid").asText()).isEqualTo(first.get("sid").asText());
        assertThat(second.get("auth_time").asLong()).isEqualTo(first.get("auth_time").asLong());
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(renewed))).andExpect(status().isOk());
        // The old token is not revoked by a renewal (it simply expires).
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk());
    }

    @Test
    void afterThirtyDays_renewalNeedsASignIn_unlessAnSosOrASafeWalkIsRunning() throws Exception {
        String token = registerAndGetToken(mockMvc, objectMapper, email());
        clock.advance(Duration.ofDays(31));
        renew(token).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Please sign in again on this phone."));
        // The current token keeps working (nothing is signed out by a refused renewal).
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk());

        long emergency = startEmergency(token);
        String duringSos = json(renew(token).andExpect(status().isOk())).get("accessToken").asText();
        endWith(duringSos, emergency, "resolve", null).andExpect(status().isOk()); // same sign-in: no password

        Map<String, Object> walk = new LinkedHashMap<>();
        walk.put("checkInIntervalMinutes", 10);
        mockMvc.perform(post("/api/journeys").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(walk)))
                .andExpect(status().isCreated());
        renew(token).andExpect(status().isOk());
    }

    @Test
    void aRevokedToken_cannotBeRenewed() throws Exception {
        String email = email();
        String token = registerAndGetToken(mockMvc, objectMapper, email);
        String other = json(mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD)))))
                .get("accessToken").asText();
        String changed = json(mockMvc.perform(put("/api/users/me/password").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("currentPassword", PASSWORD, "newPassword", "another-long-one"))))
                .andExpect(status().isOk())).get("accessToken").asText();
        renew(other).andExpect(status().isUnauthorized());
        // The phone that changed the password keeps its sign-in (same session id).
        assertThat(claims(changed).get("sid").asText()).isEqualTo(claims(token).get("sid").asText());
        assertThat(claims(other).get("sid").asText()).isNotEqualTo(claims(token).get("sid").asText());
    }

    // ---- who may end an ACTIVE SOS ----------------------------------------------------------------------------

    @Test
    void anotherSignIn_needsThePasswordToEndAnSos_andTheRecordSaysItWasEndedElsewhere() throws Exception {
        String email = email();
        String phone = registerAndGetToken(mockMvc, objectMapper, email);
        String elsewhere = login(email);
        long emergency = startEmergency(phone);
        assertThat(jdbc.queryForObject("SELECT started_session FROM emergency_events WHERE id = ?", String.class, emergency))
                .isEqualTo(claims(phone).get("sid").asText());

        endWith(elsewhere, emergency, "resolve", null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This SOS was started on another phone. Enter your account password to end it here."));
        endWith(elsewhere, emergency, "cancel", "wrong-password").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Password is incorrect"));
        mockMvc.perform(get("/api/emergencies/" + emergency).header(HttpHeaders.AUTHORIZATION, bearer(phone)))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        endWith(elsewhere, emergency, "resolve", PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.endedElsewhere").value(true));
        // The first phone sees it; the session id is gone from the row (only needed while ACTIVE).
        mockMvc.perform(get("/api/emergencies/" + emergency).header(HttpHeaders.AUTHORIZATION, bearer(phone)))
                .andExpect(jsonPath("$.endedElsewhere").value(true))
                .andExpect(jsonPath("$.startedSession").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT started_session FROM emergency_events WHERE id = ?", String.class, emergency))
                .isNull();
    }

    @Test
    void theStartingPhone_endsItsSosWithoutAPassword_alsoWithARenewedToken() throws Exception {
        String phone = registerAndGetToken(mockMvc, objectMapper, email());
        long emergency = startEmergency(phone);
        String renewed = json(renew(phone).andExpect(status().isOk())).get("accessToken").asText();
        endWith(renewed, emergency, "cancel", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.endedElsewhere").value(false));
    }

    @Test
    void wrongPasswordsForEndingAnSos_shareTheAccountLimit_andOtherPeopleStill404() throws Exception {
        String email = email();
        String phone = registerAndGetToken(mockMvc, objectMapper, email);
        String elsewhere = login(email);
        long emergency = startEmergency(phone);
        for (int i = 0; i < 5; i++) endWith(elsewhere, emergency, "resolve", "wrong-" + i).andExpect(status().isConflict());
        endWith(elsewhere, emergency, "resolve", PASSWORD).andExpect(status().isTooManyRequests());
        // The phone that started it is not blocked by the limit (no password needed there).
        String stranger = registerAndGetToken(mockMvc, objectMapper, email());
        endWith(stranger, emergency, "resolve", PASSWORD).andExpect(status().isNotFound());
        endWith(phone, emergency, "resolve", null).andExpect(status().isOk());
    }

    @Test
    void anSosStartedByAnOldTokenWithoutASessionId_keepsTheOldRule() throws Exception {
        String email = email();
        String phone = registerAndGetToken(mockMvc, objectMapper, email);
        long userId = claims(phone).get("sub").asLong();
        Instant now = Instant.now();
        String legacy = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().issuer("https://safesphere.example").issuedAt(now).expiresAt(now.plusSeconds(600))
                        .subject(String.valueOf(userId)).build())).getTokenValue();
        long emergency = startEmergency(legacy);
        assertThat(jdbc.queryForObject("SELECT started_session FROM emergency_events WHERE id = ?", String.class, emergency))
                .isNull();
        endWith(login(email), emergency, "resolve", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.endedElsewhere").value(false));
        // A legacy token can renew into a token WITH a session id.
        assertThat(claims(json(renew(legacy).andExpect(status().isOk())).get("accessToken").asText()).get("sid").asText())
                .matches("[0-9a-f-]{36}");
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private static String email() {
        return "session." + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
    }

    private String login(String email) throws Exception {
        return json(mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())).get("accessToken").asText();
    }

    private ResultActions renew(String token) throws Exception {
        return mockMvc.perform(post("/api/users/me/token").header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    private long startEmergency(String token) throws Exception {
        return json(mockMvc.perform(post("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private ResultActions endWith(String token, long emergency, String action, String password) throws Exception {
        var request = put("/api/emergencies/" + emergency + "/" + action).header(HttpHeaders.AUTHORIZATION, bearer(token));
        if (password != null) {
            request = request.contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("password", password)));
        }
        return mockMvc.perform(request);
    }

    private JsonNode claims(String token) throws Exception {
        String payload = token.split("\\.")[1];
        return objectMapper.readTree(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
