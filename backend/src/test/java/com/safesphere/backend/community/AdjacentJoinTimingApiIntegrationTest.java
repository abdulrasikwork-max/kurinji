package com.safesphere.backend.community;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.community.entity.PlaceSignal;
import com.safesphere.backend.community.entity.SignalCategory;
import com.safesphere.backend.community.repository.PlaceSignalRepository;
import com.safesphere.backend.community.repository.SignalContributionRepository;
import com.safesphere.backend.community.service.CommunityService;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.moderation.repository.ModerationCaseRepository;
import com.safesphere.backend.support.CommunityClient;
import com.safesphere.backend.support.CommunityClient.Account;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 9D: the neighbour join with the PRODUCTION timing (10-30 min publication delay, 24 h probation). A report
 * that does not count yet is never joined, so the reporter cannot use the join to learn where somebody else's single
 * report is: everything they can see stays exactly as for a report with nothing next door until their own report
 * counts. And two honest reports made minutes apart (both still pending) are joined once both count.
 */
@SpringBootTest(properties = {
        "app.community.publish-delay-min=10m",
        "app.community.publish-delay-max=30m",
        "app.community.new-account-probation=24h"})
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class AdjacentJoinTimingApiIntegrationTest {

    private static final String X = GeoCell.encode(-23.5, -142.5, GeoCell.BLOCK_PRECISION);
    private static final String AREA = X.substring(0, GeoCell.AREA_PRECISION);
    private static final Duration SETTLE = Duration.ofMinutes(31);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlaceSignalRepository signals;
    @Autowired private SignalContributionRepository contributions;
    @Autowired private ModerationCaseRepository cases;
    @Autowired private CommunityService community;

    private CommunityClient api;

    @BeforeEach
    void clean() {
        clock.reset();
        cases.deleteAll();
        contributions.deleteAll();
        signals.deleteAll();
        api = new CommunityClient(mockMvc, objectMapper);
    }

    /** An account past its probation (made two days ago on the test clock). */
    private Account prepared() throws Exception {
        Account account = api.account();
        LocalDateTime created = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(2);
        jdbc.update("UPDATE users SET created_at = ? WHERE id = ?", Timestamp.valueOf(created), account.id());
        return account;
    }

    private PlaceSignal at(String cell) {
        return signals.findByCellAndCategory(cell, SignalCategory.POOR_LIGHTING).orElse(null);
    }

    private JsonNode mine(Account who) throws Exception {
        return api.json(api.getAs(who, "/api/community/mine").andExpect(status().isOk()));
    }

    @Test
    void beforeTheReportCounts_nothingIsJoined_andTheReporterSeesNothingAboutTheNeighbour() throws Exception {
        String y = AdjacentJoinApiIntegrationTest.sides(X).get(0);
        api.report(prepared(), "POOR_LIGHTING", X).andExpect(status().isAccepted());
        clock.advance(SETTLE);
        String xId = at(X).getPublicId();
        Account bob = prepared();
        api.report(bob, "POOR_LIGHTING", y).andExpect(status().isAccepted());

        assertEquals(0, community.purge().joined(), "bob's report does not count yet");
        JsonNode own = mine(bob).get(0);
        assertEquals("REPORT", own.get("kind").asText());
        assertEquals(y, own.get("cell").asText(), "his own block, as for any report");
        assertEquals("PENDING", own.get("status").asText());
        assertNotEquals(xId, own.get("signalId").asText(), "his own signal, not the neighbour's");
        JsonNode neighbour = api.area(bob, AREA).get("signals").get(0);
        assertEquals(xId, neighbour.get("id").asText());
        assertEquals("AREA", neighbour.get("precision").asText(), "the single report's block stays hidden");
        assertEquals("null", neighbour.get("yourContribution").toString(), "nothing links him to it");

        clock.advance(SETTLE);
        assertEquals(1, community.purge().joined(), "once it counts it joins, and is public support like a confirm");
        assertEquals(xId, mine(bob).get(0).get("signalId").asText());
        assertEquals("BLOCK", api.area(prepared(), AREA).get("signals").get(0).get("precision").asText());
    }

    @Test
    void aNewAccountsReport_joinsOnlyAfterItsProbation() throws Exception {
        api.report(prepared(), "POOR_LIGHTING", X).andExpect(status().isAccepted());
        clock.advance(SETTLE);
        Account fresh = api.account();
        api.report(fresh, "POOR_LIGHTING", AdjacentJoinApiIntegrationTest.sides(X).get(1)).andExpect(status().isAccepted());
        clock.advance(Duration.ofHours(23));
        assertEquals(0, community.purge().joined());
        assertEquals("REPORT", mine(fresh).get(0).get("kind").asText());
        clock.advance(Duration.ofHours(1).plus(SETTLE));
        assertEquals(1, community.purge().joined());
        assertEquals("CONFIRM", mine(fresh).get(0).get("kind").asText());
    }

    @Test
    void twoHonestReportsMinutesApart_bothStillPending_areJoinedOnceBothCount() throws Exception {
        String y = AdjacentJoinApiIntegrationTest.sides(X).get(2);
        api.report(prepared(), "POOR_LIGHTING", X).andExpect(status().isAccepted());
        clock.advance(Duration.ofMinutes(3));
        api.report(prepared(), "POOR_LIGHTING", y).andExpect(status().isAccepted());
        assertEquals(0, community.purge().joined());
        clock.advance(SETTLE);
        assertEquals(1, community.purge().joined());
        assertNull(at(y));
        JsonNode signal = api.area(prepared(), AREA).get("signals").get(0);
        assertEquals("MODERATE", signal.get("confidence").asText());
        assertEquals(X, signal.get("cell").asText());
    }
}
