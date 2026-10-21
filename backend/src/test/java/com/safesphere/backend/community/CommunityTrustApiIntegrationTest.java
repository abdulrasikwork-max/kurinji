package com.safesphere.backend.community;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.community.entity.ContributionKind;
import com.safesphere.backend.community.entity.ContributorStanding;
import com.safesphere.backend.community.entity.SignalContribution;
import com.safesphere.backend.community.repository.ContributorStandingRepository;
import com.safesphere.backend.community.repository.PlaceSignalRepository;
import com.safesphere.backend.community.repository.SignalContributionRepository;
import com.safesphere.backend.community.repository.SignalFlagRepository;
import com.safesphere.backend.community.service.CommunityService;
import com.safesphere.backend.moderation.repository.ModerationActionRepository;
import com.safesphere.backend.moderation.repository.ModerationAppealRepository;
import com.safesphere.backend.moderation.repository.ModerationCaseRepository;
import com.safesphere.backend.moderation.service.ModerationService;
import com.safesphere.backend.support.CommunityClient;
import com.safesphere.backend.support.CommunityClient.Account;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.entity.UserRole;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 7 trust through the real API: sock puppets count once, corroboration and refutation are recorded privately,
 * an established track record raises confidence, and no trust value can be supplied or seen by a client.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class CommunityTrustApiIntegrationTest {

    static final String AREA = "tdr1v9";
    static final String CELL = "tdr1v9q";

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
    @Autowired private UserRepository users;
    @Autowired private CommunityService communityService;
    @Autowired private ModerationService moderationService;

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
        api = new CommunityClient(mockMvc, objectMapper);
    }

    @Test
    void aSockPuppetBackingEverythingItsOwnerReports_countsOnce_andAHumanIsAsked() throws Exception {
        Account owner = api.account();
        Account puppet = api.account();
        String[] cells = {"tdr1v9q", "tdr1v9r", "tdr1v9w"};
        for (String cell : cells) api.report(owner, "POOR_LIGHTING", cell).andExpect(status().isAccepted());
        for (JsonNode signal : api.area(puppet, AREA).get("signals")) {
            api.contribute(puppet, signal.get("id").asText(), "CONFIRM", CELL).andExpect(status().isAccepted());
        }

        JsonNode seen = api.area(api.account(), AREA).get("signals");
        assertEquals(3, seen.size());
        for (JsonNode signal : seen) {
            assertEquals("AREA", signal.get("precision").asText(), "no block cell from one person and their puppet");
            assertEquals("SINGLE", signal.get("support").asText());
            assertEquals("LOW", signal.get("confidence").asText());
            assertTrue(signal.get("reasons").toString().contains("LINKED_SUPPORT_COUNTED_ONCE"));
        }

        // A real, independent person nearby changes that for the place they confirm.
        Account neighbour = api.account();
        String first = seen.get(0).get("id").asText();
        api.contribute(neighbour, first, "CONFIRM", CELL).andExpect(status().isAccepted());
        JsonNode after = api.area(api.account(), AREA).get("signals");
        JsonNode confirmed = null;
        for (JsonNode signal : after) if (signal.get("id").asText().equals(first)) confirmed = signal;
        assertEquals("BLOCK", confirmed.get("precision").asText());

        moderationService.maintain();
        long coordinationCases = cases.findAll().stream()
                .filter(c -> c.getOrigin().name().equals("COORDINATION")).count();
        assertEquals(2, coordinationCases, "the two signals backed only by the pair go to a moderator");
    }

    @Test
    void independentCorroboration_isCreditedOncePrivately_linkedOneIsNot() throws Exception {
        Account reporter = api.account();
        api.report(reporter, "POOR_LIGHTING", CELL);
        Account other = api.account();
        api.contribute(other, api.onlySignalId(other, AREA), "CONFIRM", CELL).andExpect(status().isAccepted());

        communityService.purge();
        communityService.purge();
        assertEquals(1, standings.findById(reporter.id()).orElseThrow().getCorroborated(), "credited exactly once");
        assertFalse(standings.findById(other.id()).isPresent(), "a confirmation is not a report to credit");
        assertTrue(contributions.findByUserId(reporter.id()).get(0).isCredited());
    }

    @Test
    void aRetiredReportThatNobodyIndependentEverBacked_countsAsRefuted_aBackedOneDoesNot() throws Exception {
        Account lonely = api.account();
        api.report(lonely, "BROKEN_PATH", CELL);
        String id = api.onlySignalId(api.account(), AREA);
        api.contribute(api.account(), id, "NO_LONGER", CELL).andExpect(status().isAccepted());
        api.contribute(api.account(), id, "NO_LONGER", CELL).andExpect(status().isAccepted());
        communityService.purge();
        assertEquals(1, standings.findById(lonely.id()).orElseThrow().getRefuted());
        assertEquals(0, signals.count());

        // A report that others backed, and that was later fixed ("no longer true"), is not held against anyone.
        Account backed = api.account();
        api.report(backed, "BROKEN_PATH", CELL);
        String second = api.onlySignalId(api.account(), AREA);
        api.contribute(api.account(), second, "CONFIRM", CELL).andExpect(status().isAccepted());
        communityService.purge(); // credits it
        for (int i = 0; i < 2; i++) api.contribute(api.account(), second, "NO_LONGER", CELL).andExpect(status().isAccepted());
        communityService.purge();
        assertEquals(0, signals.count(), "it was retired");
        assertEquals(0, standings.findById(backed.id()).orElseThrow().getRefuted());
    }

    @Test
    void anEstablishedTrackRecord_raisesConfidence_butNeverPrecisionOrAnyonesVisibility() throws Exception {
        Account veteran = api.account();
        clock.advance(Duration.ofDays(31)); // the account is now older than 30 days
        ContributorStanding record = new ContributorStanding(veteran.id(), now());
        for (int i = 0; i < 3; i++) record.addCorroborated();
        standings.save(record);

        api.report(veteran, "POOR_LIGHTING", CELL);
        JsonNode lone = api.area(api.account(), AREA).get("signals").get(0);
        assertEquals("AREA", lone.get("precision").asText(), "a trusted person's lone report is still only ~1 km");
        assertEquals("LOW", lone.get("confidence").asText());
        assertEquals(List.of("SINGLE_REPORT"), reasons(lone), "nothing reveals the lone reporter's standing");

        String id = lone.get("id").asText();
        api.contribute(api.account(), id, "CONFIRM", CELL).andExpect(status().isAccepted());
        api.contribute(api.account(), id, "CONFIRM", CELL).andExpect(status().isAccepted());
        JsonNode signal = api.area(api.account(), AREA).get("signals").get(0);
        assertEquals("HIGH", signal.get("confidence").asText());
        assertTrue(reasons(signal).contains("ESTABLISHED_CONTRIBUTORS"));

        // The same three steps by three ordinary people: MODERATE.
        api.report(api.account(), "ISOLATED_STRETCH", CELL);
        String plain = null;
        for (JsonNode s : api.area(api.account(), AREA).get("signals")) {
            if (s.get("category").asText().equals("ISOLATED_STRETCH")) plain = s.get("id").asText();
        }
        api.contribute(api.account(), plain, "CONFIRM", CELL);
        api.contribute(api.account(), plain, "CONFIRM", CELL);
        for (JsonNode s : api.area(api.account(), AREA).get("signals")) {
            if (s.get("id").asText().equals(plain)) assertEquals("MODERATE", s.get("confidence").asText());
        }
    }

    @Test
    void trustCannotBeSuppliedByTheClient_andIsNeverReturned() throws Exception {
        Account someone = api.account();
        api.postAs(someone, "/api/community/reports", Map.of("category", "POOR_LIGHTING", "cell", CELL,
                "weight", 99, "tier", "TRUSTED", "confidence", "HIGH", "userId", 1)).andExpect(status().isAccepted());
        String raw = api.getAs(api.account(), "/api/community/area?cell=" + AREA).andReturn().getResponse()
                .getContentAsString();
        JsonNode signal = objectMapper.readTree(raw).get("signals").get(0);
        assertEquals("LOW", signal.get("confidence").asText());
        for (String hidden : new String[]{"tier", "weight", "TRUSTED", "STANDARD", "LIMITED", "corroborated", "strikes"}) {
            assertFalse(raw.contains(hidden), "the public answer must not contain " + hidden);
        }
        String standing = api.getAs(someone, "/api/community/standing").andReturn().getResponse().getContentAsString();
        assertFalse(standing.contains("tier") || standing.contains("corroborated"), "not even the person sees a score");
        assertEquals(UserRole.USER, users.findById(someone.id()).map(User::getRole).orElseThrow());
    }

    @Test
    void contributionsOfARestrictedAccount_neverCount_evenIfMadeBeforeTheRestriction() throws Exception {
        Account reporter = api.account();
        api.report(reporter, "HELP_POINT", CELL);
        ContributorStanding paused = new ContributorStanding(reporter.id(), now());
        paused.restrictUntil(now().plusDays(7));
        standings.save(paused);
        assertEquals(0, api.area(api.account(), AREA).get("signals").size());
        List<SignalContribution> rows = contributions.findByUserId(reporter.id());
        assertEquals(ContributionKind.REPORT, rows.get(0).getKind(), "kept, so a lifted restriction restores it");
    }

    private static List<String> reasons(JsonNode signal) {
        List<String> list = new java.util.ArrayList<>();
        signal.get("reasons").forEach(r -> list.add(r.asText()));
        return list;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
