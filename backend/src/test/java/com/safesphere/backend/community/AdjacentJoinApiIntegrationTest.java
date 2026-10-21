package com.safesphere.backend.community;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.community.entity.ContributionKind;
import com.safesphere.backend.community.entity.PlaceSignal;
import com.safesphere.backend.community.entity.SignalCategory;
import com.safesphere.backend.community.repository.ContributorStandingRepository;
import com.safesphere.backend.community.repository.PlaceSignalRepository;
import com.safesphere.backend.community.repository.SignalContributionRepository;
import com.safesphere.backend.community.repository.SignalFlagRepository;
import com.safesphere.backend.community.service.CommunityService;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseStatus;
import com.safesphere.backend.moderation.repository.ModerationActionRepository;
import com.safesphere.backend.moderation.repository.ModerationAppealRepository;
import com.safesphere.backend.moderation.repository.ModerationCaseRepository;
import com.safesphere.backend.support.CommunityClient;
import com.safesphere.backend.support.CommunityClient.Account;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 9D (docs/PHASE9.md §18): a lone report joins the same signal in a block that shares a side with its own, in
 * the clean-up job. Through the real API (JWT, validation, limits) with the test defaults (no delay, no probation);
 * the timing side (nothing happens before a report counts) is in AdjacentJoinTimingApiIntegrationTest. Places are
 * synthetic, in open ocean.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class AdjacentJoinApiIntegrationTest {

    /** A synthetic block in open ocean, away from the cells other tests use. */
    static final String X = GeoCell.encode(-21.5, -140.5, GeoCell.BLOCK_PRECISION);
    static final String AREA = X.substring(0, GeoCell.AREA_PRECISION);

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
    @Autowired private CommunityService community;

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

    /** The blocks that share a side with [cell], in a fixed order. */
    static List<String> sides(String cell) {
        return new TreeSet<>(GeoCell.neighbourhood(cell)).stream()
                .filter(c -> GeoCell.adjacency(cell, c) == GeoCell.Adjacency.EDGE).toList();
    }

    static List<String> corners(String cell) {
        return new TreeSet<>(GeoCell.neighbourhood(cell)).stream()
                .filter(c -> GeoCell.adjacency(cell, c) == GeoCell.Adjacency.CORNER).toList();
    }

    private PlaceSignal at(String cell, String category) {
        return signals.findByCellAndCategory(cell, SignalCategory.valueOf(category)).orElse(null);
    }

    private JsonNode seen(String cell, String category) throws Exception {
        PlaceSignal signal = at(cell, category);
        if (signal == null) return null;
        for (JsonNode s : api.area(api.account(), cell.substring(0, GeoCell.AREA_PRECISION)).get("signals")) {
            if (s.get("id").asText().equals(signal.getPublicId())) return s;
        }
        return null;
    }

    private JsonNode mine(Account who) throws Exception {
        return api.json(api.getAs(who, "/api/community/mine").andExpect(status().isOk()));
    }

    // ---- Honest corroboration ------------------------------------------------------------------------------------

    @Test
    void twoLoneReportsInSideBlocks_becomeOneConfirmedSignal_atTheOlderBlock() throws Exception {
        String y = sides(X).get(0);
        Account alice = api.account();
        Account bob = api.account();
        JsonNode first = api.json(api.report(alice, "POOR_LIGHTING", X).andExpect(status().isAccepted()));
        JsonNode second = api.json(api.report(bob, "POOR_LIGHTING", y).andExpect(status().isAccepted()));
        assertEquals(first, second, "the answer never says whether anything is next door");
        assertEquals(2, signals.count(), "nothing is joined at report time");

        assertEquals(1, community.purge().joined());

        assertNull(at(y, "POOR_LIGHTING"), "the newer lone report left its own block");
        JsonNode signal = seen(X, "POOR_LIGHTING");
        assertEquals("MODERATE", signal.get("confidence").asText());
        assertEquals("BLOCK", signal.get("precision").asText(), "two independent people: the older block is shown");
        assertEquals(X, signal.get("cell").asText());
        assertEquals("SEVERAL", signal.get("support").asText());

        JsonNode bobs = mine(bob);
        assertEquals(1, bobs.size());
        assertEquals("CONFIRM", bobs.get(0).get("kind").asText(), "shown as \"you said you saw this too\"");
        assertEquals(X, bobs.get(0).get("cell").asText(), "public at block precision now, so nothing hidden is shown");
        assertEquals("LIVE", bobs.get(0).get("status").asText());
        assertEquals("REPORT", mine(alice).get(0).get("kind").asText());
        assertEquals("TAKE_CARE", api.area(api.account(), AREA).get("picture").get("outlook").asText());
    }

    @Test
    void cornerBlocks_differentCategories_andSafePlaces_neverJoin() throws Exception {
        api.report(api.account(), "POOR_LIGHTING", X).andExpect(status().isAccepted());
        api.report(api.account(), "POOR_LIGHTING", corners(X).get(0)).andExpect(status().isAccepted());
        api.report(api.account(), "ISOLATED_STRETCH", sides(X).get(0)).andExpect(status().isAccepted());
        String haven = GeoCell.encode(-21.6, -140.6, GeoCell.BLOCK_PRECISION);
        api.report(api.account(), "HELP_POINT", haven).andExpect(status().isAccepted());
        api.report(api.account(), "HELP_POINT", sides(haven).get(0)).andExpect(status().isAccepted());

        assertEquals(0, community.purge().joined());
        assertEquals(5, signals.count());
    }

    @Test
    void aLoneReportJoinsTheBestBackedOlderNeighbour_andAChainMovesNothingFurtherThanOneBlock() throws Exception {
        // Two older signals next to Y: one person behind X, two behind the other side block W.
        String y = sides(X).get(0);
        String w = sides(y).stream().filter(c -> !c.equals(X) && GeoCell.adjacency(c, X) == GeoCell.Adjacency.NONE)
                .findFirst().orElseThrow();
        api.report(api.account(), "BROKEN_PATH", X).andExpect(status().isAccepted());
        api.report(api.account(), "BROKEN_PATH", w).andExpect(status().isAccepted());
        String wId = at(w, "BROKEN_PATH").getPublicId();
        api.contribute(api.account(), wId, "CONFIRM", w).andExpect(status().isAccepted());
        Account late = api.account();
        api.report(late, "BROKEN_PATH", y).andExpect(status().isAccepted());

        assertEquals(1, community.purge().joined());
        assertEquals(wId, mine(late).get(0).get("signalId").asText(), "the better-backed neighbour");
        assertEquals(3, contributions.findBySignalId(at(w, "BROKEN_PATH").getId()).size());
        assertNotNull(at(X, "BROKEN_PATH"), "X is not next to W: it stays where it was reported");
        assertEquals(0, community.purge().joined(), "nothing more to do: X is not next to W");
    }

    @Test
    void theOlderSignalNeverMoves_soANewerSignalCannotCaptureAnHonestReport() throws Exception {
        String y = sides(X).get(0);
        Account honest = api.account();
        api.report(honest, "HARASSMENT", X).andExpect(status().isAccepted());
        // Later, two accounts build a confirmed signal in the side block.
        api.report(api.account(), "HARASSMENT", y).andExpect(status().isAccepted());
        api.contribute(api.account(), at(y, "HARASSMENT").getPublicId(), "CONFIRM", y).andExpect(status().isAccepted());

        assertEquals(0, community.purge().joined());
        assertEquals("REPORT", mine(honest).get(0).get("kind").asText());
        assertEquals(at(X, "HARASSMENT").getPublicId(), mine(honest).get(0).get("signalId").asText());
    }

    // ---- When it must NOT join -----------------------------------------------------------------------------------

    @Test
    void noJoin_whenTheReporterSaidNoLongerTrueThere_orTheNeighbourIsUnderReview_orTheReportIsFlagged() throws Exception {
        String[] side = sides(X).toArray(String[]::new);
        Account reporter = api.account();
        api.report(api.account(), "POOR_LIGHTING", X).andExpect(status().isAccepted());
        api.contribute(reporter, at(X, "POOR_LIGHTING").getPublicId(), "NO_LONGER", X).andExpect(status().isAccepted());
        api.report(reporter, "POOR_LIGHTING", side[0]).andExpect(status().isAccepted());
        assertEquals(0, community.purge().joined(), "someone who disputed the neighbour keeps their own report");
        api.report(api.account(), "POOR_LIGHTING", side[1]).andExpect(status().isAccepted());
        assertEquals(1, community.purge().joined(), "control: anyone else's lone report there joins");

        String other = GeoCell.encode(-21.7, -140.7, GeoCell.BLOCK_PRECISION);
        api.report(api.account(), "UNSAFE_CROSSING", other).andExpect(status().isAccepted());
        api.flag(api.account(), at(other, "UNSAFE_CROSSING").getPublicId(), "FALSE_INFORMATION", other)
                .andExpect(status().isAccepted());
        assertTrue(cases.findBySignalIdAndStatus(at(other, "UNSAFE_CROSSING").getId(), CaseStatus.OPEN).isPresent());
        api.report(api.account(), "UNSAFE_CROSSING", sides(other).get(0)).andExpect(status().isAccepted());
        assertEquals(0, community.purge().joined(), "nothing is added to a signal a human is reviewing");
        cases.findBySignalIdAndStatus(at(other, "UNSAFE_CROSSING").getId(), CaseStatus.OPEN).ifPresent(cases::delete);
        assertEquals(1, community.purge().joined(), "control: once nobody reviews it, the same report joins");

        String third = GeoCell.encode(-21.8, -140.8, GeoCell.BLOCK_PRECISION);
        api.report(api.account(), "ISOLATED_STRETCH", third).andExpect(status().isAccepted());
        String lone = sides(third).get(0);
        api.report(api.account(), "ISOLATED_STRETCH", lone).andExpect(status().isAccepted());
        api.flag(api.account(), at(lone, "ISOLATED_STRETCH").getPublicId(), "SPAM_OR_COORDINATED", lone)
                .andExpect(status().isAccepted());
        assertEquals(0, community.purge().joined(), "a flagged report stays where a moderator can find it");
        api.report(api.account(), "ISOLATED_STRETCH", sides(third).get(1)).andExpect(status().isAccepted());
        assertEquals(1, community.purge().joined(), "control: an unflagged lone report next to the same signal joins");
    }

    @Test
    void aOnceConfirmedSignalThatIsLoneAgain_staysWhereItIs() throws Exception {
        api.report(api.account(), "POOR_LIGHTING", X).andExpect(status().isAccepted());
        String y = sides(X).get(0);
        Account confirmer = api.account();
        api.report(api.account(), "POOR_LIGHTING", y).andExpect(status().isAccepted());
        api.contribute(confirmer, at(y, "POOR_LIGHTING").getPublicId(), "CONFIRM", y).andExpect(status().isAccepted());
        assertEquals(0, community.purge().joined(), "two people behind it: not a lone report");
        assertTrue(at(y, "POOR_LIGHTING").isReachedConfirmed());
        api.deleteAs(confirmer, "/api/community/mine/" + at(y, "POOR_LIGHTING").getPublicId())
                .andExpect(status().isNoContent());
        assertEquals(0, community.purge().joined(), "once confirmed (a possible pattern episode): it stays");
        api.report(api.account(), "POOR_LIGHTING", sides(X).get(1)).andExpect(status().isAccepted());
        assertEquals(1, community.purge().joined(), "control: a never-confirmed lone report there joins");
    }

    // ---- The reporter's own view ---------------------------------------------------------------------------------

    @Test
    void aResentReportAfterTheJoin_addsNothing_andWithdrawingTheJoinedAnswerLeavesOnePerson() throws Exception {
        String y = sides(X).get(1);
        api.report(api.account(), "POOR_LIGHTING", X).andExpect(status().isAccepted());
        Account bob = api.account();
        JsonNode accepted = api.json(api.report(bob, "POOR_LIGHTING", y).andExpect(status().isAccepted()));
        assertEquals(1, community.purge().joined());

        // The phone's outbox sends the same report again (its first answer was lost on the way).
        assertEquals(accepted, api.json(api.report(bob, "POOR_LIGHTING", y).andExpect(status().isAccepted())));
        assertEquals(1, signals.count(), "no new signal at the old block");
        assertEquals(1, mine(bob).size());

        String id = mine(bob).get(0).get("signalId").asText();
        api.deleteAs(bob, "/api/community/mine/" + id).andExpect(status().isNoContent());
        JsonNode signal = seen(X, "POOR_LIGHTING");
        assertEquals("LOW", signal.get("confidence").asText());
        assertEquals("AREA", signal.get("precision").asText(), "one person again: the block is hidden again");
    }

    @Test
    void aPuppetNextDoor_countsOnce_andItsOwnerNeverSeesTheHiddenBlock() throws Exception {
        Account owner = api.account();
        Account puppet = api.account();
        // Three shared signals elsewhere link them (Phase 7).
        for (int i = 0; i < 3; i++) {
            String cell = GeoCell.encode(-22.5 - i * 0.1, -141.5, GeoCell.BLOCK_PRECISION);
            api.report(owner, "BROKEN_PATH", cell).andExpect(status().isAccepted());
            api.contribute(puppet, at(cell, "BROKEN_PATH").getPublicId(), "CONFIRM", cell).andExpect(status().isAccepted());
        }
        api.report(owner, "ISOLATED_STRETCH", X).andExpect(status().isAccepted());
        api.report(puppet, "ISOLATED_STRETCH", sides(X).get(2)).andExpect(status().isAccepted());

        assertEquals(1, community.purge().joined(), "joining does not depend on who people are linked to");
        JsonNode signal = seen(X, "ISOLATED_STRETCH");
        assertEquals("LOW", signal.get("confidence").asText(), "linked accounts count once, as for any confirmation");
        assertEquals("AREA", signal.get("precision").asText());
        JsonNode theirs = mine(puppet);
        JsonNode joined = null;
        for (JsonNode c : theirs) if (c.get("signalId").asText().equals(at(X, "ISOLATED_STRETCH").getPublicId())) joined = c;
        assertEquals("CONFIRM", joined.get("kind").asText());
        assertEquals(AREA, joined.get("cell").asText(), "the single report's block is never shown to the joiner");
    }

    @Test
    void contributionsKeepTheirTime_andTheJoinedReportCanStillBeCredited() throws Exception {
        String y = sides(X).get(3);
        Account alice = api.account();
        Account bob = api.account();
        api.report(alice, "UNSAFE_CROSSING", X).andExpect(status().isAccepted());
        api.report(bob, "UNSAFE_CROSSING", y).andExpect(status().isAccepted());
        var before = contributions.findBySignalId(at(y, "UNSAFE_CROSSING").getId()).get(0).getCountsFrom();
        community.purge();
        var moved = contributions.findBySignalIdAndUserId(at(X, "UNSAFE_CROSSING").getId(), bob.id()).orElseThrow();
        assertEquals(ContributionKind.CONFIRM, moved.getKind());
        assertEquals(before, moved.getCountsFrom(), "the real time of a report is never stored, and never refreshed");
        assertTrue(contributions.findBySignalIdAndUserId(at(X, "UNSAFE_CROSSING").getId(), alice.id()).orElseThrow()
                .isCredited(), "the older report is credited: an independent person now backs it");
        assertFalse(standings.findById(bob.id()).map(s -> s.getRefuted() > 0).orElse(false));
    }
}
