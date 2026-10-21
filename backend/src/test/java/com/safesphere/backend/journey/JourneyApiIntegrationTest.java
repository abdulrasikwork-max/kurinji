package com.safesphere.backend.journey;

import static com.safesphere.backend.support.TestAccounts.bearer;
import static com.safesphere.backend.support.TestAccounts.registerAndGetToken;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * End-to-end tests of /api/journeys (Phase 4B): Safe Walk start/complete/cancel, server-computed check-in
 * deadlines, the Escalation Ladder rules, last-known location only, SOS linking and two-user ownership.
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

    // ---- escalation ladder ---------------------------------------------------------------------

    @Test
    void missedCheckIn_cannotBeReportedBeforeTheServersOwnDeadline() throws Exception {
        long id = startId(tokenA, 30);
        escalate(tokenA, id, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The check-in is not due yet"));

        // Shortest interval (found in the live test): only 30 s of network delay is tolerated, not minutes.
        long shortWalk = startId(tokenB, 2);
        escalate(tokenB, shortWalk, 1).andExpect(status().isConflict());
        setDeadline(shortWalk, LocalDateTime.now(ZoneOffset.UTC).plusSeconds(10));
        escalate(tokenB, shortWalk, 1).andExpect(status().isOk());
    }

    @Test
    void escalation_goesOneStepAtATime_andRepeatsAreHarmless() throws Exception {
        long id = startId(tokenA, 10);
        setDeadline(id, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));

        escalate(tokenA, id, 2).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Escalation levels cannot be skipped"));
        escalate(tokenA, id, 1).andExpect(status().isOk()).andExpect(jsonPath("$.escalationLevel").value(1));
        escalate(tokenA, id, 1).andExpect(status().isOk()).andExpect(jsonPath("$.escalationLevel").value(1));
        escalate(tokenA, id, 2).andExpect(status().isOk()).andExpect(jsonPath("$.escalationLevel").value(2));
        escalate(tokenA, id, 1).andExpect(status().isOk()).andExpect(jsonPath("$.escalationLevel").value(2));

        List<EscalationEvent> events = escalationRepository.findByJourneyIdOrderByCreatedAtAscIdAsc(id);
        assertEquals(List.of(EscalationLevel.CHECK_IN_MISSED, EscalationLevel.NO_RESPONSE),
                events.stream().map(EscalationEvent::getLevel).toList());
        assertEquals(List.of(EscalationReason.CHECK_IN_MISSED, EscalationReason.NO_RESPONSE),
                events.stream().map(EscalationEvent::getReason).toList());
    }

    @Test
    void levels3To5_andUnknownLevels_areRejected() throws Exception {
        long id = startId(tokenA, 10);
        setDeadline(id, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5));

        escalate(tokenA, id, 3).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Level 3 is an emergency: start SOS to escalate"));
        escalate(tokenA, id, 4).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Level 4 is the emergency pathway: start SOS first"));
        escalate(tokenA, id, 5).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Verified Nearby Assistance is reserved and not available"));
        escalate(tokenA, id, 9).andExpect(status().isBadRequest());
        escalate(tokenA, id, -1).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/journeys/" + id + "/escalations").header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void imSafe_afterAnEscalation_returnsToNormal_andIsRecorded() throws Exception {
        long id = startId(tokenA, 10);
        setDeadline(id, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(3));
        escalate(tokenA, id, 1).andExpect(status().isOk());
        escalate(tokenA, id, 2).andExpect(status().isOk());

        checkIn(tokenA, id).andExpect(status().isOk()).andExpect(jsonPath("$.escalationLevel").value(0));

        List<EscalationEvent> events = escalationRepository.findByJourneyIdOrderByCreatedAtAscIdAsc(id);
        EscalationEvent last = events.get(events.size() - 1);
        assertEquals(EscalationLevel.NORMAL, last.getLevel());
        assertEquals(EscalationReason.CHECKED_IN, last.getReason());
        // Back at level 0 with a fresh deadline: a new "missed" report is refused until it passes again.
        escalate(tokenA, id, 1).andExpect(status().isConflict());
    }

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

    // ---- last known location only --------------------------------------------------------------

    @Test
    void location_onlyTheLatestIsKept_andOlderFixesAreIgnored() throws Exception {
        long id = startId(tokenA, 10);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        location(tokenA, id, 12.9716, 77.5946, now).andExpect(status().isNoContent());
        location(tokenA, id, 13.0, 78.0, now.minusSeconds(30)).andExpect(status().isNoContent()); // late, older: ignored
        location(tokenA, id, 12.98, 77.60, now.plusSeconds(30)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/journeys/" + id).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.lastLocation.latitude").value(12.98))
                .andExpect(jsonPath("$.lastLocation.longitude").value(77.6))
                .andExpect(jsonPath("$.lastLocation.recordedAt").value(now.plusSeconds(30).toString()));
    }

    @Test
    void location_isValidated_andRefusedAfterTheWalkEnded() throws Exception {
        long id = startId(tokenA, 10);
        Instant now = Instant.now();

        location(tokenA, id, 91, 0, now).andExpect(status().isBadRequest());
        location(tokenA, id, 0, 181, now).andExpect(status().isBadRequest());
        location(tokenA, id, 1, 1, now.plus(1, ChronoUnit.HOURS)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Location time cannot be in the future"));
        location(tokenA, id, 1, 1, now.minus(1, ChronoUnit.HOURS)).andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/journeys/" + id + "/complete").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)));
        location(tokenA, id, 1, 1, now).andExpect(status().isConflict());
        assertNull(journeyRepository.findById(id).orElseThrow().getLastLocationAt());
    }

    // ---- Phase 4C: destination and phone-detected concerns --------------------------------------

    @Test
    void start_withADestination_returnsItRounded_andHalfADestinationIsRejected() throws Exception {
        startWithDestination(tokenA, 12.97159949, 77.5946).andExpect(status().isCreated())
                .andExpect(jsonPath("$.destination.latitude").value(12.971599))
                .andExpect(jsonPath("$.destination.longitude").value(77.5946));

        Map<String, Object> half = new LinkedHashMap<>();
        half.put("checkInIntervalMinutes", 10);
        half.put("destinationLatitude", 12.97);
        mockMvc.perform(post("/api/journeys").header(HttpHeaders.AUTHORIZATION, bearer(tokenB))
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(half)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Destination latitude and longitude must be sent together"));
        startWithDestination(tokenB, 91, 0).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.destinationLatitude").exists());
        startWithDestination(tokenB, 0, -181).andExpect(status().isBadRequest());
        // A walk without a destination has none.
        start(tokenB, "Home", 10).andExpect(status().isCreated()).andExpect(jsonPath("$.destination").doesNotExist());
    }

    @Test
    void anUnexpectedStop_isLevel1BeforeTheDeadline_andNoResponseStillFollowsTheLadder() throws Exception {
        long id = startId(tokenA, 30); // deadline far away

        escalate(tokenA, id, 1, "UNEXPECTED_STOP").andExpect(status().isOk()).andExpect(jsonPath("$.escalationLevel").value(1));
        escalate(tokenA, id, 1, "UNEXPECTED_STOP").andExpect(status().isOk()); // re-send: harmless
        escalate(tokenA, id, 2, null).andExpect(status().isOk()).andExpect(jsonPath("$.escalationLevel").value(2));
        escalate(tokenA, id, 3, null).andExpect(status().isBadRequest()); // never to an emergency without SOS

        List<EscalationEvent> events = escalationRepository.findByJourneyIdOrderByCreatedAtAscIdAsc(id);
        assertEquals(List.of(EscalationReason.UNEXPECTED_STOP, EscalationReason.NO_RESPONSE),
                events.stream().map(EscalationEvent::getReason).toList());
        checkIn(tokenA, id).andExpect(jsonPath("$.escalationLevel").value(0));
    }

    @Test
    void aRouteDeviation_needsAWalkWithADestination() throws Exception {
        long withoutDestination = startId(tokenA, 30);
        escalate(tokenA, withoutDestination, 1, "ROUTE_DEVIATION").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A route check needs a Safe Walk with a destination"));

        long withDestination = json(startWithDestination(tokenB, 12.97, 77.59).andExpect(status().isCreated())).get("id").asLong();
        escalate(tokenB, withDestination, 1, "ROUTE_DEVIATION").andExpect(status().isOk())
                .andExpect(jsonPath("$.escalationLevel").value(1));
        assertEquals(EscalationReason.ROUTE_DEVIATION,
                escalationRepository.findByJourneyIdOrderByCreatedAtAscIdAsc(withDestination).get(0).getReason());
    }

    @Test
    void reasonsMustMatchTheirLevel_andUnknownReasonsAreRejected() throws Exception {
        long id = json(startWithDestination(tokenA, 12.97, 77.59)).get("id").asLong();

        escalate(tokenA, id, 1, "NO_RESPONSE").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This reason does not match the escalation level"));
        escalate(tokenA, id, 1, "CHECKED_IN").andExpect(status().isBadRequest());
        escalate(tokenA, id, 1, "SOS_CONFIRMED").andExpect(status().isBadRequest());
        escalate(tokenA, id, 1, "BOGUS").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown escalation reason"));
        // An explicit "missed" still waits for the server's own deadline.
        escalate(tokenA, id, 1, "CHECK_IN_MISSED").andExpect(status().isConflict());
        escalate(tokenA, id, 1, "ROUTE_DEVIATION").andExpect(status().isOk());
        escalate(tokenA, id, 2, "ROUTE_DEVIATION").andExpect(status().isBadRequest());
        escalate(tokenA, id, 2, "NO_RESPONSE").andExpect(status().isOk());
        assertTrue(escalationRepository.findByJourneyIdOrderByCreatedAtAscIdAsc(id).size() == 2);
    }

    @Test
    void aFinishedWalk_forgetsItsCoordinates_butAnEmergencyWalkKeepsThem() throws Exception {
        long finished = json(startWithDestination(tokenA, 12.97, 77.59)).get("id").asLong();
        location(tokenA, finished, 12.96, 77.58, Instant.now()).andExpect(status().isNoContent());
        mockMvc.perform(put("/api/journeys/" + finished + "/complete").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destination").doesNotExist())
                .andExpect(jsonPath("$.lastLocation").doesNotExist())
                .andExpect(jsonPath("$.destinationLabel").value("Station")); // the text the person typed stays
        assertNull(journeyRepository.findById(finished).orElseThrow().getDestinationLatitude());

        long emergencyWalk = json(startWithDestination(tokenA, 12.97, 77.59)).get("id").asLong();
        location(tokenA, emergencyWalk, 12.96, 77.58, Instant.now()).andExpect(status().isNoContent());
        mockMvc.perform(post("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/journeys/" + emergencyWalk).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.status").value("EMERGENCY"))
                .andExpect(jsonPath("$.destination.latitude").value(12.97))
                .andExpect(jsonPath("$.lastLocation.latitude").value(12.96));
    }

    @Test
    void userB_cannotReportAConcernOnUserAsWalk_orSeeItsDestination() throws Exception {
        long idOfA = json(startWithDestination(tokenA, 12.97, 77.59)).get("id").asLong();

        escalate(tokenB, idOfA, 1, "ROUTE_DEVIATION").andExpect(status().isNotFound());
        escalate(tokenB, idOfA, 1, "UNEXPECTED_STOP").andExpect(status().isNotFound());
        mockMvc.perform(get("/api/journeys/" + idOfA).header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.destination").doesNotExist());
        mockMvc.perform(get("/api/journeys/" + idOfA).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
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
        escalate(tokenB, idOfA, 1).andExpect(status().isNotFound());
        location(tokenB, idOfA, 1, 1, Instant.now()).andExpect(status().isNotFound());
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
        mockMvc.perform(post("/api/journeys/1/escalations")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/journeys/1/location")).andExpect(status().isUnauthorized());
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

    private ResultActions escalate(String token, long id, int level) throws Exception {
        return mockMvc.perform(post("/api/journeys/" + id + "/escalations").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"level\":" + level + "}"));
    }

    private ResultActions escalate(String token, long id, int level, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("level", level);
        body.put("reason", reason);
        return mockMvc.perform(post("/api/journeys/" + id + "/escalations").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions startWithDestination(String token, double lat, double lng) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("destinationLabel", "Station");
        body.put("checkInIntervalMinutes", 30);
        body.put("destinationLatitude", lat);
        body.put("destinationLongitude", lng);
        return mockMvc.perform(post("/api/journeys").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions location(String token, long id, double lat, double lng, Instant at) throws Exception {
        Map<String, Object> body = Map.of("latitude", lat, "longitude", lng, "accuracyMeters", 9.0, "recordedAt", at.toString());
        return mockMvc.perform(put("/api/journeys/" + id + "/location").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
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
