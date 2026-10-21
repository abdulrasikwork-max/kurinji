package com.safesphere.backend.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.community.repository.ContributorStandingRepository;
import com.safesphere.backend.community.repository.PlaceSignalRepository;
import com.safesphere.backend.community.repository.SignalContributionRepository;
import com.safesphere.backend.community.repository.SignalFlagRepository;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.intelligence.repository.AreaPatternRepository;
import com.safesphere.backend.moderation.repository.ModerationActionRepository;
import com.safesphere.backend.moderation.repository.ModerationAppealRepository;
import com.safesphere.backend.moderation.repository.ModerationCaseRepository;
import com.safesphere.backend.support.CommunityClient;
import com.safesphere.backend.support.CommunityClient.Account;
import com.safesphere.backend.support.MutableClock;
import java.time.Duration;
import java.util.Iterator;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Phase 8D region packs through the real API: area pictures plus CONFIRMED signals only (no ids, no single reports),
 * "absent = nothing known", incomplete packs, the daily budget, validation and authentication.
 */
@SpringBootTest(properties = "app.intelligence.region-max-signals=2")
@AutoConfigureMockMvc
class RegionPackApiIntegrationTest {

    static final String REGION = "tdr1v";
    static final String AREA = "tdr1v9";
    static final String CELL = "tdr1v9q";

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock regionTestClock() {
            return new MutableClock();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;
    @Autowired private PlaceSignalRepository signals;
    @Autowired private SignalContributionRepository contributions;
    @Autowired private SignalFlagRepository flags;
    @Autowired private ContributorStandingRepository standings;
    @Autowired private ModerationCaseRepository cases;
    @Autowired private ModerationActionRepository actions;
    @Autowired private ModerationAppealRepository appeals;
    @Autowired private AreaPatternRepository patterns;

    private CommunityClient api;

    @BeforeEach
    void clean() {
        clock.reset();
        appeals.deleteAll();
        actions.deleteAll();
        cases.deleteAll();
        flags.deleteAll();
        contributions.deleteAll();
        signals.deleteAll();
        standings.deleteAll();
        patterns.deleteAll();
        api = new CommunityClient(mockMvc, objectMapper);
    }

    @Test
    void aPack_holdsAreaPicturesAndConfirmedSignalsOnly_withoutIdsOrSingleReports() throws Exception {
        String confirmedId = confirmed("POOR_LIGHTING", CELL);
        api.report(api.account(), "HARASSMENT", "tdr1v9r").andExpect(status().isAccepted());
        String singleId = null;
        for (JsonNode s : api.area(api.account(), AREA).get("signals")) {
            if (s.get("category").asText().equals("HARASSMENT")) singleId = s.get("id").asText();
        }

        String raw = region(api.account(), REGION).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode pack = objectMapper.readTree(raw);
        assertOnly(pack, "region", "modelVersion", "complete", "areas", "signals");
        assertEquals(REGION, pack.get("region").asText());
        assertEquals("rules-1.1", pack.get("modelVersion").asText(), "Phase 9D: the active rules (rules-2 is SHADOW)");
        assertTrue(pack.get("complete").asBoolean());

        assertEquals(1, pack.get("areas").size(), "only cells the community knows something about");
        JsonNode area = pack.get("areas").get(0);
        assertOnly(area, "cell", "outlook", "concern", "information", "reasons", "concernCategories", "conflicting");
        assertEquals(AREA, area.get("cell").asText());
        assertEquals("TAKE_CARE", area.get("outlook").asText());
        assertTrue(area.get("reasons").toString().contains("UNCONFIRMED_REPORTS"));

        assertEquals(1, pack.get("signals").size(), "the single report never appears as a signal");
        JsonNode signal = pack.get("signals").get(0);
        assertOnly(signal, "category", "group", "cell", "confidence", "freshness", "disputed");
        assertEquals("POOR_LIGHTING", signal.get("category").asText());
        assertEquals(CELL, signal.get("cell").asText(), "confirmed signals are public at block precision already");
        assertEquals("MODERATE", signal.get("confidence").asText());
        assertFalse(raw.contains(confirmedId) || raw.contains(singleId), "no signal ids in a pack");
        assertFalse(raw.contains("tdr1v9r"), "the single report's block is never revealed");
    }

    @Test
    void anEmptyRegion_isCompleteWithNothingInIt_absentMeansNothingKnown() throws Exception {
        JsonNode pack = json(region(api.account(), REGION).andExpect(status().isOk()));
        assertTrue(pack.get("complete").asBoolean());
        assertEquals(0, pack.get("areas").size());
        assertEquals(0, pack.get("signals").size());
    }

    @Test
    void tooManyConfirmedSignals_makeThePackIncomplete_soThePhoneShowsNoBrief() throws Exception {
        confirmed("POOR_LIGHTING", "tdr1v9q");
        confirmed("BROKEN_PATH", "tdr1v9r");
        confirmed("UNSAFE_CROSSING", "tdr1v9w");
        JsonNode pack = json(region(api.account(), REGION).andExpect(status().isOk()));
        assertFalse(pack.get("complete").asBoolean());
        assertEquals(0, pack.get("signals").size(), "a partial list could miss a concern: none is sent");
        assertEquals(1, pack.get("areas").size());
    }

    @Test
    void quarantinedSignals_areNotInPacks() throws Exception {
        String id = confirmed("HARASSMENT", CELL);
        api.flag(api.account(), id, "FALSE_INFORMATION", CELL).andExpect(status().isAccepted());
        api.flag(api.account(), id, "FALSE_INFORMATION", CELL).andExpect(status().isAccepted());
        JsonNode pack = json(region(api.account(), REGION).andExpect(status().isOk()));
        assertEquals(0, pack.get("signals").size());
        assertEquals(0, pack.get("areas").size());
    }

    @Test
    void theDailyBudget_limitsDistinctRegions_andRequests_butNotRepeats() throws Exception {
        Account alice = api.account();
        for (int i = 0; i < 6; i++) region(alice, GeoCell.encode(12.0 + i * 0.1, 77.0, 5)).andExpect(status().isOk());
        region(alice, GeoCell.encode(14.0, 77.0, 5)).andExpect(status().isTooManyRequests());
        region(alice, GeoCell.encode(12.0, 77.0, 5)).andExpect(status().isOk()); // the same walk again is fine
        for (int i = 0; i < 13; i++) region(alice, GeoCell.encode(12.0, 77.0, 5)).andExpect(status().isOk());
        region(alice, GeoCell.encode(12.0, 77.0, 5)).andExpect(status().isTooManyRequests()); // 20 a day
        region(api.account(), REGION).andExpect(status().isOk()); // someone else is unaffected
        clock.advance(Duration.ofHours(24));
        region(alice, GeoCell.encode(14.0, 77.0, 5)).andExpect(status().isOk());
    }

    @Test
    void invalidRegions_are400_andATokenIsNeeded() throws Exception {
        Account alice = api.account();
        region(alice, "tdr1").andExpect(status().isBadRequest());
        region(alice, AREA).andExpect(status().isBadRequest());
        region(alice, "tdr1a").andExpect(status().isBadRequest());
        region(alice, "TDR1V").andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/intelligence/region").param("cell", REGION)).andExpect(status().isUnauthorized());
    }

    // ---- Helpers ---------------------------------------------------------------------------------------------------

    private String confirmed(String category, String cell) throws Exception {
        api.report(api.account(), category, cell).andExpect(status().isAccepted());
        String id = null;
        for (JsonNode s : api.area(api.account(), cell.substring(0, 6)).get("signals")) {
            if (s.get("category").asText().equals(category)) id = s.get("id").asText();
        }
        api.contribute(api.account(), id, "CONFIRM", cell).andExpect(status().isAccepted());
        return id;
    }

    private ResultActions region(Account who, String cell) throws Exception {
        return api.getAs(who, "/api/intelligence/region?cell=" + cell);
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static void assertOnly(JsonNode node, String... allowed) {
        Set<String> names = Set.of(allowed);
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            assertTrue(names.contains(field), "unexpected field: " + field);
        }
    }
}
