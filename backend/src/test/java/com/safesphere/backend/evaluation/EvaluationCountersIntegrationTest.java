package com.safesphere.backend.evaluation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.community.entity.SignalCategory;
import com.safesphere.backend.community.repository.ContributorStandingRepository;
import com.safesphere.backend.community.repository.PlaceSignalRepository;
import com.safesphere.backend.community.repository.SignalContributionRepository;
import com.safesphere.backend.community.repository.SignalFlagRepository;
import com.safesphere.backend.community.service.CommunityService;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.config.EvaluationProperties;
import com.safesphere.backend.evaluation.entity.EvaluationCounter;
import com.safesphere.backend.evaluation.entity.Metric;
import com.safesphere.backend.evaluation.repository.EvaluationCounterRepository;
import com.safesphere.backend.evaluation.service.CaseTrigger;
import com.safesphere.backend.evaluation.service.OutcomeRecorder;
import com.safesphere.backend.evaluation.service.WeeklySnapshot;
import com.safesphere.backend.moderation.entity.ModerationCase;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseOrigin;
import com.safesphere.backend.moderation.entity.ModerationEnums.CoordinationRule;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 9B outcome counters through the real API (docs/PHASE9.md §27): each lifecycle event is counted once, after its
 * transaction commits, against the right dimension; a rolled-back one counts nothing; nothing stored can hold a place, a
 * person or a fine time; the weekly snapshot reads exactly what anyone can see; only an ADMIN can read the counters.
 * Synthetic open-ocean cells. Shipped modes (coord-1 and rules-2 in SHADOW).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class EvaluationCountersIntegrationTest {

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
    @Autowired private CommunityService community;
    @Autowired private ModerationService moderation;
    @Autowired private EvaluationCounterRepository counters;
    @Autowired private OutcomeRecorder outcomes;
    @Autowired private WeeklySnapshot snapshot;
    @Autowired private PlatformTransactionManager transactions;

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
        counters.deleteAll();
        api = new CommunityClient(mockMvc, objectMapper);
    }

    /** A synthetic block in open ocean; [n] keeps the tests' places apart (~5 km). */
    private static String block(int n) {
        return GeoCell.encode(-23.0 - n * 0.05, -142.0, GeoCell.BLOCK_PRECISION);
    }

    /** Sum of one counter over every week (the virtual clock may cross a Monday). */
    private int count(Metric metric, String dimension) {
        return counters.findAll().stream()
                .filter(c -> c.getKey().getMetric() == metric && c.getKey().getDimension().equals(dimension))
                .mapToInt(EvaluationCounter::getOccurrences).sum();
    }

    private int count(Metric metric) {
        return counters.findAll().stream().filter(c -> c.getKey().getMetric() == metric)
                .mapToInt(EvaluationCounter::getOccurrences).sum();
    }

    private String idOf(String category, String cell) {
        return signals.findByCellAndCategory(cell, SignalCategory.valueOf(category)).orElseThrow().getPublicId();
    }

    private Account withRole(Account account, UserRole role) {
        User user = users.findById(account.id()).orElseThrow();
        user.setRole(role);
        users.save(user);
        return account;
    }

    /** One signal backed by two independent people (confirmed once the clean-up has seen it). */
    private String confirmedSignal(String category, String cell) throws Exception {
        api.report(api.account(), category, cell).andExpect(status().isAccepted());
        String id = idOf(category, cell);
        api.contribute(api.account(), id, "CONFIRM", cell).andExpect(status().isAccepted());
        return id;
    }

    // ---- Signal lifecycle ---------------------------------------------------------------------------------------------

    @Test
    void aSignalsLife_isCountedOnce_startedConfirmedExpired() throws Exception {
        String cell = block(1);
        Account alice = api.account();
        api.report(alice, "POOR_LIGHTING", cell).andExpect(status().isAccepted());
        api.report(alice, "POOR_LIGHTING", cell).andExpect(status().isAccepted()); // outbox re-send
        api.report(api.account(), "POOR_LIGHTING", cell).andExpect(status().isAccepted()); // merges into the same signal
        assertEquals(1, count(Metric.SIGNAL_STARTED, "CONDITION"), "one signal row, however many reports");

        community.purge();
        community.purge();
        assertEquals(1, count(Metric.SIGNAL_CONFIRMED, "CONDITION"), "first confirmation only, not every run");

        clock.advance(Duration.ofDays(31));
        community.purge();
        assertEquals(1, count(Metric.SIGNAL_ENDED, "CONDITION.EXPIRED_CONFIRMED"));
        assertEquals(0, count(Metric.SIGNAL_ENDED, "CONDITION.EXPIRED_UNCONFIRMED"));
        assertEquals(0, signals.count());
    }

    @Test
    void withdrawnAndRetiredSignals_areCountedAsSuch() throws Exception {
        String lone = block(2);
        Account alice = api.account();
        api.report(alice, "HARASSMENT", lone).andExpect(status().isAccepted());
        api.deleteAs(alice, "/api/community/mine/" + idOf("HARASSMENT", lone)).andExpect(status().isNoContent());
        assertEquals(1, count(Metric.SIGNAL_ENDED, "INCIDENT.WITHDRAWN_UNCONFIRMED"));

        String cell = block(3);
        String id = confirmedSignal("UNSAFE_CROSSING", cell);
        community.purge(); // seen confirmed
        api.contribute(api.account(), id, "NO_LONGER", cell).andExpect(status().isAccepted());
        api.contribute(api.account(), id, "NO_LONGER", cell).andExpect(status().isAccepted());
        community.purge(); // two independent people say it has changed: retired and deleted
        assertEquals(1, count(Metric.SIGNAL_ENDED, "CONDITION.RETIRED_CONFIRMED"));
        assertEquals(2, count(Metric.SIGNAL_STARTED), "one incident, one condition");
    }

    @Test
    void aNeighbourJoin_isCountedAsAJoin_notAsAnEnd() throws Exception {
        String x = block(4);
        String side = new java.util.TreeSet<>(GeoCell.neighbourhood(x)).stream()
                .filter(c -> GeoCell.adjacency(x, c) == GeoCell.Adjacency.EDGE).findFirst().orElseThrow();
        api.report(api.account(), "POOR_LIGHTING", x).andExpect(status().isAccepted());
        api.report(api.account(), "POOR_LIGHTING", side).andExpect(status().isAccepted());
        assertEquals(1, community.purge().joined());
        assertEquals(1, count(Metric.SIGNAL_JOINED_NEIGHBOUR, "CONDITION"));
        assertEquals(2, count(Metric.SIGNAL_STARTED, "CONDITION"));
        assertEquals(0, count(Metric.SIGNAL_ENDED), "the moved report goes on as support");
    }

    // ---- Cases ----------------------------------------------------------------------------------------------------------

    @Test
    void moderatorDecisions_areCountedAgainstWhatAskedForTheCase() throws Exception {
        String cell = block(5);
        String id = confirmedSignal("ISOLATED_STRETCH", cell);
        community.purge(); // seen confirmed
        api.flag(api.account(), id, "FALSE_INFORMATION", cell).andExpect(status().isAccepted());
        String other = block(6);
        api.report(api.account(), "BROKEN_PATH", other).andExpect(status().isAccepted());
        api.flag(api.account(), idOf("BROKEN_PATH", other), "FALSE_INFORMATION", other).andExpect(status().isAccepted());
        assertEquals(2, count(Metric.CASE_OPENED, "FLAGS"));

        Account moderator = withRole(api.account(), UserRole.MODERATOR);
        List<ModerationCase> open = cases.findAll();
        for (ModerationCase c : open) {
            boolean first = c.getSignalId().equals(signals.findByPublicId(id).orElseThrow().getId());
            api.postAs(moderator, "/api/moderation/cases/" + c.getPublicId() + "/actions", first
                    ? Map.of("action", "REMOVE", "reason", "FALSE_INFORMATION")
                    : Map.of("action", "DISMISS", "reason", "NO_VIOLATION")).andExpect(status().isOk());
        }
        assertEquals(1, count(Metric.CASE_CLOSED, "FLAGS.REMOVED"));
        assertEquals(1, count(Metric.CASE_CLOSED, "FLAGS.DISMISSED"));
        assertEquals(1, count(Metric.SIGNAL_REMOVED, "CONDITION.CONFIRMED_FALSE_INFORMATION"),
                "a confirmed signal removed as false: the false-confirmation numerator");
        assertEquals(0, count(Metric.CASE_UNREVIEWED));
    }

    @Test
    void aCaseNobodyDecided_isCountedAsUnreviewed_whenItsSignalEnds_orIsWithdrawn() throws Exception {
        String cell = block(7);
        api.report(api.account(), "FOLLOWING", cell).andExpect(status().isAccepted());
        api.flag(api.account(), idOf("FOLLOWING", cell), "FALSE_INFORMATION", cell).andExpect(status().isAccepted());
        clock.advance(Duration.ofDays(4)); // an incident lives 3 days
        community.purge();
        assertEquals(1, count(Metric.CASE_UNREVIEWED, "FLAGS"));
        assertEquals(1, count(Metric.SIGNAL_ENDED, "INCIDENT.EXPIRED_UNCONFIRMED"));

        // Phase 7's own coordination rule: owner + puppet behind three places get three cases, stored with their rule.
        clock.reset();
        Account owner = api.account();
        Account puppet = api.account();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String c = block(8 + i);
            api.report(owner, "POOR_LIGHTING", c).andExpect(status().isAccepted());
            ids.add(idOf("POOR_LIGHTING", c));
            api.contribute(puppet, ids.get(i), "CONFIRM", c).andExpect(status().isAccepted());
        }
        moderation.maintain();
        moderation.maintain();
        List<ModerationCase> coordination = cases.findAll().stream()
                .filter(c -> c.getOrigin() == CaseOrigin.COORDINATION).toList();
        assertEquals(3, coordination.size());
        coordination.forEach(c -> assertEquals(CoordinationRule.LINKED_GROUP, c.getCoordinationRule()));
        assertEquals(3, count(Metric.CASE_OPENED, "LINKED_GROUP"), "opened once each, not once per run");

        // No withdrawal lock for a COORDINATION case (9C): withdrawing both supporters ends it unreviewed.
        api.deleteAs(owner, "/api/community/mine/" + ids.get(0)).andExpect(status().isNoContent());
        api.deleteAs(puppet, "/api/community/mine/" + ids.get(0)).andExpect(status().isNoContent());
        assertEquals(1, count(Metric.CASE_UNREVIEWED, "LINKED_GROUP"));
        assertEquals(1, count(Metric.SIGNAL_ENDED, "CONDITION.WITHDRAWN_UNCONFIRMED"));
    }

    @Test
    void coordinationInShadow_countsEachGroupOncePerWeek_andOpensNothing() throws Exception {
        List<Account> ring = new ArrayList<>();
        for (int i = 0; i < 4; i++) ring.add(api.account());
        int p = 0;
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                String area = GeoCell.encode(-30 + (p / 4) * 0.05, -150 + (p % 4) * 0.05, 6);
                api.report(ring.get(i), "POOR_LIGHTING", area + "0").andExpect(status().isAccepted());
                api.report(ring.get(j), "ISOLATED_STRETCH", area + "1").andExpect(status().isAccepted());
                api.contribute(ring.get(j), idOf("POOR_LIGHTING", area + "0"), "CONFIRM", area + "0")
                        .andExpect(status().isAccepted());
                api.contribute(ring.get(i), idOf("ISOLATED_STRETCH", area + "1"), "CONFIRM", area + "1")
                        .andExpect(status().isAccepted());
                p++;
            }
        }
        moderation.maintain();
        moderation.maintain();
        moderation.maintain();
        assertEquals(0, cases.count(), "SHADOW opens nothing");
        assertEquals(1, count(Metric.COORDINATION_SHADOW, "SUPPORT_CORE"), "one group, not one per hourly run");
        assertTrue(counters.findAll().stream().filter(c -> c.getKey().getMetric() == Metric.COORDINATION_SHADOW)
                .allMatch(c -> c.getKey().getModelVersion().equals("coord-1")));

        clock.advance(Duration.ofDays(7)); // conditions still alive: a new week counts the group again
        moderation.maintain();
        assertEquals(2, count(Metric.COORDINATION_SHADOW, "SUPPORT_CORE"));
    }

    // ---- Transactions and failure ---------------------------------------------------------------------------------------

    @Test
    void countsAreWrittenOnlyIfTheTransactionCommits() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(s -> {
            outcomes.signalStarted(SignalCategory.HELP_POINT);
            assertEquals(0, count(Metric.SIGNAL_STARTED), "nothing is written before the commit");
            s.setRollbackOnly();
        });
        assertEquals(0, count(Metric.SIGNAL_STARTED), "a rolled-back event counts nothing");

        tx.executeWithoutResult(s -> {
            outcomes.signalStarted(SignalCategory.HELP_POINT);
            outcomes.signalStarted(SignalCategory.HELP_POINT);
        });
        assertEquals(2, count(Metric.SIGNAL_STARTED, "HAVEN"));
        outcomes.signalStarted(SignalCategory.HELP_POINT); // outside a transaction: written at once
        assertEquals(3, count(Metric.SIGNAL_STARTED, "HAVEN"));
    }

    @Test
    void aRefusedRequest_countsNothing() throws Exception {
        String cell = block(12);
        Account busy = api.account();
        String area = cell.substring(0, GeoCell.AREA_PRECISION);
        for (int i = 0; i < 3; i++) {
            api.report(busy, "POOR_LIGHTING", area + "bcd".charAt(i)).andExpect(status().isAccepted());
        }
        api.report(busy, "POOR_LIGHTING", area + "e").andExpect(status().isTooManyRequests());
        assertEquals(3, count(Metric.SIGNAL_STARTED, "CONDITION"), "the 4th report in one area was refused");
    }

    @Test
    void switchedOff_nothingIsCountedAndNothingIsTouched() {
        OutcomeRecorder off = new OutcomeRecorder(null, new EvaluationProperties(false, null), null, null);
        assertDoesNotThrow(() -> {
            off.signalStarted(SignalCategory.HARASSMENT);
            off.caseOpened(CaseTrigger.FLAGS);
            off.coordinationShadow(CaseTrigger.SUPPORT_CORE, List.of(1L));
        }, "no database touched (all dependencies are null)");
        assertTrue(new EvaluationProperties(null, null).enabled(), "the shipped default is on");
        assertEquals(104, new EvaluationProperties(null, null).retentionWeeks());
    }

    // ---- Weekly snapshot -----------------------------------------------------------------------------------------------

    @Test
    void theWeeklySnapshot_countsWhatAnyoneSees_once_andComparesRulesTwo() throws Exception {
        String area = GeoCell.encode(-33.0, -152.0, 6);
        Account a = api.account();
        Account b = api.account();
        api.report(a, "POOR_LIGHTING", area + "0").andExpect(status().isAccepted());
        api.report(b, "ISOLATED_STRETCH", area + "1").andExpect(status().isAccepted());
        api.contribute(b, idOf("POOR_LIGHTING", area + "0"), "CONFIRM", area + "0").andExpect(status().isAccepted());
        api.contribute(a, idOf("ISOLATED_STRETCH", area + "1"), "CONFIRM", area + "1").andExpect(status().isAccepted());
        String shown = null;
        for (JsonNode cell : api.area(api.account(), area).get("picture").get("areas")) {
            if (cell.get("cell").asText().equals(area)) shown = cell.get("outlook").asText();
        }
        assertEquals("TAKE_EXTRA_CARE", shown, "two people, two confirmed conditions: rules-1.1");

        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        WeeklySnapshot.Result first = snapshot.run(now);
        assertTrue(first.taken());
        assertEquals(1, first.areas());
        assertEquals(2, first.signals());
        assertEquals(1, count(Metric.SNAPSHOT_TAKEN));
        assertEquals(2, count(Metric.VISIBLE_SIGNALS, "CONDITION.CONFIRMED"));
        assertEquals(1, count(Metric.AREAS_BY_OUTLOOK, shown), "the snapshot sees exactly what viewers see");
        assertEquals(1, count(Metric.SHADOW_PICTURE, "LESS_CAUTIOUS"), "rules-2 would say TAKE_CARE (9D-2)");
        assertTrue(counters.findAll().stream().filter(c -> c.getKey().getMetric() == Metric.AREAS_BY_OUTLOOK)
                .allMatch(c -> c.getKey().getModelVersion().equals("rules-1.1")));
        assertTrue(counters.findAll().stream().filter(c -> c.getKey().getMetric() == Metric.SHADOW_PICTURE)
                .allMatch(c -> c.getKey().getModelVersion().equals("rules-2")));

        assertFalse(snapshot.run(now).taken(), "once per week");
        assertEquals(1, count(Metric.SNAPSHOT_TAKEN));
        assertEquals(2, count(Metric.VISIBLE_SIGNALS, "CONDITION.CONFIRMED"));

        clock.advance(Duration.ofDays(7));
        assertTrue(snapshot.run(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).taken(), "a new week");
        assertEquals(2, count(Metric.SNAPSHOT_TAKEN));
        assertEquals(2, counters.findAll().stream().filter(c -> c.getKey().getMetric() == Metric.SNAPSHOT_TAKEN)
                .map(c -> c.getKey().getWeekStart()).distinct().count());
    }

    @Test
    void rowsOlderThanTheRetention_areDeleted_andEveryStoredRowIsVocabularyOnly() throws Exception {
        LocalDate week = OutcomeRecorder.weekOf(clock);
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            counters.insert(week.minusWeeks(104), "SIGNAL_STARTED", "INCIDENT", "-", 5);
            counters.insert(week.minusWeeks(103), "SIGNAL_STARTED", "INCIDENT", "-", 7);
        });
        assertEquals(1, snapshot.run(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).deleted(),
                "104 weeks are kept, counting the current one");
        assertEquals(7, count(Metric.SIGNAL_STARTED, "INCIDENT"));

        confirmedSignal("HARASSMENT", block(13));
        community.purge();
        moderation.maintain();
        for (EvaluationCounter c : counters.findAll()) {
            assertTrue(OutcomeRecorder.storable(c.getKey().getDimension(), c.getKey().getModelVersion()),
                    "only vocabulary: " + c.getKey().getDimension());
            assertEquals(java.time.DayOfWeek.MONDAY, c.getKey().getWeekStart().getDayOfWeek(), "weeks only");
        }
    }

    // ---- The ADMIN report ------------------------------------------------------------------------------------------------

    @Test
    void onlyAnAdministrator_canReadTheCounters() throws Exception {
        mockMvc.perform(get("/api/admin/evaluation")).andExpect(status().isUnauthorized());
        api.getAs(api.account(), "/api/admin/evaluation").andExpect(status().isForbidden());
        api.getAs(withRole(api.account(), UserRole.MODERATOR), "/api/admin/evaluation").andExpect(status().isForbidden());
        Account admin = withRole(api.account(), UserRole.ADMIN);
        api.getAs(admin, "/api/admin/evaluation?weeks=0").andExpect(status().isBadRequest());
        api.getAs(admin, "/api/admin/evaluation?weeks=105").andExpect(status().isBadRequest());
        api.getAs(admin, "/api/admin/evaluation?weeks=104").andExpect(status().isOk());
    }

    @Test
    void theReport_holdsCountsRatesAndTheGate_andNothingAboutPlacesOrPeople() throws Exception {
        String cell = block(14);
        String id = confirmedSignal("THEFT_OR_SNATCHING", cell);
        api.flag(api.account(), id, "TARGETS_SOMEONE", cell).andExpect(status().isAccepted());
        Account moderator = withRole(api.account(), UserRole.MODERATOR);
        api.postAs(moderator, "/api/moderation/cases/" + cases.findAll().get(0).getPublicId() + "/actions",
                Map.of("action", "DISMISS", "reason", "NO_VIOLATION")).andExpect(status().isOk());

        Account admin = withRole(api.account(), UserRole.ADMIN);
        JsonNode report = api.json(api.getAs(admin, "/api/admin/evaluation").andExpect(status().isOk()));
        assertOnly(report, "notice", "fromWeek", "toWeek", "currentWeekIncomplete", "countersEnabled", "counters",
                "rates", "g1");
        assertEquals(OutcomeRecorder.weekOf(clock).toString(), report.get("toWeek").asText());
        assertEquals(OutcomeRecorder.weekOf(clock).minusWeeks(11).toString(), report.get("fromWeek").asText());
        assertTrue(report.get("notice").asText().contains("fewer than 10"), "the publication rule travels with it");
        for (JsonNode row : report.get("counters")) {
            assertOnly(row, "weekStart", "metric", "dimension", "modelVersion", "value");
        }
        for (JsonNode rate : report.get("rates")) {
            assertOnly(rate, "name", "dimension", "numerator", "denominator", "minimum", "status", "value", "low", "high",
                    "lowerBound");
        }
        JsonNode precision = null;
        for (JsonNode rate : report.get("rates")) {
            if (rate.get("name").asText().equals("TRIGGER_PRECISION") && rate.get("dimension").asText().equals("FLAGS")) {
                precision = rate;
            }
        }
        assertEquals("NOT_ENOUGH_DATA", precision.get("status").asText(), "1 decided case: no percentage");
        assertTrue(precision.get("value").isNull());
        assertEquals(1, precision.get("denominator").asInt());
        assertEquals("NOT_MET", report.get("g1").get("status").asText());
        assertEquals(1, report.get("g1").get("activeModerators").asInt(), "one moderator decided in the last 30 days");

        String body = report.toString();
        assertFalse(body.contains(cell) || body.contains(cell.substring(0, 6)), "no cell");
        assertFalse(body.contains("@example.com"), "no e-mail");
        assertFalse(body.contains(id), "no signal id");
    }

    private static void assertOnly(JsonNode node, String... allowed) {
        Set<String> names = Set.of(allowed);
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            assertTrue(names.contains(field), "unexpected field: " + field);
        }
    }
}
