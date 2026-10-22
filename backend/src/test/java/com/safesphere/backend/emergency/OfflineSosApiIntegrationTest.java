package com.safesphere.backend.emergency;

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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Phase 11 "SOS without internet": the phone starts the SOS by itself and records it when the server can be reached.
 * The same reference always means the same emergency; phone times are kept but clamped; an SOS that also ended offline
 * is stored as ended and can still receive the locations the phone collected.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OfflineSosApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void theSameReference_alwaysAnswersWithTheSameEmergency() throws Exception {
        String token = token();
        String ref = ref();
        Instant started = Instant.now().minus(Duration.ofMinutes(40)).truncatedTo(ChronoUnit.MILLIS);
        JsonNode first = json(create(token, body(ref, started, null, null)).andExpect(status().isCreated()));
        JsonNode again = json(create(token, body(ref, started, null, null)).andExpect(status().isCreated()));
        assertThat(again.get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM emergency_events WHERE client_ref = ?", Integer.class, ref))
                .isEqualTo(1);
        // A first attempt WITHOUT the phone time (sent while online, answer lost) and a retry with it: still one.
        String other = token();
        String online = ref();
        long id = json(create(other, body(online, null, null, null)).andExpect(status().isCreated())).get("id").asLong();
        JsonNode retry = json(create(other, body(online, started, started.plusSeconds(60), "RESOLVED"))
                .andExpect(status().isCreated()));
        assertThat(retry.get("id").asLong()).isEqualTo(id);
        assertThat(retry.get("status").asText()).isEqualTo("ACTIVE"); // stored as it was; the phone then ends it
        assertThat(retry.get("startedOffline").asBoolean()).isFalse();
    }

    @Test
    void anOfflineStart_keepsThePhoneTime_andSaysSo() throws Exception {
        String token = token();
        Instant started = Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.MILLIS);
        create(token, body(ref(), started, null, null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.startedOffline").value(true))
                .andExpect(jsonPath("$.startedAt").value(started.toString()))
                .andExpect(jsonPath("$.clientRef").doesNotExist());
        // The online start (no phone time) is not marked.
        String other = token();
        create(other, Map.of("clientRef", ref())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.startedOffline").value(false));
        mockMvc.perform(post("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(token())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.startedOffline").value(false));
    }

    @Test
    void phoneTimes_areClamped_neverInTheFuture_andAtMostADayBack() throws Exception {
        Instant before = Instant.now();
        JsonNode future = json(create(token(), body(ref(), Instant.now().plus(Duration.ofHours(3)), null, null))
                .andExpect(status().isCreated()));
        assertThat(Instant.parse(future.get("startedAt").asText())).isBetween(before.minusSeconds(1), Instant.now());

        JsonNode old = json(create(token(), body(ref(), Instant.now().minus(Duration.ofDays(3)), null, null))
                .andExpect(status().isCreated()));
        Instant oldStart = Instant.parse(old.get("startedAt").asText());
        assertThat(oldStart).isBetween(before.minus(Duration.ofHours(24)).minusSeconds(1),
                Instant.now().minus(Duration.ofHours(24)).plusSeconds(1));

        // An end before the start becomes the start; an end in the future becomes now.
        Instant start = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MILLIS);
        JsonNode backwards = json(create(token(), body(ref(), start, start.minus(Duration.ofHours(5)), "RESOLVED"))
                .andExpect(status().isCreated()));
        assertThat(backwards.get("endedAt").asText()).isEqualTo(backwards.get("startedAt").asText());
        JsonNode ahead = json(create(token(), body(ref(), start, Instant.now().plus(Duration.ofDays(1)), "CANCELLED"))
                .andExpect(status().isCreated()));
        assertThat(Instant.parse(ahead.get("endedAt").asText())).isBefore(Instant.now().plusSeconds(1));
    }

    @Test
    void anSosThatEndedOffline_isStoredEnded_andDoesNotBlockANewOne() throws Exception {
        String token = token();
        Instant start = Instant.now().minus(Duration.ofMinutes(50)).truncatedTo(ChronoUnit.MILLIS);
        Instant end = start.plus(Duration.ofMinutes(20));
        JsonNode ended = json(create(token, body(ref(), start, end, "RESOLVED")).andExpect(status().isCreated()));
        assertThat(ended.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(ended.get("endedAt").asText()).isEqualTo(end.toString());
        assertThat(jdbc.queryForObject("SELECT started_session FROM emergency_events WHERE id = ?", String.class,
                ended.get("id").asLong())).isNull();
        // Ended rows never count as "active": a new SOS starts normally.
        mockMvc.perform(post("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isCreated());
        // And with an SOS ACTIVE, another ended-offline one can still be recorded, but a second ACTIVE one cannot.
        create(token, body(ref(), start, end, "CANCELLED")).andExpect(status().isCreated());
        create(token, body(ref(), start, null, null)).andExpect(status().isConflict());
    }

    @Test
    void theLocationsOfAnSosThatEndedOffline_areAcceptedOnceItReachesTheServer() throws Exception {
        String token = token();
        Instant start = Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.MILLIS);
        Instant end = start.plus(Duration.ofMinutes(30));
        long id = json(create(token, body(ref(), start, end, "RESOLVED")).andExpect(status().isCreated())).get("id").asLong();
        // Ended 90 minutes ago, but the server heard of it just now: the 15-minute grace counts from now.
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("latitude", 12.97);
        point.put("longitude", 77.59);
        point.put("accuracyMeters", 12);
        point.put("source", "FUSED");
        point.put("recordedAt", start.plus(Duration.ofMinutes(3)).toString());
        mockMvc.perform(post("/api/emergencies/" + id + "/locations").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("points", List.of(point)))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.stored").value(1));
        // A point after the (phone's) end is still refused.
        point.put("recordedAt", end.plus(Duration.ofMinutes(30)).toString());
        mockMvc.perform(post("/api/emergencies/" + id + "/locations").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("points", List.of(point)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void endingWithThePhoneTime_keepsIt_withinTheStartAndNow() throws Exception {
        String token = token();
        Instant start = Instant.now().minus(Duration.ofMinutes(30)).truncatedTo(ChronoUnit.MILLIS);
        long id = json(create(token, body(ref(), start, null, null)).andExpect(status().isCreated())).get("id").asLong();
        Instant end = start.plus(Duration.ofMinutes(12));
        mockMvc.perform(put("/api/emergencies/" + id + "/resolve").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("endedAt", end.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.endedAt").value(end.toString()));

        long second = json(create(token, body(ref(), start, null, null)).andExpect(status().isCreated())).get("id").asLong();
        JsonNode cancelled = json(mockMvc.perform(put("/api/emergencies/" + second + "/cancel")
                .header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("endedAt", start.minus(Duration.ofDays(2)).toString()))))
                .andExpect(status().isOk()));
        assertThat(cancelled.get("endedAt").asText()).isEqualTo(cancelled.get("startedAt").asText());
    }

    @Test
    void invalidOfflineRequests_areRefused() throws Exception {
        String token = token();
        Instant start = Instant.now().minus(Duration.ofMinutes(5));
        Map<String, Object> noRef = new LinkedHashMap<>();
        noRef.put("startedAt", start.toString());
        create(token, noRef).andExpect(status().isBadRequest());
        create(token, Map.of("clientRef", ref().toUpperCase())).andExpect(status().isBadRequest());
        create(token, Map.of("clientRef", "not-a-uuid")).andExpect(status().isBadRequest());
        create(token, body(ref(), start, Instant.now(), "ACTIVE")).andExpect(status().isBadRequest());
        create(token, body(ref(), start, null, "RESOLVED")).andExpect(status().isBadRequest());
        create(token, body(ref(), start, Instant.now(), null)).andExpect(status().isBadRequest());
        Map<String, Object> onlineEnded = new LinkedHashMap<>();
        onlineEnded.put("clientRef", ref());
        onlineEnded.put("endedAt", Instant.now().toString());
        onlineEnded.put("outcome", "RESOLVED");
        create(token, onlineEnded).andExpect(status().isBadRequest());
        create(token, Map.of("clientRef", ref(), "triggerType", "DURESS_PIN")).andExpect(status().isBadRequest());
        assertThat(history(token)).isEmpty();
    }

    @Test
    void references_belongToOnePerson_andAnotherPersonsReferenceRevealsNothing() throws Exception {
        String alice = token();
        String bob = token();
        String ref = ref();
        Instant start = Instant.now().minus(Duration.ofMinutes(10)).truncatedTo(ChronoUnit.MILLIS);
        long aliceId = json(create(alice, body(ref, start, null, null)).andExpect(status().isCreated())).get("id").asLong();
        // Bob sending Alice's reference gets his OWN new emergency, never hers.
        long bobId = json(create(bob, body(ref, start, null, null)).andExpect(status().isCreated())).get("id").asLong();
        assertThat(bobId).isNotEqualTo(aliceId);
        assertThat(history(bob)).hasSize(1);
        assertThat(history(alice)).hasSize(1);
        mockMvc.perform(get("/api/emergencies/" + aliceId).header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(status().isNotFound());
    }

    @Test
    void anOfflineSos_endsTheWalkThatWasRunning_butNotOneStartedAfterIt() throws Exception {
        String token = token();
        long walk = startWalk(token);
        // Dated 2 hours back: the walk started after this SOS, so the late record must not end it.
        create(token, body(ref(), Instant.now().minus(Duration.ofHours(2)), Instant.now().minus(Duration.ofHours(1)), "RESOLVED"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.journeyId").doesNotExist());
        mockMvc.perform(get("/api/journeys/" + walk).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Started offline DURING the walk: the walk becomes the emergency (level 3), as with an online SOS.
        create(token, body(ref(), Instant.now(), null, null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.journeyId").value(walk));
        mockMvc.perform(get("/api/journeys/" + walk).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.status").value("EMERGENCY"));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private ResultActions create(String token, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private static Map<String, Object> body(String ref, Instant startedAt, Instant endedAt, String outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientRef", ref);
        if (startedAt != null) body.put("startedAt", startedAt.toString());
        if (endedAt != null) body.put("endedAt", endedAt.toString());
        if (outcome != null) body.put("outcome", outcome);
        return body;
    }

    private long startWalk(String token) throws Exception {
        return json(mockMvc.perform(post("/api/journeys").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("checkInIntervalMinutes", 10))))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private List<JsonNode> history(String token) throws Exception {
        JsonNode list = json(mockMvc.perform(get("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk()));
        return java.util.stream.StreamSupport.stream(list.spliterator(), false).toList();
    }

    private String token() throws Exception {
        return registerAndGetToken(mockMvc, objectMapper, "offline." + UUID.randomUUID().toString().substring(0, 12) + "@example.com");
    }

    private static String ref() {
        return UUID.randomUUID().toString();
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
