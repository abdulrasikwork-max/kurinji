package com.safesphere.backend.communication;

import static com.safesphere.backend.support.TestAccounts.bearer;
import static com.safesphere.backend.support.TestAccounts.registerAndGetToken;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.circlealert.repository.CircleAlertRepository;
import com.safesphere.backend.emergency.repository.EmergencyEventRepository;
import com.safesphere.backend.emergency.repository.EmergencyLocationRepository;
import com.safesphere.backend.escalation.entity.EscalationEvent;
import com.safesphere.backend.escalation.entity.EscalationLevel;
import com.safesphere.backend.escalation.entity.EscalationReason;
import com.safesphere.backend.escalation.repository.EscalationEventRepository;
import com.safesphere.backend.journey.repository.JourneyEventRepository;
import com.safesphere.backend.journey.repository.SafeJourneyRepository;
import com.safesphere.backend.safetycontact.repository.SafetyContactRepository;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Phase 4D end-to-end: the circle alert log (what the phone reports it told the Safety Circle), level 4 dial prompts,
 * and the Emergency Context Packet with its OWNER and SAFETY_CIRCLE allow-list projections. Two users throughout.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CommunicationApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private SafetyContactRepository contactRepository;
    @Autowired private EmergencyEventRepository emergencyRepository;
    @Autowired private EmergencyLocationRepository locationRepository;
    @Autowired private SafeJourneyRepository journeyRepository;
    @Autowired private JourneyEventRepository journeyEventRepository;
    @Autowired private EscalationEventRepository escalationRepository;
    @Autowired private CircleAlertRepository alertRepository;

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() throws Exception {
        alertRepository.deleteAll();
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

    // ---- circle alert log ----------------------------------------------------------------------

    @Test
    void emergencyAlerts_areStoredOnce_andTheStatusOnlyMovesForward() throws Exception {
        long emergency = startEmergency(tokenA);
        long contact = createContact(tokenA, "Ravi", "9876543210");
        String ref = UUID.randomUUID().toString();

        reportEmergency(tokenA, emergency, report(ref, contact, "SOS_STARTED", "SMS_AUTO", "SENT", Instant.now()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stored").value(1));
        reportEmergency(tokenA, emergency, report(ref, contact, "SOS_STARTED", "SMS_AUTO", "SENT", Instant.now()))
                .andExpect(jsonPath("$.stored").value(0)).andExpect(jsonPath("$.updated").value(0));
        reportEmergency(tokenA, emergency, report(ref, contact, "SOS_STARTED", "SMS_AUTO", "DELIVERED", Instant.now()))
                .andExpect(jsonPath("$.updated").value(1));
        // A late "sent" report can never overwrite "delivered".
        reportEmergency(tokenA, emergency, report(ref, contact, "SOS_STARTED", "SMS_AUTO", "SENT", Instant.now()))
                .andExpect(jsonPath("$.updated").value(0));

        context(tokenA, emergency, null)
                .andExpect(jsonPath("$.circleAlerts", hasSize(1)))
                .andExpect(jsonPath("$.circleAlerts[0].status").value("DELIVERED"))
                .andExpect(jsonPath("$.circleAlerts[0].contactName").value("Ravi"))
                .andExpect(jsonPath("$.circleAlerts[0].phoneNumber").doesNotExist());
    }

    @Test
    void alertKinds_mustFitTheirTarget_andTimesAreChecked() throws Exception {
        long emergency = startEmergency(tokenA);
        reportEmergency(tokenA, emergency, report(UUID.randomUUID().toString(), null, "WALK_NO_RESPONSE", "SMS_AUTO", "SENT", Instant.now()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This alert kind belongs to a Safe Walk, not an emergency"));
        reportEmergency(tokenA, emergency, report(UUID.randomUUID().toString(), null, "SOS_STARTED", "SMS_AUTO", "SENT",
                Instant.now().plus(1, ChronoUnit.HOURS)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Alert time cannot be in the future"));
        reportEmergency(tokenA, emergency, report(UUID.randomUUID().toString(), null, "SOS_STARTED", "SMS_AUTO", "SENT",
                Instant.now().minus(1, ChronoUnit.HOURS)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Alert time is before the start"));

        long journey = startJourney(tokenB);
        reportJourney(tokenB, journey, report(UUID.randomUUID().toString(), null, "SOS_STARTED", "SMS_AUTO", "SENT", Instant.now()))
                .andExpect(status().isBadRequest());
        reportJourney(tokenB, journey, report(UUID.randomUUID().toString(), null, "WALK_NO_RESPONSE", "SMS_COMPOSER",
                "COMPOSER_OPENED", Instant.now()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stored").value(1));
    }

    @Test
    void reports_areValidated_andABatchIsAllOrNothing() throws Exception {
        long emergency = startEmergency(tokenA);
        postJson("/api/emergencies/" + emergency + "/circle-alerts", tokenA, Map.of("reports", List.of()))
                .andExpect(status().isBadRequest());
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) tooMany.add(report(UUID.randomUUID().toString(), null, "SOS_STARTED", "SMS_AUTO", "SENT", Instant.now()));
        postJson("/api/emergencies/" + emergency + "/circle-alerts", tokenA, Map.of("reports", tooMany))
                .andExpect(status().isBadRequest());
        reportEmergency(tokenA, emergency, report("not-a-uuid", null, "SOS_STARTED", "SMS_AUTO", "SENT", Instant.now()))
                .andExpect(status().isBadRequest());
        reportEmergency(tokenA, emergency, report(UUID.randomUUID().toString(), null, "SOS_STARTED", "SMS_AUTO", "SHOUTED", Instant.now()))
                .andExpect(status().isBadRequest());

        // One bad report in a batch: nothing is stored.
        postJson("/api/emergencies/" + emergency + "/circle-alerts", tokenA, Map.of("reports", List.of(
                report(UUID.randomUUID().toString(), null, "SOS_STARTED", "SMS_AUTO", "SENT", Instant.now()),
                report(UUID.randomUUID().toString(), null, "ALL_CLEAR", "SMS_AUTO", "SENT", Instant.now()))))
                .andExpect(status().isBadRequest());
        assertEquals(0, alertRepository.countByEmergencyId(emergency));
    }

    @Test
    void anotherUsersContactId_isStoredAsUnknown_neverLinked() throws Exception {
        long contactOfB = createContact(tokenB, "Meera", "9123456780");
        long emergency = startEmergency(tokenA);

        reportEmergency(tokenA, emergency, report(UUID.randomUUID().toString(), contactOfB, "SOS_STARTED", "SMS_AUTO", "SENT", Instant.now()))
                .andExpect(status().isOk());

        assertEquals(null, alertRepository.findByEmergencyIdOrderByOccurredAtAscIdAsc(emergency).get(0).getContactId());
        context(tokenA, emergency, null).andExpect(jsonPath("$.circleAlerts[0].contactName").doesNotExist());
    }

    @Test
    void userB_cannotReportOnUserAsEmergencyOrWalk_andReferencesCannotMoveBetweenTargets() throws Exception {
        long emergencyOfA = startEmergency(tokenA);
        long journeyOfB = startJourney(tokenB);
        String ref = UUID.randomUUID().toString();

        reportEmergency(tokenB, emergencyOfA, report(ref, null, "SOS_STARTED", "SMS_AUTO", "SENT", Instant.now()))
                .andExpect(status().isNotFound());
        reportJourney(tokenA, journeyOfB, report(ref, null, "WALK_NO_RESPONSE", "SMS_AUTO", "SENT", Instant.now()))
                .andExpect(status().isNotFound());

        reportEmergency(tokenA, emergencyOfA, report(ref, null, "SOS_STARTED", "SMS_AUTO", "SENT", Instant.now()))
                .andExpect(status().isOk());
        reportJourney(tokenB, journeyOfB, report(ref, null, "WALK_NO_RESPONSE", "SMS_AUTO", "DELIVERED", Instant.now()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This alert reference is already used"));
    }

    @Test
    void anEmergencyKeepsAtMost200Alerts() throws Exception {
        long emergency = startEmergency(tokenA);
        for (int batch = 0; batch < 10; batch++) {
            List<Map<String, Object>> reports = new ArrayList<>();
            for (int i = 0; i < 20; i++) reports.add(report(UUID.randomUUID().toString(), null, "SOS_STARTED", "SMS_AUTO", "SENT", Instant.now()));
            postJson("/api/emergencies/" + emergency + "/circle-alerts", tokenA, Map.of("reports", reports)).andExpect(status().isOk());
        }
        reportEmergency(tokenA, emergency, report(UUID.randomUUID().toString(), null, "SOS_STARTED", "SMS_AUTO", "SENT", Instant.now()))
                .andExpect(status().isConflict());
    }

    // ---- level 4: dial prompt ------------------------------------------------------------------

    @Test
    void dialPrompt_recordsLevel4Once_onlyWhileActive_andOnlyForTheOwner() throws Exception {
        long emergency = startEmergency(tokenA);

        dialPrompt(tokenB, emergency).andExpect(status().isNotFound());
        dialPrompt(tokenA, emergency).andExpect(status().isNoContent());
        dialPrompt(tokenA, emergency).andExpect(status().isNoContent()); // repeat tap: no second row

        List<EscalationEvent> events = escalationRepository.findByEmergencyIdOrderByCreatedAtAscIdAsc(emergency);
        assertEquals(1, events.size());
        assertEquals(EscalationLevel.EMERGENCY_PATHWAY, events.get(0).getLevel());
        assertEquals(EscalationReason.DIAL_PROMPT_OPENED, events.get(0).getReason());
        context(tokenA, emergency, null).andExpect(jsonPath("$.highestLevel").value(4));

        mockMvc.perform(put("/api/emergencies/" + emergency + "/resolve").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isOk());
        dialPrompt(tokenA, emergency).andExpect(status().isConflict());
    }

    @Test
    void aSafeWalkStillCannotJumpToLevel4() throws Exception {
        long journey = startJourney(tokenA);
        postJson("/api/journeys/" + journey + "/escalations", tokenA, Map.of("level", 4))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Level 4 is the emergency pathway: start SOS first"));
    }

    // ---- Emergency Context Packet --------------------------------------------------------------

    @Test
    void ownerContext_assemblesTheWalkTheSosTheLocationAndTheAlerts() throws Exception {
        long journey = json(postJson("/api/journeys", tokenA, Map.of(
                "destinationLabel", "Station", "checkInIntervalMinutes", 30,
                "destinationLatitude", 12.97, "destinationLongitude", 77.59))).get("id").asLong();
        reportJourney(tokenA, journey, report(UUID.randomUUID().toString(), null, "WALK_NO_RESPONSE", "SMS_AUTO", "SENT", Instant.now()))
                .andExpect(status().isOk());
        long emergency = startEmergency(tokenA); // links the walk, records level 3
        uploadLocation(tokenA, emergency, 12.9716, 77.5946);
        reportEmergency(tokenA, emergency, report(UUID.randomUUID().toString(), null, "SOS_STARTED", "SMS_AUTO", "SENT", Instant.now()))
                .andExpect(status().isOk());

        context(tokenA, emergency, "owner")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audience").value("OWNER"))
                .andExpect(jsonPath("$.emergency.status").value("ACTIVE"))
                .andExpect(jsonPath("$.journey.id").value(journey))
                .andExpect(jsonPath("$.journey.destinationLabel").value("Station"))
                .andExpect(jsonPath("$.latestLocation.latitude").value(12.9716))
                .andExpect(jsonPath("$.latestLocation.mapLink").value("https://www.google.com/maps/search/?api=1&query=12.9716,77.5946"))
                .andExpect(jsonPath("$.locationCount").value(1))
                .andExpect(jsonPath("$.highestLevel").value(3))
                .andExpect(jsonPath("$.escalations", hasSize(1))) // the SOS row belongs to both: listed once
                .andExpect(jsonPath("$.escalations[0].reason").value("SOS_CONFIRMED"))
                .andExpect(jsonPath("$.circleAlerts", hasSize(2)))
                .andExpect(jsonPath("$.circleAlerts[0].source").value("SAFE_WALK"))
                .andExpect(jsonPath("$.circleAlerts[1].source").value("EMERGENCY"));
    }

    @Test
    void safetyCircleContext_isAStrictAllowList() throws Exception {
        long emergency = startEmergency(tokenA);
        uploadLocation(tokenA, emergency, 12.9716, 77.5946);

        JsonNode circle = json(context(tokenA, emergency, "SAFETY_CIRCLE").andExpect(status().isOk()));

        Set<String> fields = new TreeSet<>();
        for (Iterator<String> it = circle.fieldNames(); it.hasNext(); ) fields.add(it.next());
        assertEquals(new TreeSet<>(List.of("audience", "personName", "type", "status", "startedAt", "endedAt",
                "locationLink", "locationRecordedAt")), fields);
        assertEquals("Test User", circle.get("personName").asText());
        assertEquals("SOS", circle.get("type").asText());
        assertEquals("https://www.google.com/maps/search/?api=1&query=12.9716,77.5946", circle.get("locationLink").asText());
    }

    @Test
    void unavailableAndUnknownAudiences_areRefused_andOtherUsersGet404() throws Exception {
        long emergency = startEmergency(tokenA);
        context(tokenA, emergency, "OFFICIAL_PATHWAY").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This audience is not available"));
        context(tokenA, emergency, "VERIFIED_NEARBY_ASSISTANCE").andExpect(status().isBadRequest());
        context(tokenA, emergency, "neighbours").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown audience"));
        context(tokenB, emergency, null).andExpect(status().isNotFound());
        context(tokenB, emergency, "SAFETY_CIRCLE").andExpect(status().isNotFound());
    }

    @Test
    void everyNewEndpointRequiresAToken() throws Exception {
        mockMvc.perform(post("/api/emergencies/1/circle-alerts")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/journeys/1/circle-alerts")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/emergencies/1/dial-prompts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/emergencies/1/context")).andExpect(status().isUnauthorized());
    }

    // ---- helpers -------------------------------------------------------------------------------

    private Map<String, Object> report(String ref, Long contactId, String kind, String channel, String status, Instant at) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("clientRef", ref);
        report.put("contactId", contactId);
        report.put("kind", kind);
        report.put("channel", channel);
        report.put("status", status);
        report.put("occurredAt", at.toString());
        return report;
    }

    private ResultActions reportEmergency(String token, long id, Map<String, Object> report) throws Exception {
        return postJson("/api/emergencies/" + id + "/circle-alerts", token, Map.of("reports", List.of(report)));
    }

    private ResultActions reportJourney(String token, long id, Map<String, Object> report) throws Exception {
        return postJson("/api/journeys/" + id + "/circle-alerts", token, Map.of("reports", List.of(report)));
    }

    private ResultActions dialPrompt(String token, long id) throws Exception {
        return mockMvc.perform(post("/api/emergencies/" + id + "/dial-prompts").header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    private ResultActions context(String token, long id, String audience) throws Exception {
        var request = get("/api/emergencies/" + id + "/context").header(HttpHeaders.AUTHORIZATION, bearer(token));
        if (audience != null) request = request.param("audience", audience);
        return mockMvc.perform(request);
    }

    private long startEmergency(String token) throws Exception {
        return json(mockMvc.perform(post("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private long startJourney(String token) throws Exception {
        return json(postJson("/api/journeys", token, Map.of("checkInIntervalMinutes", 30)).andExpect(status().isCreated()))
                .get("id").asLong();
    }

    private long createContact(String token, String name, String phone) throws Exception {
        return json(postJson("/api/safety-contacts", token, Map.of(
                "name", name, "phoneNumber", phone, "relationship", "FRIEND", "priority", 1,
                "primary", false, "enabled", true)).andExpect(status().isCreated())).get("id").asLong();
    }

    private void uploadLocation(String token, long emergency, double lat, double lng) throws Exception {
        postJson("/api/emergencies/" + emergency + "/locations", token, Map.of("points", List.of(Map.of(
                "latitude", lat, "longitude", lng, "accuracyMeters", 8.0, "source", "FUSED", "batteryPercent", 64,
                "recordedAt", Instant.now().toString())))).andExpect(status().isCreated());
    }

    private ResultActions postJson(String url, String token, Object body) throws Exception {
        return mockMvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
