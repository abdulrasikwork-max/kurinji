package com.safesphere.backend.evaluation.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.community.entity.ModerationState;
import com.safesphere.backend.community.repository.ContributorStandingRepository;
import com.safesphere.backend.community.repository.PlaceSignalRepository;
import com.safesphere.backend.community.repository.SignalContributionRepository;
import com.safesphere.backend.community.repository.SignalFlagRepository;
import com.safesphere.backend.community.service.CommunityService;
import com.safesphere.backend.community.service.CoordinationPolicy.Group;
import com.safesphere.backend.community.service.CoordinationPolicy.Rule;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.community.service.TrustService;
import com.safesphere.backend.evaluation.simulation.AttackReport.Row;
import com.safesphere.backend.intelligence.repository.AreaPatternRepository;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.CellPatterns;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.CellPicture;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Information;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 9A attack simulation (docs/PHASE9_DESIGN.md §10.1, results in docs/PHASE9.md). Each scenario plays one abuse
 * pattern against the REAL API with the PRODUCTION timing switched back on (random 10-30 min publication delay, 24 h
 * new-account probation, 10 writes per 10 minutes), measures what it costs the attacker and what the server and a
 * viewer get, and asserts it, so a later change that makes an attack cheaper or dearer fails here and must be
 * re-measured on purpose. Rows go to target/attack-simulation/community-attacks.md (SIMULATED, not real-world data).
 *
 * Assertions are of two kinds: INVARIANTS that must always hold (one account never raises a concern; safe places never
 * cancel a concern), and MEASUREMENTS of today's behaviour, including known weaknesses (two accounts can reach "take
 * extra care"). A measurement of a weakness is not an endorsement: it pins the current cost until a fix is adopted.
 * Since 9D every candidate is PRODUCTION code (coord-1 in 9C; rules-2 in SHADOW, the havens-alone cap and the
 * neighbour join in 9D), so the simulation measures what the server would actually do. The 9D scenarios are in
 * NeighbourJoinSimulationTest.
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
class AttackSimulationTest {

