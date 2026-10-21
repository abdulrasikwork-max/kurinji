package com.safesphere.backend.evaluation.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.community.repository.ContributorStandingRepository;
import com.safesphere.backend.community.repository.PlaceSignalRepository;
import com.safesphere.backend.community.repository.SignalContributionRepository;
import com.safesphere.backend.community.repository.SignalFlagRepository;
import com.safesphere.backend.community.service.CommunityService;
import com.safesphere.backend.community.service.CoordinationPolicy.Group;
import com.safesphere.backend.community.service.TrustService;
import com.safesphere.backend.evaluation.simulation.AttackReport.Row;
import com.safesphere.backend.intelligence.repository.AreaPatternRepository;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.CellPatterns;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Model;
import com.safesphere.backend.intelligence.service.AreaModelShadow;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseOrigin;
import com.safesphere.backend.moderation.repository.ModerationActionRepository;
import com.safesphere.backend.moderation.repository.ModerationAppealRepository;
import com.safesphere.backend.moderation.repository.ModerationCaseRepository;
import com.safesphere.backend.moderation.service.CoordinationCheck;
import com.safesphere.backend.moderation.service.ModerationService;
import com.safesphere.backend.support.CommunityClient.Account;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 9D attack and false-positive simulation of the NEIGHBOUR JOIN (a lone report joins the same signal in a block
 * that shares a side with its own) and of rules-2 on honest corroboration. Same harness and PRODUCTION timing as
 * AttackSimulationTest (10-30 min delay, 24 h probation, 10 writes per 10 minutes). Rows go to
 * target/attack-simulation/neighbour-join.md (SIMULATED, not real-world data).
 *
 * The question for every attack: does the join give anyone a capability that "I saw this too" (a CONFIRM, possible
 * from anywhere in the ~1 km neighbourhood) did not already give? And for every honest case: does it help, and what
 * does it cost?
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
@TestPropertySource(properties = {
        "app.community.publish-delay-min=10m",
        "app.community.publish-delay-max=30m",
        "app.community.new-account-probation=24h",
        "app.community.writes-per-10-minutes=10"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NeighbourJoinSimulationTest {

    static final String SECTION = "neighbour-join";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CommunityService community;
    @Autowired private ModerationService moderation;
    @Autowired private TrustService trust;
    @Autowired private PlaceSignalRepository signals;
    @Autowired private SignalContributionRepository contributions;
    @Autowired private SignalFlagRepository flags;
    @Autowired private ContributorStandingRepository standings;
    @Autowired private ModerationCaseRepository cases;
    @Autowired private ModerationActionRepository actions;
    @Autowired private ModerationAppealRepository appeals;
    @Autowired private AreaPatternRepository patterns;
    @Autowired private CoordinationCheck coordination;
    @Autowired private AreaModelShadow shadow;

    private AttackHarness h;

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
        h = harness();
    }

    @AfterAll
    static void writeReport() throws IOException {
        AttackReport.write(SECTION, "Phase 9D: the neighbour join and rules-2, attacked and tested on honest groups");
    }

    private AttackHarness harness() {
        return new AttackHarness(mockMvc, objectMapper, clock, jdbc, community, moderation, trust, signals,
                contributions, cases, coordination, shadow);
    }

    private static String area(int row, int column) {
        return AttackHarness.area(40 + row, column);
    }

    private static String side(String cell, String area, int k) {
        return AttackHarness.sides(cell).stream().filter(c -> c.startsWith(area)).toList().get(k);
    }

    private void row(String scenario, String question, String response, String viewerSees) {
        AttackReport.add(SECTION, new Row(scenario, question, h.effort(), h.elapsed(), response, viewerSees));
    }

    /** An honest single report at [cell], visible (at area precision) once it counts. */
    private Account honestSingle(String category, String cell) throws Exception {
        Account reporter = h.honest();
        h.report(reporter, category, cell);
        h.settle();
        assertNotNull(h.visible(category, cell));
        assertEquals("AREA", h.visible(category, cell).get("precision").asText());
        return reporter;
    }

    // ---- Attacks ---------------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    void piggyback_aReportPlacedNextToAnHonestOne_gainsExactlyWhatAConfirmGives() throws Exception {
        String area = area(0, 0);
        String x = AttackHarness.block(area, 12);
        honestSingle("POOR_LIGHTING", x);
        Account attacker = h.attacker();
        assertEquals(202, h.report(attacker, "POOR_LIGHTING", side(x, area, 0)));
        h.settle();
        JsonNode joined = h.visible("POOR_LIGHTING", x);
        assertEquals("MODERATE", joined.get("confidence").asText());

        // Control: the same attacker budget, spent on "I saw this too" on another honest single.
        String control = AttackHarness.block(area(0, 2), 12);
        honestSingle("POOR_LIGHTING", control);
        assertEquals(202, h.confirm(h.attacker(), "POOR_LIGHTING", control));
        h.settle();
        assertEquals(joined.get("confidence"), h.visible("POOR_LIGHTING", control).get("confidence"));
        assertEquals(joined.get("precision"), h.visible("POOR_LIGHTING", control).get("precision"));
        row("J1 piggyback (grid-boundary report next to a real one)", "Does a report placed one block from an honest "
                        + "report gain more than \"I saw this too\"?",
                "Joined once it counted: MODERATE, block shown. A CONFIRM from anywhere in the ~1 km neighbourhood "
                        + "gives exactly the same, for the same single write",
                "TAKE_CARE either way: no new capability (the join is a confirmation limited to one block)");
    }

    @Test
    @Order(2)
    void capture_aNewerFakeNextToAnOlderHonestReport_cannotAbsorbIt() throws Exception {
        String area = area(1, 0);
        String x = AttackHarness.block(area, 12);
        Account honest = honestSingle("HARASSMENT", x);
        Account a = h.attacker();
        Account b = h.attacker();
        String y = side(x, area, 0);
        h.report(a, "HARASSMENT", y);   // both before either counts, so their signal is never a lone report
        h.report(b, "HARASSMENT", y);
        h.settle();
        assertTrue(h.exists("HARASSMENT", x), "the older honest report stays where it was made");
        assertEquals("REPORT", h.mine(honest).get(0).get("kind").asText());
        assertEquals("MODERATE", h.visible("HARASSMENT", y).get("confidence").asText());
        assertEquals("LOW", h.visible("HARASSMENT", x).get("confidence").asText());
        row("J2 capture (a newer, better-backed fake next to an honest report)", "Can a pair pull an honest report "
                        + "into their own signal (so a later removal of the fake takes it along)?",
                "No: only a NEWER lone report joins an OLDER signal; nothing older ever moves",
                "The pair's signal and the honest single stay apart (the pair alone costs exactly what S2b costs)");
    }

    @Test
    @Order(3)
    void spatialProbing_findsNothingBeforeTheProbeCounts_butAReportOnTheExactBlockRevealsItAtOnce() throws Exception {
        String area = area(2, 0);
        String x = AttackHarness.block(area, 12);
        honestSingle("ISOLATED_STRETCH", x);
        String id = h.visible("ISOLATED_STRETCH", x).get("id").asText();

        // Probes from a fresh account (24 h probation): three side blocks (the per-area limit is 3 a day).
        Account prober = h.freshAttacker();
        for (int k = 0; k < 3; k++) assertEquals(202, h.report(prober, "ISOLATED_STRETCH", side(x, area, k)));
        for (JsonNode own : h.mine(prober)) {
            assertEquals("REPORT", own.get("kind").asText());
            assertEquals("PENDING", own.get("status").asText());
            assertNotEquals(id, own.get("signalId").asText(), "each probe is its own signal, as with nothing next door");
        }
        h.job();
        JsonNode seen = h.areaAs(prober, area).get("signals").get(0);
        assertEquals("null", seen.get("yourContribution").toString(), "nothing ties the prober to the real report");
        assertEquals("AREA", seen.get("precision").asText());
        for (JsonNode own : h.mine(prober)) h.withdraw(prober, "ISOLATED_STRETCH", own.get("cell").asText());
        assertEquals("LOW", h.visible("ISOLATED_STRETCH", x).get("confidence").asText(), "and no trace is left");

        // MEASURED, pre-existing since Phase 6 (not caused by 9D): a report on the EXACT hidden block merges into it at
        // once, and the prober's own view says so before anything counts.
        Account exact = h.freshAttacker();
        assertEquals(202, h.report(exact, "ISOLATED_STRETCH", x));
        JsonNode revealed = h.areaAs(exact, area).get("signals").get(0);
        assertEquals("REPORT", revealed.get("yourContribution").asText(), "MEASURED WEAKNESS F11 (Phase 6 exact merge)");
        assertEquals(id, h.mine(exact).get(0).get("signalId").asText());
        assertEquals(204, h.withdraw(exact, "ISOLATED_STRETCH", x));
        assertEquals("AREA", h.visible("ISOLATED_STRETCH", x).get("precision").asText(), "silent: others see nothing");
        row("J3 spatial probing of a single report's hidden block", "Can probe reports find where a single report is, "
                        + "without leaving a trace?",
                "Side-block probes: NOTHING before the probe counts (own block, own id, no link to the real report), "
                        + "then only the k = 2 reveal a CONFIRM gives. MEASURED pre-existing weakness (Phase 6, F11): a "
                        + "probe on the EXACT block shows \"your report\" on the real one at once; withdrawn, no trace",
                "Nothing. The exact-block probe costs up to 32 reports per ~1 km area (3 per account per day)");
    }

    @Test
    @Order(4)
    void withdrawAndReReport_nextToASignal_neverCountsTheSamePersonTwice() throws Exception {
        String area = area(3, 0);
        String x = AttackHarness.block(area, 12);
        honestSingle("BROKEN_PATH", x);
        Account attacker = h.attacker();
        String y = side(x, area, 0);
        for (int round = 0; round < 4; round++) {
            assertEquals(202, h.report(attacker, "BROKEN_PATH", y));
            h.settle();
            JsonNode s = h.visible("BROKEN_PATH", x);
            assertEquals("SEVERAL", s.get("support").asText(), "round " + round + ": two people, never three");
            assertEquals("MODERATE", s.get("confidence").asText());
            assertEquals(1, h.mine(attacker).size());
            assertEquals(204, h.withdraw(attacker, "BROKEN_PATH", x));
        }
        assertEquals("LOW", h.visible("BROKEN_PATH", x).get("confidence").asText());
        row("J4 withdraw and re-report next to a signal", "Can one account count twice by cycling join and "
                        + "withdrawal?",
                "No: each cycle is ONE confirmation, gone when withdrawn. Like S12 (withdraw-and-redo) it can keep the "
                        + "support fresh; withdrawn rows never count against daily limits (Phase 6, unchanged by 9D)",
                "Never more than two people (the honest reporter and the account)");
    }

    @Test
    @Order(5)
    void otherCategoriesAndSafePlaces_nextToARealConcern_neverJoinOrCancelIt() throws Exception {
        String area = area(4, 0);
        String x = AttackHarness.block(area, 12);
        honestSingle("POOR_LIGHTING", x);
        h.report(h.attacker(), "WELL_LIT_BUSY", side(x, area, 0));
        h.report(h.attacker(), "ISOLATED_STRETCH", side(x, area, 1));
        h.settle();
        assertEquals(3, h.view(area).get("signals").size(), "nothing joined: different claims stay apart");
        assertEquals("LOW", h.visible("POOR_LIGHTING", x).get("confidence").asText());
        row("J5 conflicting categories next to a real report", "Can a different claim (a \"well lit\" safe place, "
                        + "another concern) placed next door merge with or cancel a real report?",
                "No join across categories, never for safe places; nothing cancels anything",
                "Three separate single reports, as before 9D");
    }

    @Test
    @Order(6)
    void oneAccountReportingNeighbouringBlocks_neverRaisesAConcern() throws Exception {
        String area = area(5, 0);
        String x = AttackHarness.block(area, 12);
        Account liar = h.attacker();
        h.report(liar, "HARASSMENT", x);
        h.settle();
        h.report(liar, "HARASSMENT", side(x, area, 0));
        h.report(liar, "HARASSMENT", side(x, area, 1));
        h.settle();
        assertEquals("NONE_CONFIRMED", h.picture(area).get("concern").asText(), "INVARIANT: one account, no concern");
        assertEquals(3, h.view(area).get("signals").size(), "one person's own reports never join each other");
        row("J6 one account, three neighbouring blocks", "Can one account confirm itself through the join?",
                "No: a lone report never joins a signal its own reporter already backs", "LITTLE_INFORMATION");
    }

    @Test
    @Order(7)
    void aRingThatJoinsInsteadOfConfirming_isStillFoundByCoordOne() throws Exception {
        List<Account> ring = new ArrayList<>();
        for (int i = 0; i < 3; i++) ring.add(h.attacker());
        int[][] pairs = {{0, 1}, {0, 2}, {1, 2}};
        for (int p = 0; p < pairs.length; p++) {
            String a = area(6, 2 * p);
            String x = AttackHarness.block(a, 12);
            String y = AttackHarness.block(a, 20);
            h.report(ring.get(pairs[p][0]), "POOR_LIGHTING", x);
            h.report(ring.get(pairs[p][1]), "ISOLATED_STRETCH", y);
        }
        h.settle();
        for (int p = 0; p < pairs.length; p++) {
            String a = area(6, 2 * p);
            h.report(ring.get(pairs[p][1]), "POOR_LIGHTING", side(AttackHarness.block(a, 12), a, 0));
            h.report(ring.get(pairs[p][0]), "ISOLATED_STRETCH", side(AttackHarness.block(a, 20), a, 0));
        }
        h.settle();
        int extraCare = 0;
        for (int p = 0; p < pairs.length; p++) if (h.outlook(area(6, 2 * p)).equals("TAKE_EXTRA_CARE")) extraCare++;
        assertEquals(3, extraCare, "the same as S4 with confirmations");
        List<Group> groups = h.coordinationGroups();
        assertEquals(1, groups.size(), "joined reports are confirmations: coord-1 sees them");
        assertEquals(3, groups.get(0).people().size());
        int underRulesTwo = 0;
        for (int p = 0; p < pairs.length; p++) {
            if (h.outlookUnder(Model.RULES_2, area(6, 2 * p), CellPatterns.NONE).equals("TAKE_EXTRA_CARE")) underRulesTwo++;
        }
        assertEquals(0, underRulesTwo);
        row("J7 ring of 3 using the join instead of confirmations", "Does the join hide a ring from the coordination "
                        + "check?",
                "No: a joined report is stored as a confirmation; coord-1 finds 1 group of 3 (SHADOW counts it)",
                "3 areas TAKE_EXTRA_CARE, as in S4 (rules-2, SHADOW: 0)");
    }

    // ---- Honest people ---------------------------------------------------------------------------------------------

    @Test
    @Order(10)
    void aHostel_fourResidentsReportingOneLaneFromTheirOwnBlocks_becomeOneStrongSignal() throws Exception {
        String area = area(10, 0);
        String x = AttackHarness.block(area, 12);
        List<Account> residents = new ArrayList<>();
        for (int i = 0; i < 4; i++) residents.add(h.honest());
        h.report(residents.get(0), "POOR_LIGHTING", x);
        h.settle();
        for (int i = 1; i < 4; i++) h.report(residents.get(i), "POOR_LIGHTING", side(x, area, i - 1));
        h.settle();
        JsonNode lane = h.visible("POOR_LIGHTING", x);
        assertEquals("HIGH", lane.get("confidence").asText(), "four independent people (all-standard: 4 = HIGH)");
        assertEquals("TAKE_CARE", h.outlook(area));
        String before = "Phase 8: four single LOW signals, LITTLE_INFORMATION";

        // The same residents, two more places: the third shared place links them (Phase 7, as in S15).
        String z = AttackHarness.block(area, 28);
        String w = AttackHarness.block(area, 4);
        h.report(residents.get(1), "ISOLATED_STRETCH", z);
        h.report(residents.get(2), "BROKEN_PATH", w);
        h.settle();
        for (int i : new int[] {0, 2, 3}) h.report(residents.get(i), "ISOLATED_STRETCH", side(z, area, i == 0 ? 0 : i - 1));
        for (int i : new int[] {0, 1, 3}) h.report(residents.get(i), "BROKEN_PATH", side(w, area, i == 0 ? 0 : i == 1 ? 1 : 2));
        h.settle();
        assertEquals(6, h.linkedPairs(residents), "3 shared places: Phase 7 counts them once (S15, unchanged)");
        assertEquals("LITTLE_INFORMATION", h.outlook(area));
        assertEquals(3, h.openCases(CaseOrigin.COORDINATION), "a human is asked, as for S15");
        assertEquals(3, h.dismissOpenCoordinationCases(h.moderator()));
        h.job();
        assertEquals("TAKE_EXTRA_CARE", h.outlook(area), "after a moderator found them fine (Option B)");
        row("J8 hostel: 4 residents report the same lane from their own blocks", "Does the join help a real group?",
                "One place: 3 lone reports joined → one HIGH signal (" + before + "). Three places: Phase 7 links "
                        + "them (as S15); 3 COORDINATION cases; after a moderator's 3 dismissals: counted again",
                "One place: TAKE_CARE. Three places: LITTLE_INFORMATION until a moderator looks, then TAKE_EXTRA_CARE");
    }

    @Test
    @Order(11)
    void aSparseLaunch_twoNewHonestAccounts_joinOnceTheirProbationEnds() throws Exception {
        String area = area(11, 0);
        String x = AttackHarness.block(area, 12);
        Account first = h.honest();
        jdbc.update("UPDATE users SET created_at = ? WHERE id = ?", Timestamp.valueOf(h.now()), first.id());
        Account second = h.honest();
        jdbc.update("UPDATE users SET created_at = ? WHERE id = ?", Timestamp.valueOf(h.now()), second.id());
        h.report(first, "UNSAFE_CROSSING", x);
        h.advance(Duration.ofHours(2));
        h.report(second, "UNSAFE_CROSSING", side(x, area, 0));
        h.settle();
        assertEquals("NO_INFORMATION", h.outlook(area), "nothing counts during the probation");
        h.advance(Duration.ofHours(23));
        h.settle();
        assertEquals("TAKE_CARE", h.outlook(area), "both count now; the newer report joined the older one");
        row("J9 sparse launch: two NEW accounts, minutes apart, side blocks", "Do honest reports made while both were "
                        + "still pending ever confirm each other?",
                "Yes, once both count (a join at report time would have missed this pair: nothing was visible)",
                "NO_INFORMATION for 24 h, then TAKE_CARE");
    }

    @Test
    @Order(12)
    void aStaleIncident_neverConfirmsANewOne() throws Exception {
        String area = area(12, 0);
        String x = AttackHarness.block(area, 12);
        honestSingle("HARASSMENT", x);
        h.advance(Duration.ofDays(3));
        h.report(h.honest(), "HARASSMENT", side(x, area, 0));
        h.settle();
        assertFalse(h.exists("HARASSMENT", x), "the old incident ended (3 days) and was deleted");
        assertEquals("LOW", h.visible("HARASSMENT", side(x, area, 0)).get("confidence").asText());
        row("J10 a stale incident next to a new one", "Can an expired report confirm a new one?",
                "No: only a signal anyone can see now is a target; the old one has ended", "LITTLE_INFORMATION");
    }

    @Test
    @Order(13)
    void rulesTwo_onHonestCorroboration_costsHonestPairsTheirExtraCare_andNothingElse() throws Exception {
        String pairArea = area(13, 0);
        Account a = h.honest();
        Account b = h.honest();
        h.report(a, "POOR_LIGHTING", AttackHarness.block(pairArea, 0));
        h.report(b, "ISOLATED_STRETCH", AttackHarness.block(pairArea, 1));
        h.settle();
        h.confirm(b, "POOR_LIGHTING", AttackHarness.block(pairArea, 0));
        h.confirm(a, "ISOLATED_STRETCH", AttackHarness.block(pairArea, 1));

        String trioArea = area(13, 2);
        Account c = h.honest();
        Account d = h.honest();
        Account e = h.honest();
        h.report(c, "POOR_LIGHTING", AttackHarness.block(trioArea, 0));
        h.report(d, "ISOLATED_STRETCH", AttackHarness.block(trioArea, 1));
        h.settle();
        h.confirm(d, "POOR_LIGHTING", AttackHarness.block(trioArea, 0));
        h.confirm(e, "ISOLATED_STRETCH", AttackHarness.block(trioArea, 1));
        h.settle();

        assertEquals("TAKE_EXTRA_CARE", h.outlook(pairArea));
        assertEquals("TAKE_CARE", h.outlookUnder(Model.RULES_2, pairArea, CellPatterns.NONE), "the honest pair's cost");
        assertEquals("TAKE_EXTRA_CARE", h.outlook(trioArea));
        assertEquals("TAKE_EXTRA_CARE", h.outlookUnder(Model.RULES_2, trioArea, CellPatterns.NONE));
        assertEquals(new AreaModelShadow.Comparison(2, 1, 0), h.shadow());
        row("J11 rules-2 on honest corroboration", "What would rules-2 cost honest people?",
                "Honest pair, two places: rules-1.1 TAKE_EXTRA_CARE, rules-2 TAKE_CARE (indistinguishable from S2's "
                        + "attacking pair). Three honest people: both TAKE_EXTRA_CARE. Shadow: 2 areas, 1 less cautious",
                "Nothing: rules-2 is SHADOW. Promoting it trades the honest pair's extra care for S2/S4's; there is no "
                        + "real-world data on how often each happens");
    }
}
