package com.safesphere.backend.community;

import static com.safesphere.backend.support.TestAccounts.bearer;
import static com.safesphere.backend.support.TestAccounts.registerAndGetToken;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.community.repository.PlaceSignalRepository;
import com.safesphere.backend.community.repository.SignalContributionRepository;
import com.safesphere.backend.community.service.CommunityService;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Phase 6 place signals through the real API (JWT, validation, ownership, privacy projections, abuse limits).
 * Publication delay and probation are 0 here (test properties); CommunityDelayApiIntegrationTest covers them.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CommunityApiIntegrationTest {

    /** Bangalore; all in area cell "tdr1v9". */
    static final String CELL = "tdr1v9q";
    static final String AREA = "tdr1v9";

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;
    @Autowired private PlaceSignalRepository signalRepository;
    @Autowired private SignalContributionRepository contributionRepository;
    @Autowired private CommunityService service;

    @BeforeEach
    void clean() {
        clock.reset();
        contributionRepository.deleteAll();
        signalRepository.deleteAll();
    }

    // ---- Visibility and precision -------------------------------------------------------------------------------

    @Test
    void singleReport_isShownToOthersOnlyAtAreaPrecision_withoutAnyIdentity() throws Exception {
        String alice = account();
        String bob = account();
        report(alice, "POOR_LIGHTING", CELL).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RECORDED"));

        JsonNode signal = area(bob, AREA).get("signals").get(0);
        assertEquals("POOR_LIGHTING", signal.get("category").asText());
        assertEquals("CONDITION", signal.get("group").asText());
        assertEquals("AREA", signal.get("precision").asText());
        assertEquals(AREA, signal.get("cell").asText(), "the block cell is not revealed for one person's report");
        double[] areaCentre = GeoCell.centre(AREA);
        assertEquals(areaCentre[0], signal.get("latitude").asDouble(), 1e-9);
        assertEquals("SINGLE", signal.get("support").asText());
        assertEquals("TODAY", signal.get("freshness").asText());
        assertTrue(signal.get("yourContribution").isNull());
        assertEquals("LOW", signal.get("confidence").asText(), "one person's report is never more than LOW");
        assertEquals("SINGLE_REPORT", signal.get("reasons").get(0).asText());
        assertFalse(signal.get("youFlagged").asBoolean());
        assertOnlyAllowedFields(signal);

        assertEquals("REPORT", area(alice, AREA).get("signals").get(0).get("yourContribution").asText());
    }

    @Test
    void confirmationFromSomeoneNearby_raisesItToBlockPrecision() throws Exception {
        String alice = account();
        String bob = account();
        report(alice, "POOR_LIGHTING", CELL);
        String id = onlySignalId(bob);

        contribute(bob, id, "CONFIRM", CELL).andExpect(status().isAccepted());

        JsonNode signal = area(account(), AREA).get("signals").get(0);
        assertEquals("BLOCK", signal.get("precision").asText());
        assertEquals(CELL, signal.get("cell").asText());
        assertEquals("SEVERAL", signal.get("support").asText());
        assertEquals("MODERATE", signal.get("confidence").asText());
        assertEquals("CONFIRMED_BY_OTHERS", signal.get("reasons").get(0).asText());
    }

    @Test
    void secondReportOfTheSamePlace_mergesSilently() throws Exception {
        String alice = account();
        String bob = account();
        String first = report(alice, "HARASSMENT", CELL).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String second = report(bob, "HARASSMENT", CELL).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();

        assertEquals(first, second, "the answer must not reveal that someone else reported it");
        assertEquals(1, signalRepository.count());
        JsonNode signals = area(account(), AREA).get("signals");
        assertEquals(1, signals.size());
        assertEquals("SEVERAL", signals.get(0).get("support").asText());
    }

    @Test
    void resendingTheSameReport_isHarmless() throws Exception {
        String alice = account();
        report(alice, "BROKEN_PATH", CELL).andExpect(status().isAccepted());
        report(alice, "BROKEN_PATH", CELL).andExpect(status().isAccepted());
        assertEquals(1, contributionRepository.count());
    }

    @Test
    void areaAnswer_containsOnlyTheNeighbourhood() throws Exception {
        String alice = account();
        report(alice, "HELP_POINT", CELL);
        report(alice, "HELP_POINT", GeoCell.encode(13.0716, 77.5946, 7)); // ~11 km north
        assertEquals(1, area(account(), AREA).get("signals").size());
    }

    // ---- Answer rules -------------------------------------------------------------------------------------------

    @Test
    void nobodyCanConfirmTheirOwnReport_orAnswerTwice() throws Exception {
        String alice = account();
        String bob = account();
        report(alice, "POOR_LIGHTING", CELL);
        String id = onlySignalId(bob);

        contribute(alice, id, "CONFIRM", CELL).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You have already added to this signal"));
        contribute(bob, id, "CONFIRM", CELL).andExpect(status().isAccepted());
        contribute(bob, id, "NO_LONGER", CELL).andExpect(status().isConflict());
    }

    @Test
    void answeringFromFarAway_isRefused() throws Exception {
        String alice = account();
        String bob = account();
        report(alice, "POOR_LIGHTING", CELL);
        String id = onlySignalId(bob);
        contribute(bob, id, "CONFIRM", GeoCell.encode(13.0716, 77.5946, 7)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You can only answer signals near where you are"));
    }

    @Test
    void areaSignal_nearnessIsCheckedOnlyAtAreaPrecision_soItsBlockCannotBeProbed() throws Exception {
        String alice = account();
        String bob = account();
        report(alice, "POOR_LIGHTING", CELL);
        String id = onlySignalId(bob);
        // Another block in the same area cell, far from CELL's own block neighbourhood: still accepted, because
        // checking the hidden block would turn "near" into an oracle for it.
        String otherBlockSameArea = "tdr1v90";
        assertFalse(GeoCell.neighbourhood(otherBlockSameArea).contains(CELL));
        contribute(bob, id, "CONFIRM", otherBlockSameArea).andExpect(status().isAccepted());
    }

    @Test
    void reportKindViaContributions_isRejected() throws Exception {
        String alice = account();
        report(alice, "POOR_LIGHTING", CELL);
        String bob = account();
        contribute(bob, onlySignalId(bob), "REPORT", CELL).andExpect(status().isBadRequest());
    }

    @Test
    void unknownSignal_is404() throws Exception {
        contribute(account(), UUID.randomUUID().toString(), "CONFIRM", CELL).andExpect(status().isNotFound());
    }

    // ---- Retirement, expiry, retention ---------------------------------------------------------------------------

    @Test
    void oneNoLonger_disputes_twoRetire_andPurgeDeletesIt() throws Exception {
        String alice = account();
        report(alice, "POOR_LIGHTING", CELL);
        String bob = account();
        String id = onlySignalId(bob);

        contribute(bob, id, "NO_LONGER", CELL).andExpect(status().isAccepted());
        assertTrue(area(account(), AREA).get("signals").get(0).get("disputed").asBoolean());

        contribute(account(), id, "NO_LONGER", CELL).andExpect(status().isAccepted());
        assertEquals(0, area(account(), AREA).get("signals").size());
        contribute(account(), id, "CONFIRM", CELL).andExpect(status().isNotFound());

        service.purge();
        assertEquals(0, signalRepository.count());
        assertEquals(0, contributionRepository.count());

        // The condition came back: a new report starts a fresh signal.
        report(account(), "POOR_LIGHTING", CELL).andExpect(status().isAccepted());
        assertEquals(1, area(account(), AREA).get("signals").size());
    }

    @Test
    void reportOnARetiredSignal_startsAFreshOne_beforePurge() throws Exception {
        report(account(), "POOR_LIGHTING", CELL);
        String id = onlySignalId(account());
        contribute(account(), id, "NO_LONGER", CELL);
        contribute(account(), id, "NO_LONGER", CELL);
        report(account(), "POOR_LIGHTING", CELL).andExpect(status().isAccepted());
        JsonNode signals = area(account(), AREA).get("signals");
        assertEquals(1, signals.size());
        assertFalse(signals.get(0).get("disputed").asBoolean());
        assertFalse(id.equals(signals.get(0).get("id").asText()));
    }

    @Test
    void incidentsExpireAfterThreeDays_conditionsDoNot_andPurgeDeletesTheRows() throws Exception {
        String alice = account();
        report(alice, "FOLLOWING", CELL);
        report(alice, "ISOLATED_STRETCH", CELL);

        clock.advance(Duration.ofDays(3).plusMinutes(1));
        JsonNode signals = area(account(), AREA).get("signals");
        assertEquals(1, signals.size());
        assertEquals("ISOLATED_STRETCH", signals.get(0).get("category").asText());
        assertEquals("THIS_WEEK", signals.get(0).get("freshness").asText());

        service.purge();
        assertEquals(1, signalRepository.count());
        assertEquals(1, contributionRepository.count());

        clock.advance(Duration.ofDays(28));
        service.purge();
        assertEquals(0, signalRepository.count());
    }

    // ---- Owner view and withdrawal ---------------------------------------------------------------------------------

    @Test
    void mine_showsOnlyTheCallersOwn_andWithdrawWorksOnlyForThem() throws Exception {
        String alice = account();
        String bob = account();
        report(alice, "UNSAFE_CROSSING", CELL);
        String id = onlySignalId(bob);

        mockMvc.perform(get("/api/community/mine").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].signalId").value(id))
                .andExpect(jsonPath("$[0].kind").value("REPORT"))
                .andExpect(jsonPath("$[0].cell").value(CELL))
                .andExpect(jsonPath("$[0].status").value("LIVE"));
        mockMvc.perform(get("/api/community/mine").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(delete("/api/community/mine/" + id).header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/community/mine/" + id).header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isNoContent());
        assertEquals(0, signalRepository.count(), "a signal nobody supports any more is deleted");
        assertEquals(0, area(bob, AREA).get("signals").size());
    }

    @Test
    void mine_confirmationOfAnAreaSignal_showsOnlyTheAreaCell() throws Exception {
        report(account(), "POOR_LIGHTING", CELL);
        String bob = account();
        String id = onlySignalId(bob);
        contribute(bob, id, "NO_LONGER", "tdr1v90"); // keeps it at AREA precision (no second supporter)
        mockMvc.perform(get("/api/community/mine").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(jsonPath("$[0].kind").value("NO_LONGER"))
                .andExpect(jsonPath("$[0].cell").value(AREA));
    }

    // ---- Abuse limits ---------------------------------------------------------------------------------------------

    @Test
    void reportBombingOneArea_isLimited() throws Exception {
        String alice = account();
        report(alice, "POOR_LIGHTING", "tdr1v9q").andExpect(status().isAccepted());
        report(alice, "POOR_LIGHTING", "tdr1v9r").andExpect(status().isAccepted());
        report(alice, "POOR_LIGHTING", "tdr1v9w").andExpect(status().isAccepted());
        report(alice, "POOR_LIGHTING", "tdr1v9x").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("You have already reported several things in this area today."));
    }

    @Test
    void dailyReportLimit() throws Exception {
        String alice = account();
        for (int i = 0; i < 10; i++) {
            report(alice, "HELP_POINT", GeoCell.encode(12.9716 + i * 0.02, 77.5946, 7)).andExpect(status().isAccepted());
        }
        report(alice, "HELP_POINT", GeoCell.encode(13.3, 77.5946, 7)).andExpect(status().isTooManyRequests());
        clock.advance(Duration.ofHours(24).plusMinutes(1));
        report(alice, "HELP_POINT", GeoCell.encode(13.3, 77.5946, 7)).andExpect(status().isAccepted());
    }

    @Test
    void lookingAtManyDifferentAreas_isLimited_butRefreshingYourOwnIsNot() throws Exception {
        String alice = account();
        for (int i = 0; i < 15; i++) {
            areaRequest(alice, GeoCell.encode(12.0 + i * 0.05, 77.0, 6)).andExpect(status().isOk());
        }
        areaRequest(alice, GeoCell.encode(14.0, 77.0, 6)).andExpect(status().isTooManyRequests());
        areaRequest(alice, GeoCell.encode(12.0, 77.0, 6)).andExpect(status().isOk());
        clock.advance(Duration.ofHours(1));
        areaRequest(alice, GeoCell.encode(14.0, 77.0, 6)).andExpect(status().isOk());
    }

    // ---- Validation and authentication -------------------------------------------------------------------------------

    @Test
    void invalidInput_is400() throws Exception {
        String alice = account();
        areaRequest(alice, "tdr1va").andExpect(status().isBadRequest());     // 'a' is not geohash
        areaRequest(alice, CELL).andExpect(status().isBadRequest());         // a block cell is not an area
        report(alice, "POOR_LIGHTING", AREA).andExpect(status().isBadRequest());
        report(alice, "SUSPICIOUS_PERSON", CELL).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/community/reports").header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"POOR_LIGHTING\",\"cell\":\"" + CELL + "\",\"latitude\":12.97}"))
                .andExpect(status().isAccepted()); // unknown fields (e.g. coordinates) are ignored, never stored
        assertEquals(1, signalRepository.count());
    }

    @Test
    void everyEndpointNeedsAToken() throws Exception {
        mockMvc.perform(get("/api/community/area").param("cell", AREA)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/community/mine")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/community/reports").contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"POOR_LIGHTING\",\"cell\":\"" + CELL + "\"}")).andExpect(status().isUnauthorized());
    }

    // ---- Helpers ---------------------------------------------------------------------------------------------------

    /**
     * The public projection is an allow-list: fail if any other field (an id of a person, a time, a count, a trust
     * score) appears. Phase 7 added exactly confidence, reasons (aggregate) and youFlagged (the caller's own flag).
     */
    static void assertOnlyAllowedFields(JsonNode signal) {
        java.util.Set<String> allowed = java.util.Set.of("id", "category", "group", "precision", "cell", "latitude",
                "longitude", "support", "freshness", "disputed", "yourContribution", "confidence", "reasons",
                "youFlagged");
        for (Iterator<String> it = signal.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            assertTrue(allowed.contains(field), "unexpected field in public signal: " + field);
        }
    }

    private String account() throws Exception {
        return registerAndGetToken(mockMvc, objectMapper, "community." + UUID.randomUUID() + "@example.com");
    }

    private ResultActions report(String token, String category, String cell) throws Exception {
        return mockMvc.perform(post("/api/community/reports").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("category", category, "cell", cell))));
    }

    private ResultActions contribute(String token, String id, String kind, String near) throws Exception {
        return mockMvc.perform(post("/api/community/signals/" + id + "/contributions")
                .header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("kind", kind, "near", near))));
    }

    private ResultActions areaRequest(String token, String area) throws Exception {
        return mockMvc.perform(get("/api/community/area").param("cell", area).header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    private JsonNode area(String token, String area) throws Exception {
        return objectMapper.readTree(areaRequest(token, area).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private String onlySignalId(String token) throws Exception {
        JsonNode signals = area(token, AREA).get("signals");
        assertEquals(1, signals.size());
        return signals.get(0).get("id").asText();
    }
}
