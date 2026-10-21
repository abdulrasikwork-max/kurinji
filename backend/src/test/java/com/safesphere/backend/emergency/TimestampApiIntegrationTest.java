package com.safesphere.backend.emergency;

import static com.safesphere.backend.support.TestAccounts.bearer;
import static com.safesphere.backend.support.TestAccounts.registerAndGetToken;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.emergency.repository.EmergencyEventRepository;
import com.safesphere.backend.safetycontact.repository.SafetyContactRepository;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regression tests for the UTC timestamp convention (docs/TIMESTAMPS.md). The surefire JVM runs in
 * Asia/Kolkata on purpose, so any hidden dependence on the machine's zone shows up here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TimestampApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private SafetyContactRepository contactRepository;
    @Autowired private EmergencyEventRepository emergencyRepository;
    @Autowired private JdbcTemplate jdbc;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        contactRepository.deleteAll();
        emergencyRepository.deleteAll();
        userRepository.deleteAll();
        token = registerAndGetToken(mockMvc, objectMapper, "time.user@example.com");
    }

    @Test
    void apiTimestampsAreIsoUtcWithZ_andMatchRealUtcNow() throws Exception {
        Instant before = Instant.now();
        JsonNode started = json(post("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"triggerType\":\"MANUAL\"}"));
        Instant after = Instant.now();

        String startedAt = started.get("startedAt").asText();
        assertTrue(startedAt.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z"), startedAt);
        Instant parsed = Instant.parse(startedAt);
        // A 5 h 30 m error (or any zone error) would be far outside this window.
        assertTrue(!parsed.isBefore(before.minusSeconds(2)) && !parsed.isAfter(after.plusSeconds(2)),
                "startedAt " + parsed + " is not within the test run " + before + " .. " + after);
    }

    @Test
    void storedColumnHoldsTheUtcWallClock_notTheMachinesLocalTime() throws Exception {
        JsonNode started = json(post("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"triggerType\":\"MANUAL\"}"));
        long id = started.get("id").asLong();
        Instant apiInstant = Instant.parse(started.get("startedAt").asText());

        // Read the raw column as text so no JDBC time-zone conversion is involved.
        String raw = jdbc.queryForObject("SELECT CAST(started_at AS VARCHAR(40)) FROM emergency_events WHERE id = ?",
                String.class, id);
        LocalDateTime stored = LocalDateTime.parse(raw.replace(' ', 'T'));

        assertEquals(LocalDateTime.ofInstant(apiInstant, ZoneOffset.UTC), stored,
                "the column must hold the UTC wall clock that the API reports");
        LocalDateTime machineLocalNow = LocalDateTime.now();
        assertTrue(Duration.between(stored, machineLocalNow).abs().toHours() >= 5,
                "this suite runs in Asia/Kolkata, so a stored machine-local value would be 5 h 30 m away");
    }

    @Test
    void endedAtIsAfterStartedAt_andBothStayUtc() throws Exception {
        JsonNode started = json(post("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"triggerType\":\"MANUAL\"}"));
        long id = started.get("id").asLong();

        JsonNode ended = json(put("/api/emergencies/" + id + "/resolve").header(HttpHeaders.AUTHORIZATION, bearer(token)));

        Instant startedAt = Instant.parse(ended.get("startedAt").asText());
        Instant endedAt = Instant.parse(ended.get("endedAt").asText());
        assertTrue(!endedAt.isBefore(startedAt), "ended " + endedAt + " before started " + startedAt);
        assertTrue(Duration.between(startedAt, endedAt).toMinutes() < 1);

        String rawEnded = jdbc.queryForObject("SELECT CAST(ended_at AS VARCHAR(40)) FROM emergency_events WHERE id = ?",
                String.class, id);
        assertEquals(LocalDateTime.ofInstant(endedAt, ZoneOffset.UTC), LocalDateTime.parse(rawEnded.replace(' ', 'T')));
    }

    @Test
    void errorBodyTimestampIsIsoUtcWithZ() throws Exception {
        String body = mockMvc.perform(get("/api/emergencies/999999").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        String timestamp = objectMapper.readTree(body).get("timestamp").asText();
        assertTrue(timestamp.endsWith("Z"), timestamp);
        assertTrue(Math.abs(Duration.between(Instant.parse(timestamp), Instant.now()).toSeconds()) < 10, timestamp);
    }

    private JsonNode json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }
}