    static final String SECTION = "community-attacks";
    private static final String[] CONCERNS = {"POOR_LIGHTING", "ISOLATED_STRETCH", "UNSAFE_CROSSING", "BROKEN_PATH",
            "HARASSMENT", "FOLLOWING", "THEFT_OR_SNATCHING"};

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
        AttackReport.write(SECTION, "Phase 9A: attacks on community safety intelligence");
    }

    private AttackHarness harness() {
        return new AttackHarness(mockMvc, objectMapper, clock, jdbc, community, moderation, trust, signals,
                contributions, cases, coordination, shadow);
    }

    private static String area(int row, int column) {
        return AttackHarness.area(row, column);
    }

    private static String block(String area, int k) {
        return AttackHarness.block(area, k);
    }

    private static void row(String scenario, String question, AttackHarness h, String response, String viewerSees) {
        AttackReport.add(SECTION, new Row(scenario, question, h.effort(), h.elapsed(), response, viewerSees));
    }

    // ---- Fake reports -------------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    void aLoneAccount_cannotRaiseAConcern_whateverItReports() throws Exception {
        String area = area(0, 0);
        Account liar = h.attacker();
        List<Integer> firstDay = new ArrayList<>();
        int k = 0;
        for (int i = 0; i < 4; i++) firstDay.add(h.report(liar, CONCERNS[k % CONCERNS.length], block(area, k++)));
        assertEquals(List.of(202, 202, 202, 429), firstDay, "three reports per area per day");
        assertTrue(h.lastRefusal.contains("already reported several things in this area"));
        for (int day = 1; day <= 2; day++) {
            h.advance(Duration.ofDays(1).plus(AttackHarness.SETTLE)); // the daily window counts from counts_from
            for (int i = 0; i < 3; i++) assertEquals(202, h.report(liar, CONCERNS[k % CONCERNS.length], block(area, k++)));
        }
        h.settle();

        JsonNode picture = h.picture(area);
        assertEquals("NONE_CONFIRMED", picture.get("concern").asText(), "INVARIANT: one account never raises a concern");
        assertEquals("LITTLE_INFORMATION", picture.get("outlook").asText());
        JsonNode seen = h.view(area).get("signals");
        assertEquals(9, seen.size());
        for (JsonNode s : seen) {
            assertEquals("LOW", s.get("confidence").asText());
            assertEquals("AREA", s.get("precision").asText(), "one person never pins a block");
        }
        row("S1 one account", "Can one account create a concern by reporting a lot?", h,
                "4th report in one area on one day refused (429); 9 single-person signals after 3 days, all LOW, "
                        + "placed only to ~1 km", "LITTLE_INFORMATION (\"unconfirmed reports\"); never a concern level");
    }

    // ---- Coordinated confirmations ------------------------------------------------------------------------------------

    @Test
    @Order(2)
    void twoAccounts_reachTakeExtraCare_withTwoConditions_andTakeCareWithOne() throws Exception {
        String area = area(1, 0);
        Account a = h.attacker();
        Account b = h.attacker();
        h.report(a, "POOR_LIGHTING", block(area, 0));
        h.report(b, "ISOLATED_STRETCH", block(area, 1));
        h.settle();
        h.confirm(b, "POOR_LIGHTING", block(area, 0));
        h.confirm(a, "ISOLATED_STRETCH", block(area, 1));
        h.settle();
        assertEquals("TAKE_EXTRA_CARE", h.outlook(area), "MEASURED WEAKNESS F5: two accounts, two conditions "
                + "(unchanged by 9D: rules-2 is in SHADOW)");
        assertFalse(h.linked(a, b), "they share only 2 signals (Phase 7 links from 3)");
        assertEquals(0, h.openCases(CaseOrigin.COORDINATION));
        assertEquals("TAKE_CARE", h.outlookUnder(Model.RULES_2, area, CellPatterns.NONE),
                "the production rules-2 (SHADOW) needs a third independent person");
        assertEquals(new AreaModelShadow.Comparison(1, 1, 0), h.shadow(), "the shadow counts this area, shows nothing");
        row("S2 pair, two conditions (F5)", "What does it take to make an area read \"take extra care\"?", h,
                "Both signals MODERATE, block precision; not linked; no case; nothing for a moderator. The rules-2 "
                        + "shadow logs 1 of 1 areas \"less cautious\"",
                "TAKE_EXTRA_CARE (active rules-1.1). Under rules-2 (SHADOW, not shown): TAKE_CARE");

        AttackHarness single = harness();
        String other = area(1, 2);
        Account c = single.attacker();
        Account d = single.attacker();
        single.report(c, "BROKEN_PATH", block(other, 0));
        single.settle();
        single.confirm(d, "BROKEN_PATH", block(other, 0));
        single.settle();
        assertEquals("TAKE_CARE", single.outlook(other));
        row("S2b pair, one condition", "...and \"take care\"?", single,
                "One MODERATE signal", "TAKE_CARE (k = 2: the accepted floor since Phase 6)");
    }

    @Test
    @Order(3)
    void ownerAndPuppet_countFullyForTwoSharedSignals_andOnceFromTheThird() throws Exception {
        String area = area(2, 0);
        Account owner = h.attacker();
        Account puppet = h.attacker();
        h.report(owner, "POOR_LIGHTING", block(area, 0));
        h.report(owner, "ISOLATED_STRETCH", block(area, 1));
        h.settle();
        h.confirm(puppet, "POOR_LIGHTING", block(area, 0));
        h.confirm(puppet, "ISOLATED_STRETCH", block(area, 1));
        h.settle();
        assertFalse(h.linked(owner, puppet));
        assertEquals("TAKE_EXTRA_CARE", h.outlook(area));

        h.report(owner, "UNSAFE_CROSSING", block(area, 2));
        h.settle();
        h.confirm(puppet, "UNSAFE_CROSSING", block(area, 2));
        h.settle();
        assertTrue(h.linked(owner, puppet), "3 shared live signals, all of their activity: linked");
        for (JsonNode s : h.view(area).get("signals")) {
            assertEquals("LOW", s.get("confidence").asText());
            assertTrue(s.get("reasons").toString().contains("LINKED_SUPPORT_COUNTED_ONCE"));
        }
        assertEquals("LITTLE_INFORMATION", h.outlook(area));
        assertEquals(3, h.openCases(CaseOrigin.COORDINATION));
        row("S3 owner + puppet", "When do two accounts that back each other stop counting twice?", h,
                "2 shared signals: both MODERATE. The 3rd shared signal links them: all three LOW (counted once), "
                        + "3 COORDINATION cases opened",
                "TAKE_EXTRA_CARE with 2; LITTLE_INFORMATION from the 3rd (the attacker's optimum is 2 per pair)");
    }

    @ParameterizedTest(name = "ring of {0}")
    @ValueSource(ints = {3, 4, 5, 6})
    @Order(4)
    void aRotatingRing_marksOneAreaPerPair_andPhase7NeverNoticesIt(int n) throws Exception {
        List<Account> ring = new ArrayList<>();
        for (int i = 0; i < n; i++) ring.add(h.attacker());
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) pairs.add(new int[] {i, j});
        List<String> areas = new ArrayList<>();
        for (int p = 0; p < pairs.size(); p++) areas.add(area(10 + p / 5, p % 5));

        for (int p = 0; p < pairs.size(); p++) {
            h.report(ring.get(pairs.get(p)[0]), "POOR_LIGHTING", block(areas.get(p), 0));
            h.report(ring.get(pairs.get(p)[1]), "ISOLATED_STRETCH", block(areas.get(p), 1));
        }
        h.settle();
        for (int p = 0; p < pairs.size(); p++) {
            h.confirm(ring.get(pairs.get(p)[1]), "POOR_LIGHTING", block(areas.get(p), 0));
            h.confirm(ring.get(pairs.get(p)[0]), "ISOLATED_STRETCH", block(areas.get(p), 1));
        }
        h.settle();

        int marked = 0;
        int markedUnderRulesTwo = 0;
        for (String a : areas) {
            if (h.outlook(a).equals("TAKE_EXTRA_CARE")) marked++;
            if (h.outlookUnder(Model.RULES_2, a, CellPatterns.NONE).equals("TAKE_EXTRA_CARE")) markedUnderRulesTwo++;
        }
        assertEquals(n * (n - 1) / 2, marked, "MEASURED WEAKNESS F6: one area per pair");
        assertEquals(0, markedUnderRulesTwo, "rules-2 (SHADOW): every one of those areas would read TAKE_CARE");
        assertEquals(0, h.linkedPairs(ring), "each pair shares only 2 signals");
        List<Group> groups = h.coordinationGroups();
        assertEquals(1, groups.size());
        assertEquals(Rule.SUPPORT_CORE, groups.get(0).rule());
        assertEquals(n, groups.get(0).people().size());
        CoordinationCheck.Run shadow = h.coordinationRun();
        assertEquals(new CoordinationCheck.Run(1, 0), shadow, "SHADOW (the shipped default): counted, nothing opened");
        assertEquals(0, h.openCases(CaseOrigin.COORDINATION));
        row("S4 rotating ring of " + n + " (F6)", "How far does a ring of accounts that never repeat a pair get?", h,
                marked + " areas marked; " + 2 * (n - 1) + " writes per account; Phase 7: 0 links. coord-1 R1 "
                        + "(production, 9C): 1 group of " + n + "; SHADOW counts it, ACTIVE would open 1 case",
                marked + " areas read TAKE_EXTRA_CARE (under rules-2, SHADOW: 0; all TAKE_CARE)");
    }

    @ParameterizedTest(name = "padding {0}")
    @ValueSource(ints = {4, 5})
    @Order(5)
    void aPaddedPair_staysUnlinked_onceItsPaddingIsLargerThanWhatItShares(int padding) throws Exception {
        // Honest, popular places to pad with: each reported by a local and confirmed by two passers-by. The two
        // attackers pad on DIFFERENT places (padding on the same ones would be shared too, and link them anyway).
        String home = area(20, 0);
        String[] kinds = {"POOR_LIGHTING", "BROKEN_PATH", "UNSAFE_CROSSING", "WELL_LIT_BUSY", "HELP_POINT"};
        String[] places = new String[10];
        for (int i = 0; i < places.length; i++) {
            places[i] = kinds[i % kinds.length];
            h.report(h.honest(), places[i], block(home, i));
        }
        // The fake: four signals in two far-apart areas, each backed by exactly the pair.
        String f1 = area(20, 2);
        String f2 = area(20, 4);
        Account p = h.attacker();
        Account q = h.attacker();
        h.report(p, "POOR_LIGHTING", block(f1, 0));
        h.report(p, "ISOLATED_STRETCH", block(f2, 0));
        h.report(q, "ISOLATED_STRETCH", block(f1, 1));
        h.report(q, "POOR_LIGHTING", block(f2, 1));
        h.settle();
        for (int i = 0; i < places.length; i++) {
            h.confirm(h.honest(), places[i], block(home, i));
            h.confirm(h.honest(), places[i], block(home, i));
        }
        for (int i = 0; i < padding; i++) {
            h.confirm(p, places[i], block(home, i));
            h.confirm(q, places[5 + i], block(home, 5 + i));
        }
        h.confirm(q, "POOR_LIGHTING", block(f1, 0));
        h.confirm(q, "ISOLATED_STRETCH", block(f2, 0));
        h.confirm(p, "ISOLATED_STRETCH", block(f1, 1));
        h.confirm(p, "POOR_LIGHTING", block(f2, 1));
        h.settle();

        boolean linked = h.linked(p, q);
        List<Group> pairs = h.coordinationGroups().stream().filter(g -> g.rule() == Rule.EXCLUSIVE_PAIR).toList();
        if (padding <= 4) {
            assertTrue(linked, "4 shared out of 8: linked (shared * 2 >= activity)");
            assertEquals("LITTLE_INFORMATION", h.outlook(f1));
            assertEquals(4, h.openCases(CaseOrigin.COORDINATION));
            assertTrue(pairs.isEmpty(), "a linked pair is Phase 7's job (R0)");
        } else {
            assertFalse(linked, "MEASURED WEAKNESS: 4 shared out of 9 escapes the 'half of activity' rule");
            assertEquals("TAKE_EXTRA_CARE", h.outlook(f1));
            assertEquals("TAKE_EXTRA_CARE", h.outlook(f2));
            assertEquals("TAKE_CARE", h.outlookUnder(Model.RULES_2, f1, CellPatterns.NONE), "rules-2 (SHADOW)");
            assertEquals(0, h.openCases(CaseOrigin.COORDINATION));
            assertEquals(1, pairs.size());
            assertEquals(Set.of(p.id(), q.id()), pairs.get(0).people());
        }
        row("S5 padded pair, padding " + padding, "Can a pair escape linking by also confirming popular real places?",
                h, linked ? "Linked: fakes LOW, 4 COORDINATION cases" : "Not linked: 4 fakes MODERATE, no case. "
                        + "coord-1 R2 (exclusive pair, production): found",
                linked ? "LITTLE_INFORMATION" : "2 areas TAKE_EXTRA_CARE (padding needed = shared signals + 1)");
    }

    // ---- Lures (fake safe places) -------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} accounts")
    @ValueSource(ints = {2, 4})
    @Order(6)
    void fakeSafePlaces_neverMakeAnUnknownAreaReadNoConfirmedConcerns(int accounts) throws Exception {
        String area = area(3, accounts);
        assertEquals("NO_INFORMATION", h.outlook(area));
        // Every pair of accounts backs TWO fake safe places (each reports one, confirms the other): no pair shares
        // the 3 that Phase 7 links on (a rotating ring, as in S4).
        List<Account> ring = new ArrayList<>();
        for (int i = 0; i < accounts; i++) ring.add(h.attacker());
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < accounts; i++) for (int j = i + 1; j < accounts; j++) pairs.add(new int[] {i, j});
        String[] havens = {"HELP_POINT", "OPEN_LATE_STAFFED", "WELL_LIT_BUSY"};
        int places = 2 * pairs.size();
        for (int p = 0; p < pairs.size(); p++) {
            h.report(ring.get(pairs.get(p)[0]), havens[(2 * p) % 3], block(area, 2 * p));
            h.report(ring.get(pairs.get(p)[1]), havens[(2 * p + 1) % 3], block(area, 2 * p + 1));
        }
        h.settle();
        for (int p = 0; p < pairs.size(); p++) {
            h.confirm(ring.get(pairs.get(p)[1]), havens[(2 * p) % 3], block(area, 2 * p));
            h.confirm(ring.get(pairs.get(p)[0]), havens[(2 * p + 1) % 3], block(area, 2 * p + 1));
        }
        h.settle();
        assertEquals(0, h.linkedPairs(ring));
        JsonNode cell = h.cellPicture(area);
        assertEquals("LITTLE_INFORMATION", cell.get("outlook").asText(),
                "FIXED IN 9D (rules-1.1): before, 2 accounts made it NO_CONFIRMED_CONCERNS");
        assertEquals("LITTLE", cell.get("information").asText(), "safe places alone count once");
        assertTrue(h.picture(area).get("reasons").toString().contains("SAFE_PLACES_NEARBY"), "they are still shown");
        assertEquals("rules-1.1", h.picture(area).get("modelVersion").asText());
        row("S6 fake safe places, " + accounts + " accounts", "Can fake safe places make an unknown area look known "
                + "and fine?", h, places + " confirmed havens. Since 9D (rules-1.1) safe places ALONE count once: "
                + "information LITTLE however many there are",
                "LITTLE_INFORMATION + \"places to go for help are known nearby\" (Phase 8 with 2 accounts: "
                        + "NO_CONFIRMED_CONCERNS)");
    }

    @Test
    @Order(7)
    void fakeSafePlaces_neverCancelAConfirmedConcern() throws Exception {
        String area = area(3, 2);
        Account a = h.attacker();
        Account b = h.attacker();
        h.report(h.honest(), "POOR_LIGHTING", block(area, 0));
        h.report(a, "WELL_LIT_BUSY", block(area, 0));
        h.report(a, "HELP_POINT", block(area, 1));
        h.settle();
        h.confirm(h.honest(), "POOR_LIGHTING", block(area, 0));
        h.confirm(b, "WELL_LIT_BUSY", block(area, 0));
        h.confirm(b, "HELP_POINT", block(area, 1));
        h.settle();
        JsonNode picture = h.picture(area);
        assertEquals("TAKE_CARE", h.outlook(area), "INVARIANT: a safe place never cancels a confirmed concern");
        assertTrue(picture.get("reasons").toString().contains("CONFLICTING_REPORTS"));
        row("S7 fake safe place on a real concern", "Can fake safe places cancel a real warning?", h,
                "Real POOR_LIGHTING stays confirmed; a confirmed WELL_LIT_BUSY in the same block only adds a conflict",
                "TAKE_CARE + \"people disagree\" (unchanged level)");
    }

    // ---- Silencing real warnings --------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} supporters")
    @ValueSource(ints = {2, 3, 5})
    @Order(8)
    void flagging_hidesARealWarning_onceAsManyIndependentPeopleFlagItAsBackIt(int supporters) throws Exception {
        String area = area(4, supporters);
        String cell = block(area, 0);
        realWarning(supporters, "POOR_LIGHTING", cell);
        int flaggersNeeded = 0;
        for (int m = 1; m <= supporters + 1; m++) {
            assertEquals(202, h.flag(h.attacker(), "POOR_LIGHTING", cell, "FALSE_INFORMATION"));
            if (h.moderationOf("POOR_LIGHTING", cell) == ModerationState.QUARANTINED) {
                flaggersNeeded = m;
                break;
            }
        }
        assertEquals(Math.max(2, supporters), flaggersNeeded, "quarantine needs flag weight >= support weight");
        assertNull(h.visible("POOR_LIGHTING", cell), "hidden from everyone until a moderator decides");
        assertEquals(1, h.openCases(CaseOrigin.FLAGS));
        row("S8 flag bombing, " + supporters + " supporters", "How many accounts does it take to hide a real warning?",
                h, "Quarantined after " + flaggersNeeded + " flags; a FLAGS case waits for a moderator (reversible)",
                "The warning disappears until a human restores it");
    }

    @Test
    @Order(9)
    void freshAccounts_flags_countOnlyAfterTheirProbation() throws Exception {
        String cell = block(area(4, 8), 0);
        realWarning(2, "POOR_LIGHTING", cell);
        h.flag(h.freshAttacker(), "POOR_LIGHTING", cell, "FALSE_INFORMATION");
        h.flag(h.freshAttacker(), "POOR_LIGHTING", cell, "FALSE_INFORMATION");
        assertEquals(ModerationState.NONE, h.moderationOf("POOR_LIGHTING", cell));
        assertNotNull(h.visible("POOR_LIGHTING", cell));
        h.advance(Duration.ofHours(24));
        h.job();
        assertEquals(ModerationState.QUARANTINED, h.moderationOf("POOR_LIGHTING", cell));
        row("S9 flag bombing with new accounts", "Do freshly made accounts silence a warning at once?", h,
                "No: their flags count only after the 24 h probation, then the job quarantines",
                "Visible for 24 h, then hidden until a moderator decides");
    }

    @ParameterizedTest(name = "{0} supporters")
    @ValueSource(ints = {2, 3})
    @Order(10)
    void noLongerTrue_erasesARealWarning_withoutAHuman_onceAsManyPeopleDisputeItAsBackIt(int supporters)
            throws Exception {
        String cell = block(area(5, supporters), 0);
        realWarning(supporters, "BROKEN_PATH", cell);
        int disputersNeeded = 0;
        for (int m = 1; m <= supporters + 1; m++) {
            h.dispute(h.attacker(), "BROKEN_PATH", cell);
            h.settle();
            if (!h.exists("BROKEN_PATH", cell)) {
                disputersNeeded = m;
                break;
            }
            assertTrue(h.visible("BROKEN_PATH", cell).get("disputed").asBoolean(), "shown as 'some say this changed'");
        }
        assertEquals(Math.max(2, supporters), disputersNeeded);
        assertEquals(0, cases.count(), "retirement involves no moderator");
        row("S10 \"no longer true\", " + supporters + " supporters", "How many accounts does it take to ERASE a real "
                        + "warning?", h, "Retired and deleted after " + disputersNeeded + " disputers; no case, no "
                        + "human review (by design since Phase 7)",
                "\"Some say this has changed\", then the warning is gone for good");
    }

    // ---- Withdrawals --------------------------------------------------------------------------------------------------

    @Test
    @Order(11)
    void withdrawingDuringACoordinationReview_noLongerTellsTheOwnerAnything() throws Exception {
        String area = area(6, 0);
        Account owner = h.attacker();
        Account puppet = h.attacker();
        for (int i = 0; i < 3; i++) h.report(owner, CONCERNS[i], block(area, i));
        h.report(owner, "HELP_POINT", block(area(6, 2), 0));
        h.settle();
        for (int i = 0; i < 3; i++) h.confirm(puppet, CONCERNS[i], block(area, i));
        h.settle();
        assertEquals(3, h.openCases(CaseOrigin.COORDINATION));
        assertEquals(204, h.withdraw(owner, CONCERNS[0], block(area, 0)),
                "FIXED IN 9C (F8): a case the system opened by itself no longer locks withdrawal");
        assertEquals(204, h.withdraw(owner, "HELP_POINT", block(area(6, 2), 0)), "control: no case at all");
        row("S11 withdrawal as a detection oracle (F8)", "Can a ring find out that it was detected?", h,
                "No longer (9C): withdrawing during a COORDINATION review answers 204 like any other withdrawal. "
                        + "Before 9C it answered 409 \"This signal is being reviewed\". Flags still lock it",
                "Nothing");
    }

    @Test
    @Order(12)
    void withdrawAndRedo_keepsAFakeIncidentTodayLongPastItsLifetime() throws Exception {
        String cell = block(area(7, 0), 0);
        String control = block(area(7, 2), 0);
        Account a = h.attacker();
        Account b = h.attacker();
        AttackHarness lazy = harness();
        Account c = lazy.attacker();
        Account d = lazy.attacker();
        h.report(a, "HARASSMENT", cell);
        lazy.report(c, "HARASSMENT", control);
        h.settle();
        h.confirm(b, "HARASSMENT", cell);
        lazy.confirm(d, "HARASSMENT", control);
        h.settle();
        for (int day = 1; day <= 4; day++) {
            h.advance(Duration.ofDays(1).minus(AttackHarness.SETTLE.multipliedBy(2)));
            h.withdraw(b, "HARASSMENT", cell);
            assertEquals(202, h.confirm(b, "HARASSMENT", cell));
            h.settle();
            h.withdraw(a, "HARASSMENT", cell);
            assertEquals(202, h.report(a, "HARASSMENT", cell));
            h.settle();
        }
        assertFalse(lazy.exists("HARASSMENT", control), "control: an incident ends after 3 days");
        JsonNode kept = h.visible("HARASSMENT", cell);
        assertNotNull(kept, "MEASURED: kept alive by withdraw-and-redo");
        assertEquals("TODAY", kept.get("freshness").asText());
        assertEquals("TAKE_CARE", h.outlook(area(7, 0)));
        assertTrue(h.picture(area(7, 0)).get("reasons").toString().contains("CONFIRMED_INCIDENT_TODAY"));
        row("S12 withdraw-and-redo", "Can a pair keep a fake incident \"today\" past its 3-day lifetime?", h,
                "Yes: 4 days later still MODERATE and TODAY (a pair that never refreshes: gone after 3 days). The "
                        + "server cannot tell this from honest people confirming again",
                "TAKE_CARE + \"confirmed incident today\", indefinitely");
    }

    // ---- Pattern memory -----------------------------------------------------------------------------------------------

    @Test
    @Order(13)
    void aPairBuildsARecurringPattern_thenOneConfirmedIncidentMeansTakeExtraCare() throws Exception {
        String area = area(8, 0);
        Account a = h.attacker();
        Account b = h.attacker();
        int episodes = 0;
        boolean recurring = false;
        while (!recurring && episodes < 6) {
            String cell = block(area, episodes);
            h.report(a, "HARASSMENT", cell);
            h.settle();
            h.confirm(b, "HARASSMENT", cell);
            h.settle();                                             // the job marks it confirmed
            h.advance(Duration.ofDays(3).plusMinutes(1));
            h.job();                                                // it ends naturally: an episode
            episodes++;
            recurring = h.picture(area).get("reasons").toString().contains("RECURRING_CONCERNS");
            if (!recurring) h.advance(Duration.ofDays(4));         // next week (one episode per week counts)
        }
        assertTrue(recurring);
        assertEquals(3, episodes);
        assertFalse(h.linked(a, b), "incidents expire, so the pair never shares 3 live signals");
        assertEquals("LITTLE_INFORMATION", h.outlook(area), "a pattern alone never raises the level");
        String time = h.elapsed();

        h.report(a, "FOLLOWING", block(area, 10));
        h.settle();
        h.confirm(b, "FOLLOWING", block(area, 10));
        h.settle();
        assertEquals("TAKE_EXTRA_CARE", h.outlook(area), "MEASURED: rule (c) with a self-made history");
        assertEquals("TAKE_EXTRA_CARE", h.outlookUnder(Model.RULES_2, area, new CellPatterns(true, false)),
                "rules-2 does not touch rule (c)");
        row("S13 pattern poisoning", "Can one pair build a \"recurring\" history, then use it?", h,
                "3 weekly confirmed incidents by the same pair → RECURRING after " + time + "; never linked. Then ONE "
                        + "confirmed incident → TAKE_EXTRA_CARE (rule c). rules-2 does not change this",
                "\"Concerns were confirmed here repeatedly\", then TAKE_EXTRA_CARE");
    }

    // ---- Grid boundaries ----------------------------------------------------------------------------------------------

    @Test
    @Order(14)
    void twoHonestPeople_reportingOneStreetFromSideBlocks_nowConfirmEachOther_cornerBlocksStillDoNot() throws Exception {
        String area = area(9, 0);
        String x = block(area, 12);
        String side = AttackHarness.sides(x).stream().filter(c -> c.startsWith(area)).findFirst().orElseThrow();
        h.report(h.honest(), "POOR_LIGHTING", x);
        h.report(h.honest(), "POOR_LIGHTING", side);
        h.settle();
        assertEquals("TAKE_CARE", h.outlook(area), "FIXED IN 9D (F7): the newer lone report joined the older one");
        JsonNode joined = h.visible("POOR_LIGHTING", x);
        assertEquals("MODERATE", joined.get("confidence").asText());
        assertEquals("BLOCK", joined.get("precision").asText());
        assertFalse(h.exists("POOR_LIGHTING", side));

        String other = area(9, 2);
        String y = block(other, 12);
        String corner = AttackHarness.corners(y).stream().filter(c -> c.startsWith(other)).findFirst().orElseThrow();
        h.report(h.honest(), "POOR_LIGHTING", y);
        h.report(h.honest(), "POOR_LIGHTING", corner);
        h.settle();
        assertEquals("LITTLE_INFORMATION", h.outlook(other), "KNOWN LIMITATION: corner blocks are not joined");

        String same = area(9, 4);
        h.report(h.honest(), "POOR_LIGHTING", block(same, 12));
        h.report(h.honest(), "POOR_LIGHTING", block(same, 12));
        h.settle();
        assertEquals("TAKE_CARE", h.outlook(same), "control: the same block merges at once");
        AttackReport.add(SECTION, new Row("S14 grid boundary (F7, 9D)", "Do two honest reports of one street count as "
                + "confirmation?", "2 honest people (no attacker)", h.elapsed(),
                "Side blocks: since 9D the newer lone report joins the older one once it counts (one MODERATE signal). "
                        + "Corner blocks: still two single LOW signals. Same block: one MODERATE signal",
                "Side: TAKE_CARE (was LITTLE_INFORMATION). Corner: LITTLE_INFORMATION. Same block: TAKE_CARE "
                        + "(how often each happens: grid-boundary.md)"));
    }

    // ---- Honest communities (false positives / false negatives) ------------------------------------------------------

    @ParameterizedTest(name = "{0} shared places")
    @ValueSource(ints = {2, 3})
    @Order(15)
    void anHonestGroupBackingTheSamePlaces_countsAsOnePersonFromTheThirdSharedPlace(int places) throws Exception {
        String area = area(9, 4 + places);
        List<Account> residents = new ArrayList<>();
        for (int i = 0; i < 4; i++) residents.add(h.honest());
        for (int s = 0; s < places; s++) h.report(residents.get(s % 4), CONCERNS[s], block(area, s));
        h.settle();
        for (int s = 0; s < places; s++) {
            for (int r = 0; r < 4; r++) if (r != s % 4) h.confirm(residents.get(r), CONCERNS[s], block(area, s));
        }
        h.settle();
        int linked = h.linkedPairs(residents);
        String outlook = h.outlook(area);
        String afterReview = "";
        if (places <= 2) {
            assertEquals(0, linked);
            assertEquals("TAKE_EXTRA_CARE", outlook);
        } else {
            assertEquals(6, linked, "every pair of residents backs the same 3 places and nothing else");
            assertEquals("LITTLE_INFORMATION", outlook, "MEASURED FALSE NEGATIVE of the independence rule");
            assertEquals(places, h.openCases(CaseOrigin.COORDINATION));
            // Phase 9C.1 (S15 Option B): a moderator looks at each case and finds the residents are separate people.
            assertEquals(places, h.dismissOpenCoordinationCases(h.moderator()));
            h.job();
            afterReview = h.outlook(area);
            assertEquals("TAKE_EXTRA_CARE", afterReview, "after the dismissals their real warnings count again");
            assertEquals(6, h.linkedPairs(residents), "Phase 7 still links them: the decision is per signal");
            assertEquals(0, h.openCases(CaseOrigin.COORDINATION), "and nobody is asked about it again");
        }
        assertTrue(h.coordinationGroups().isEmpty(), "coord-1: one neighbourhood, quiet");
        AttackReport.add(SECTION, new Row("S15 honest group, " + places + " shared places", "What happens to a real "
                + "group (a hostel, a street) that backs the same places?", "4 honest residents (no attacker)",
                h.elapsed(), linked == 0 ? "All signals HIGH" : "All 6 pairs linked: every signal LOW (counted once), "
                + places + " COORDINATION cases (Phase 7). coord-1: quiet (one neighbourhood). A moderator DISMISSES "
                + "each case (9C.1, Option B): those signals count the residents separately again",
                outlook + (linked == 0 ? "" : " (their real warnings stop counting); after the "
                + places + " dismissals: " + afterReview)));
    }

    @Test
    @Order(16)
    void aSparseHonestLaunch_keepsTheCandidateDetectorsQuiet() throws Exception {
        Random random = new Random(9_016L);
        String region = GeoCell.encode(AttackHarness.ORIGIN_LAT - 0.5, AttackHarness.ORIGIN_LNG - 0.5, 5);
        List<String> areas = GeoCell.children(region);
        String[] categories = {"POOR_LIGHTING", "ISOLATED_STRETCH", "UNSAFE_CROSSING", "BROKEN_PATH", "HARASSMENT",
                "WELL_LIT_BUSY", "OPEN_LATE_STAFFED", "HELP_POINT"};
        List<Account> people = new ArrayList<>();
        Map<Long, String> home = new HashMap<>();
        for (int i = 0; i < 30; i++) {
            Account person = h.honest();
            people.add(person);
            home.put(person.id(), areas.get(random.nextInt(areas.size())));
            String category = categories[random.nextInt(categories.length)];
            h.report(person, category, block(home.get(person.id()), random.nextInt(32)));
        }
        h.settle();
        for (Account person : people) {
            for (var s : signals.findByAreaCellIn(List.of(home.get(person.id())))) {
                if (random.nextDouble() < 0.3) h.confirm(person, s.getCategory().name(), s.getCell());
            }
        }
        h.settle();

        SupportSnapshot snapshot = h.support();
        Map<Information, Integer> information = new EnumMap<>(Information.class);
        for (CellPicture p : h.pictures(Model.RULES_1_1, areas, Map.of())) information.merge(p.information(), 1, Integer::sum);
        long confirmed = snapshot.signals().stream().filter(SupportSnapshot.Entry::confirmed).count();
        assertTrue(h.coordinationGroups().isEmpty(), "no honest group looks coordinated here");
        Set<String> withSignals = new HashSet<>();
        snapshot.signals().forEach(e -> withSignals.add(e.areaCell()));
        AttackReport.add(SECTION, new Row("S16 sparse honest launch (synthetic model)", "With 30 honest people in a "
                + "~5 km region (1 report each, 30 % chance to confirm what is in their area), what do people see?",
                "30 honest people (no attacker)", h.elapsed(),
                snapshot.signals().size() + " visible signals, " + confirmed + " confirmed; " + h.linkedPairs(people)
                        + " linked pairs; " + h.openCases(CaseOrigin.COORDINATION) + " COORDINATION cases; coord-1: "
                        + "0 findings",
                "Of 32 area cells: " + information + " (the model's assumptions decide these numbers)"));
    }

    // ---- Scraping and rate limits -------------------------------------------------------------------------------------

    @Test
    @Order(17)
    void oneAccount_collectsAWholeRegionWithAreaLookups_regionPacksAreTighter() throws Exception {
        Account scraper = h.attacker();
        String region = GeoCell.encode(AttackHarness.ORIGIN_LAT + 0.5, AttackHarness.ORIGIN_LNG + 0.5, 5);
        Set<String> uncovered = new TreeSet<>(GeoCell.children(region));
        int lookups = 0;
        while (!uncovered.isEmpty()) {
            String centre = uncovered.stream().max((x, y) -> {
                int byCover = Integer.compare(cover(x, uncovered), cover(y, uncovered));
                return byCover != 0 ? byCover : y.compareTo(x);
            }).orElseThrow();
            MvcResult result = h.get(scraper, "/api/community/area?cell=" + centre);
            assertEquals(200, result.getResponse().getStatus());
            for (JsonNode cell : h.json(result).get("picture").get("areas")) uncovered.remove(cell.get("cell").asText());
            lookups++;
        }
        int accepted = lookups;
        for (String next : GeoCell.children(GeoCell.encode(AttackHarness.ORIGIN_LAT + 0.6, AttackHarness.ORIGIN_LNG + 0.5, 5))) {
            if (h.get(scraper, "/api/community/area?cell=" + next).getResponse().getStatus() != 200) break;
            accepted++;
        }
        assertEquals(15, accepted, "15 different areas per hour");
        int perDay = (24 * 15) / lookups;

        List<Integer> packs = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            String r = GeoCell.encode(AttackHarness.ORIGIN_LAT + 1 + i * 0.1, AttackHarness.ORIGIN_LNG + 1, 5);
            packs.add(h.get(scraper, "/api/intelligence/region?cell=" + r).getResponse().getStatus());
        }
        assertEquals(List.of(200, 200, 200, 200, 200, 200, 429), packs);
        row("S17 scraping", "How much can one account collect?", h,
                lookups + " area look-ups cover a whole ~5 km region (32 area cells); 15 different areas per hour, "
                        + "then 429. Derived (not run for 24 h): ~" + perDay + " regions a day by area look-ups vs 6 "
                        + "by region packs",
                "Every visible signal at its shown precision (public by design)");
    }

    private static int cover(String centre, Set<String> uncovered) {
        int n = 0;
        for (String c : GeoCell.neighbourhood(centre)) if (uncovered.contains(c)) n++;
        return n;
    }

    @Test
    @Order(18)
    void scriptedWrites_areSlowedByTheBurstAndDailyLimits() throws Exception {
        String area = area(11, 0);
        for (int r = 0; r < 4; r++) {
            Account local = h.honest();
            for (int i = 0; i < 3; i++) h.report(local, CONCERNS[(r * 3 + i) % CONCERNS.length], block(area, r * 3 + i));
        }
        h.settle();
        Account bot = h.attacker();
        h.waitOutBursts = false;
        for (int i = 0; i < 10; i++) {
            assertEquals(202, h.confirm(bot, CONCERNS[i % CONCERNS.length], block(area, i)));
        }
        assertEquals(429, h.confirm(bot, CONCERNS[10 % CONCERNS.length], block(area, 10)));
        assertTrue(h.lastRefusal.contains("very quickly"));
        h.advance(Duration.ofMinutes(10));
        assertEquals(202, h.confirm(bot, CONCERNS[10 % CONCERNS.length], block(area, 10)));

        Account reporter = h.attacker();
        for (int i = 0; i < 10; i++) assertEquals(202, h.report(reporter, "POOR_LIGHTING", block(area(12, i), 0)));
        assertEquals(429, h.report(reporter, "POOR_LIGHTING", block(area(12, 10), 0)));
        assertTrue(h.lastRefusal.contains("today's limit for reports"));
        h.waitOutBursts = true;
        row("S18 scripted writes", "How fast can a script write?", h,
                "11th write within 10 minutes refused (429), accepted 10 minutes later; 11th report in a day refused "
                        + "(429). Also configured and tested elsewhere: 3 reports per area per day, 30 contributions "
                        + "and 10 flags per day",
                "Nothing (limits are invisible to viewers)");
    }

    // ---- Helpers ------------------------------------------------------------------------------------------------------

    /** A real warning backed by [supporters] honest, independent people (one report, the rest confirmations). */
    private void realWarning(int supporters, String category, String cell) throws Exception {
        h.report(h.honest(), category, cell);
        h.settle();
        for (int i = 1; i < supporters; i++) h.confirm(h.honest(), category, cell);
        h.settle();
        JsonNode seen = h.visible(category, cell);
        assertNotNull(seen);
        assertEquals(supporters >= 2 ? "BLOCK" : "AREA", seen.get("precision").asText());
    }
}
