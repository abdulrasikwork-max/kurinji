package com.safesphere.backend.journey;

import static com.safesphere.backend.support.TestAccounts.bearer;
import static com.safesphere.backend.support.TestAccounts.registerAndGetToken;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.emergency.repository.EmergencyEventRepository;
import com.safesphere.backend.emergency.repository.EmergencyLocationRepository;
import com.safesphere.backend.escalation.entity.EscalationEvent;
import com.safesphere.backend.escalation.entity.EscalationLevel;
import com.safesphere.backend.escalation.entity.EscalationReason;
import com.safesphere.backend.escalation.repository.EscalationEventRepository;
import com.safesphere.backend.journey.entity.JourneyEventType;
import com.safesphere.backend.journey.repository.JourneyEventRepository;
import com.safesphere.backend.journey.repository.SafeJourneyRepository;
import com.safesphere.backend.safetycontact.repository.SafetyContactRepository;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
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
 * End-to-end tests of /api/journeys ("On my way", Phase 4B, trimmed in N6-B1): start / complete / cancel,
 * server-computed reminder deadlines, check-ins, SOS linking, no location of any kind, two-user ownership.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JourneyApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private SafetyContactRepository contactRepository;
    @Autowired private EmergencyEventRepository emergencyRepository;
    @Autowired private EmergencyLocationRepository locationRepository;
    @Autowired private SafeJourneyRepository journeyRepository;
    @Autowired private JourneyEventRepository journeyEventRepository;
    @Autowired private EscalationEventRepository escalationRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() throws Exception {
        escalationRepository.deleteAll();
        journeyEventRepository.deleteAll();
        locationRepository.deleteAll();
        emergencyRepository.deleteAll();
        journeyRepository.deleteAll();
        contactRepository.deleteAll();
        userRepository.deleteAll();
        tokenA = registerAndGetToken(mockMvc, objectMapper, "user.a@example.com");
        tokenB = registerAndGetToken(mockMvc, objectMapper, "user.b@example.com");
    }

    // ---- Safe Walk start / complete / cancel ---------------------------------------------------

    @Test
    void start_createsAnActiveWalk_withTheFirstDeadlineComputedByTheServer() throws Exception {
        JsonNode walk = json(start(tokenA, "  Home  ", 10).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.destinationLabel").value("Home"))
                .andExpect(jsonPath("$.checkInIntervalMinutes").value(10))
                .andExpect(jsonPath("$.escalationLevel").value(0))
                .andExpect(jsonPath("$.endedAt").doesNotExist())
                .andExpect(jsonPath("$.lastLocation").doesNotExist())
                .andExpect(jsonPath("$.serverTime").exists()));

        Instant startedAt = Instant.parse(walk.get("startedAt").asText());
        Instant due = Instant.parse(walk.get("nextCheckInDueAt").asText());
        assertEquals(Duration.ofMinutes(10), Duration.between(startedAt, due));
        assertEquals(List.of(JourneyEventType.STARTED), eventTypes(walk.get("id").asLong()));
    }

    @Test
    void start_validatesTheIntervalAndLabel_andABlankLabelBecomesNull() throws Exception {
        start(tokenA, null, 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.checkInIntervalMinutes").exists());
        start(tokenA, null, 61).andExpect(status().isBadRequest());
        start(tokenA, "x".repeat(101), 10).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/journeys").header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        start(tokenA, "   ", 2).andExpect(status().isCreated())
                .andExpect(jsonPath("$.destinationLabel").doesNotExist());
    }

    @Test
    void onlyOneActiveWalk_andNoWalkWhileAnEmergencyIsActive() throws Exception {
        start(tokenA, null, 10).andExpect(status().isCreated());
        start(tokenA, null, 10).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A Safe Walk is already active"));

        mockMvc.perform(post("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(status().isCreated());
        start(tokenB, null, 10).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("An emergency is active. End it before starting a Safe Walk"));
    }

    @Test
    void complete_endsTheWalk_onlyOnce_andANewOneCanStart() throws Exception {
        long id = startId(tokenA, 10);

        mockMvc.perform(put("/api/journeys/" + id + "/complete").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.endedAt").exists())
                .andExpect(jsonPath("$.nextCheckInDueAt").doesNotExist());
        mockMvc.perform(put("/api/journeys/" + id + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This Safe Walk has already ended"));
        checkIn(tokenA, id).andExpect(status().isConflict());

        start(tokenA, null, 10).andExpect(status().isCreated());
    }

    @Test
    void cancel_endsTheWalkAsCancelled_andHistoryIsNewestFirst() throws Exception {
        long first = startId(tokenA, 10);
        mockMvc.perform(put("/api/journeys/" + first + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        startId(tokenA, 5);

        mockMvc.perform(get("/api/journeys").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[1].status").value("CANCELLED"));
        assertEquals(List.of(JourneyEventType.STARTED, JourneyEventType.CANCELLED), eventTypes(first));
    }

    // ---- check-ins -----------------------------------------------------------------------------

    @Test
    void checkIn_success_movesTheDeadlineAnIntervalFromNow() throws Exception {
        long id = startId(tokenA, 10);
        setDeadline(id, LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));

        JsonNode walk = json(checkIn(tokenA, id).andExpect(status().isOk()));

        Instant due = Instant.parse(walk.get("nextCheckInDueAt").asText());
        Instant serverTime = Instant.parse(walk.get("serverTime").asText());
        long minutes = Duration.between(serverTime, due).toSeconds();
        assertTrue(minutes > 9 * 60 && minutes <= 10 * 60, "next deadline about 10 minutes away");
        assertEquals(List.of(JourneyEventType.STARTED, JourneyEventType.CHECKED_IN), eventTypes(id));
    }

    @Test
    void checkIn_bringsAWalkLeftOnAnOldLevelBackToNormal_andRecordsIt() throws Exception {
        // A phone from before N6 could leave a walk on level 1 or 2; "More time" still brings it back.
        long id = startId(tokenA, 10);
        jdbcTemplate.update("update safe_journeys set escalation_level = 2 where id = ?", id);

        checkIn(tokenA, id).andExpect(status().isOk()).andExpect(jsonPath("$.escalationLevel").value(0));

        List<EscalationEvent> events = escalationRepository.findByJourneyIdOrderByCreatedAtAscIdAsc(id);
        assertEquals(1, events.size());
        assertEquals(EscalationLevel.NORMAL, events.get(0).getLevel());
        assertEquals(EscalationReason.CHECKED_IN, events.get(0).getReason());
    }

    // ---- SOS during a walk ---------------------------------------------------------------------

    @Test
    void sosDuringAWalk_linksTheEmergency_endsTheWalk_andRecordsLevel3() throws Exception {
        long journeyId = startId(tokenA, 10);

        JsonNode emergency = json(mockMvc.perform(post("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.journeyId").value(journeyId)));
        long emergencyId = emergency.get("id").asLong();

        mockMvc.perform(get("/api/journeys/" + journeyId).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.status").value("EMERGENCY"))
                .andExpect(jsonPath("$.escalationLevel").value(3))
                .andExpect(jsonPath("$.nextCheckInDueAt").doesNotExist());
        EscalationEvent sos = escalationRepository.findByEmergencyIdOrderByCreatedAtAscIdAsc(emergencyId).get(0);
        assertEquals(journeyId, sos.getJourneyId());
        assertEquals(EscalationLevel.EMERGENCY, sos.getLevel());
        assertEquals(EscalationReason.SOS_CONFIRMED, sos.getReason());
        assertEquals(List.of(JourneyEventType.STARTED, JourneyEventType.EMERGENCY), eventTypes(journeyId));
    }

    @Test
    void aPlainSosWithoutAWalk_hasNoJourney() throws Exception {
        mockMvc.perform(post("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.journeyId").doesNotExist());
    }

    // ---- N6-B1: no location of any kind ----------------------------------------------------------

    @Test
    void oldDestinationCoordinates_areIgnored_andNoAnswerCarriesACoordinate() throws Exception {
        Map<String, Object> old = new LinkedHashMap<>();
        old.put("destinationLabel", "Station");
        old.put("checkInIntervalMinutes", 30);
        old.put("destinationLatitude", 12.97);
        old.put("destinationLongitude", 77.59);
        long id = json(mockMvc.perform(post("/api/journeys").header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(old)))
                .andExpect(status().isCreated())).get("id").asLong();

        JsonNode walk = json(mockMvc.perform(get("/api/journeys/" + id).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isOk()));
        Set<String> fields = new TreeSet<>();
        walk.fieldNames().forEachRemaining(fields::add);
        assertEquals(new TreeSet<>(List.of("id", "status", "destinationLabel", "checkInIntervalMinutes",
                "escalationLevel", "startedAt", "endedAt", "nextCheckInDueAt", "serverTime")), fields);
        assertEquals("Station", walk.get("destinationLabel").asText());
    }

    @Test
    void theWalksLocationEscalationAndAlertEndpoints_areGone() throws Exception {
        long id = startId(tokenA, 10);
        mockMvc.perform(put("/api/journeys/" + id + "/location").header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":1,\"longitude\":1,\"recordedAt\":\"" + Instant.now() + "\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/journeys/" + id + "/escalations").header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"level\":1}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/journeys/" + id + "/circle-alerts").header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reports\":[]}"))
                .andExpect(status().isNotFound());
        assertTrue(escalationRepository.findByJourneyIdOrderByCreatedAtAscIdAsc(id).isEmpty());
        mockMvc.perform(get("/api/journeys/" + id).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.escalationLevel").value(0));
    }

    // ---- ownership and authentication ----------------------------------------------------------

    @Test
    void userB_cannotSeeOrChangeUserAsWalk() throws Exception {
        long idOfA = startId(tokenA, 10);
        setDeadline(idOfA, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));

        mockMvc.perform(get("/api/journeys/" + idOfA).header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Safe Walk not found"));
        checkIn(tokenB, idOfA).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/journeys/" + idOfA + "/complete").header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/journeys/" + idOfA + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/journeys").header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(jsonPath("$", hasSize(0)));
        // B's SOS does not touch A's walk.
        mockMvc.perform(post("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(jsonPath("$.journeyId").doesNotExist());

        mockMvc.perform(get("/api/journeys/" + idOfA).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.escalationLevel").value(0));
    }

    @Test
    void everyJourneyEndpointRequiresAToken() throws Exception {
        mockMvc.perform(post("/api/journeys")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/journeys")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/journeys/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/journeys/1/check-ins")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/journeys/1/complete")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/journeys/1/cancel")).andExpect(status().isUnauthorized());
    }

    // ---- helpers -------------------------------------------------------------------------------

    private ResultActions start(String token, String label, int interval) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("destinationLabel", label);
        body.put("checkInIntervalMinutes", interval);
        return mockMvc.perform(post("/api/journeys").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private long startId(String token, int interval) throws Exception {
        return json(start(token, null, interval).andExpect(status().isCreated())).get("id").asLong();
    }

    private ResultActions checkIn(String token, long id) throws Exception {
        return mockMvc.perform(post("/api/journeys/" + id + "/check-ins").header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    /** Pretends time has passed: moves the stored deadline (the service compares it with the server clock). */
    private void setDeadline(long journeyId, LocalDateTime dueAt) {
        jdbcTemplate.update("update safe_journeys set next_check_in_due_at = ? where id = ?", dueAt, journeyId);
    }

    private List<JourneyEventType> eventTypes(long journeyId) {
        return journeyEventRepository.findByJourneyIdOrderByOccurredAtAscIdAsc(journeyId).stream()
                .map(e -> e.getType()).toList();
    }
}
