package com.safesphere.backend.social;

import static com.safesphere.backend.support.TestAccounts.PASSWORD;
import static com.safesphere.backend.support.TestAccounts.bearer;
import static com.safesphere.backend.support.TestAccounts.registerAndGetToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Phase 13A (D6) through the real API: registration needs an adult date of birth, the date is never stored or echoed,
 * and accounts created before V20 confirm once.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class AgeGateApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void underEighteen_isRefused_andNoAccountIsCreated() throws Exception {
        String email = email();
        register(email, today().minusYears(18).plusDays(1).toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You must be 18 or older to use this app"));
        assertThat(users.findByEmail(email)).isEmpty();
    }

    @Test
    void eighteenthBirthdayToday_isAccepted_andTheAccountIsConfirmed() throws Exception {
        register(email(), today().minusYears(18).toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.ageConfirmed").value(true));
    }

    @Test
    void futureDates_absurdAges_missingAndMalformedDates_areRefused() throws Exception {
        register(email(), today().plusDays(1).toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Enter a valid date of birth"));
        register(email(), today().minusYears(121).toString()).andExpect(status().isBadRequest());
        register(email(), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.dateOfBirth").value("Date of birth is required"));
        register(email(), "22-10-1998").andExpect(status().isBadRequest());
    }

    @Test
    void theDateOfBirthIsNeitherStoredNorEchoed() throws Exception {
        String date = "1997-03-09";
        String body = register(email(), date).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(date).doesNotContain("dateOfBirth");
        Integer birthColumns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE LOWER(COLUMN_NAME) LIKE '%birth%'", Integer.class);
        assertThat(birthColumns).isZero();
    }

    @Test
    void anAccountFromBeforeV20_confirmsOnce_andKeepsItsFirstConfirmationTime() throws Exception {
        String email = email();
        String token = registerAndGetToken(mockMvc, objectMapper, email);
        jdbc.update("UPDATE users SET age_confirmed_at = NULL WHERE email = ?", email);
        me(token).andExpect(jsonPath("$.ageConfirmed").value(false));

        confirm(token, today().minusYears(17).toString()).andExpect(status().isBadRequest());
        me(token).andExpect(jsonPath("$.ageConfirmed").value(false));

        confirm(token, "1990-01-01").andExpect(status().isNoContent());
        me(token).andExpect(jsonPath("$.ageConfirmed").value(true));
        LocalDateTime first = users.findByEmail(email).orElseThrow().getAgeConfirmedAt();

        clock.advance(java.time.Duration.ofDays(3));
        confirm(token, "1990-01-01").andExpect(status().isNoContent());
        assertThat(users.findByEmail(email).orElseThrow().getAgeConfirmedAt()).isEqualTo(first);
    }

    @Test
    void confirmation_needsASignedInCaller() throws Exception {
        mockMvc.perform(post("/api/users/me/age-confirmation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dateOfBirth\":\"1990-01-01\"}"))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions register(String email, String dateOfBirth) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("name", "Age Test", "email", email, "password", PASSWORD));
        if (dateOfBirth != null) body.put("dateOfBirth", dateOfBirth);
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions confirm(String token, String dateOfBirth) throws Exception {
        return mockMvc.perform(post("/api/users/me/age-confirmation").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("dateOfBirth", dateOfBirth))));
    }

    private ResultActions me(String token) throws Exception {
        return mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String email() {
        return "age." + UUID.randomUUID() + "@example.com";
    }
}
