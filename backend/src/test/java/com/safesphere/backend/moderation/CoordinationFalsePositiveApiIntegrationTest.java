package com.safesphere.backend.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.community.entity.ModerationState;
import com.safesphere.backend.community.entity.PlaceSignal;
import com.safesphere.backend.community.entity.SignalCategory;
import com.safesphere.backend.community.repository.ContributorStandingRepository;
import com.safesphere.backend.community.repository.PlaceSignalRepository;
import com.safesphere.backend.community.repository.SignalContributionRepository;
import com.safesphere.backend.community.repository.SignalFlagRepository;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.moderation.entity.ModerationCase;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseOrigin;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseStatus;
import com.safesphere.backend.moderation.repository.ModerationActionRepository;
import com.safesphere.backend.moderation.repository.ModerationAppealRepository;
import com.safesphere.backend.moderation.repository.ModerationCaseRepository;
import com.safesphere.backend.moderation.service.CoordinationCheck;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 9C false-positive tests: LEGITIMATE groups through the real API with coord-1 in ACTIVE mode and room for many
 * cases, so any finding would become a case. A women's safety app is used by people who live, study and walk together;
 * flagging them by mistake would cost their voice, so each scenario states what coord-1 does with them and what it
 * costs. The one shape coord-1 is built to flag (a group backing each other across town, and nobody else backing it)
 * includes friends who really do that: that documented false positive gets a case for a human and nothing else.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
@TestPropertySource(properties = {"app.moderation.coordination.mode=ACTIVE",
        "app.moderation.coordination.max-cases-per-day=50"})
class CoordinationFalsePositiveApiIntegrationTest {

    private static final String[] CONCERNS = {"POOR_LIGHTING", "ISOLATED_STRETCH", "UNSAFE_CROSSING", "BROKEN_PATH",
            "HARASSMENT"};
    private static final String BLOCKS = "0123456789bcdefghjkmnpqrstuvwxyz";

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
    @Autowired private ModerationService moderation;
    @Autowired private CoordinationCheck coordination;

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

    /** Area cells ~5.5 km apart in open ocean: two of them are never in one neighbourhood. */
    private static String area(int row, int column) {
        return GeoCell.encode(-35 + row * 0.05, -155 + column * 0.05, 6);
    }

    private static String block(String area, int k) {
        return area + BLOCKS.charAt(k);
    }

    private List<Account> people(int n) throws Exception {
        List<Account> list = new ArrayList<>();
        for (int i = 0; i < n; i++) list.add(api.account());
        return list;
    }

    private void report(Account who, String category, String cell) throws Exception {
        api.report(who, category, cell).andExpect(status().isAccepted());
    }

    private void confirm(Account who, String category, String cell) throws Exception {
        String id = signals.findByCellAndCategory(cell, SignalCategory.valueOf(category)).orElseThrow().getPublicId();
        api.contribute(who, id, "CONFIRM", cell).andExpect(status().isAccepted());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private List<ModerationCase> coordinationCases() {
        return cases.findAll().stream().filter(c -> c.getOrigin() == CaseOrigin.COORDINATION).toList();
    }

    private String outlook(String area) throws Exception {
        for (JsonNode cell : api.area(api.account(), area).get("picture").get("areas")) {
            if (cell.get("cell").asText().equals(area)) return cell.get("outlook").asText();
        }
        throw new AssertionError("no picture");
    }

    private void nothingHidden() {
        for (PlaceSignal s : signals.findAll()) assertEquals(ModerationState.NONE, s.getModeration());
    }

    @Test
    void aHostelWhoseResidentsBackEverythingTogether_isNeverACoord1Finding() throws Exception {
        String hostel = area(0, 0);
        List<Account> residents = people(6);
        for (int k = 0; k < 5; k++) report(residents.get(k), CONCERNS[k], block(hostel, k));
        for (int k = 0; k < 5; k++) {
            for (int r = 0; r < 6; r++) if (r != k) confirm(residents.get(r), CONCERNS[k], block(hostel, k));
        }
        moderation.maintain();
        assertTrue(coordination.current(now()).isEmpty(), "one neighbourhood: coord-1 never flags a hostel");
        assertEquals(5, coordinationCases().size(), "Phase 7 (R0) still links them: one case per place, for a human. "
                + "A moderator's DISMISS makes each place count again (DismissedCoordinationApiIntegrationTest)");
        nothingHidden();
    }

    @Test
    void friendsWhoLiveAndStudyTogetherAcrossTown_documentedFalsePositive_getOneCaseAndNothingElse() throws Exception {
        String campus = area(1, 0);
        String home = area(1, 3);
        List<Account> friends = people(4);
        int p = 0;
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) { // they walk in twos: every pair backs two places, at campus or at home
                String at = p < 3 ? campus : home;
                report(friends.get(i), "POOR_LIGHTING", block(at, 2 * (p % 3)));
                report(friends.get(j), "ISOLATED_STRETCH", block(at, 2 * (p % 3) + 1));
                confirm(friends.get(j), "POOR_LIGHTING", block(at, 2 * (p % 3)));
                confirm(friends.get(i), "ISOLATED_STRETCH", block(at, 2 * (p % 3) + 1));
                p++;
            }
        }
        Map<String, String> before = new HashMap<>();
        Account viewer = api.account();
        for (String a : List.of(campus, home)) before.put(a, api.area(viewer, a).get("signals").toString());

        moderation.maintain();
        moderation.maintain();
        assertEquals(1, coordinationCases().size(), "DOCUMENTED FALSE POSITIVE: one case for a human, not one per place");
        nothingHidden();
        for (String a : List.of(campus, home)) {
            assertEquals(before.get(a), api.area(viewer, a).get("signals").toString(), "their warnings stand as they were");
        }
        assertEquals("TAKE_EXTRA_CARE", outlook(campus));
        PlaceSignal target = signals.findById(coordinationCases().get(0).getSignalId()).orElseThrow();
        Account reporter = friends.stream().filter(f -> contributions.findBySignalIdAndUserId(target.getId(), f.id())
                .map(c -> c.getKind().name().equals("REPORT")).orElse(false)).findFirst().orElseThrow();
        // No withdrawal lock (it would tell them they were looked at), and nobody is told anything.
        Account moderator = api.account();
        User user = users.findById(moderator.id()).orElseThrow();
        user.setRole(UserRole.MODERATOR);
        users.save(user);
        JsonNode detail = api.json(api.getAs(moderator, "/api/moderation/cases/" + coordinationCases().get(0).getPublicId()));
        assertEquals("[\"SUPPORT_CORE\"]", detail.get("coordination").get("reasons").toString());
        api.postAs(moderator, "/api/moderation/cases/" + coordinationCases().get(0).getPublicId() + "/actions",
                Map.of("action", "DISMISS", "reason", "NO_VIOLATION")).andExpect(status().isOk());
        // The reporter takes their report back; the place stays (the friend who confirmed it still backs it), so it
        // drops out of the group's exclusive two-backer places. The group must still count as "found fine".
        api.deleteAs(reporter, "/api/community/mine/" + target.getPublicId()).andExpect(status().isNoContent());
        clock.advance(Duration.ofDays(2));
        moderation.maintain();
        assertEquals(0, coordinationCases().stream().filter(c -> c.getStatus() == CaseStatus.OPEN).count(),
                "found fine once: not asked about again (no other place of theirs is picked instead)");
    }

    @Test
    void manyPeopleGenuinelyHittingTheSameProblemAcrossTown_areLeftAlone() throws Exception {
        // A city-wide street-light failure: two places in each of three areas, each backed by four locals, plus two
        // commuters who pass all three areas every day and back one place in each.
        List<Account> commuters = people(2);
        List<String> areas = List.of(area(2, 0), area(2, 2), area(2, 4));
        for (String a : areas) {
            for (int k = 0; k < 2; k++) {
                List<Account> locals = people(4);
                report(locals.get(0), CONCERNS[k], block(a, k));
                for (int i = 1; i < 4; i++) confirm(locals.get(i), CONCERNS[k], block(a, k));
            }
            for (Account c : commuters) confirm(c, CONCERNS[0], block(a, 0));
        }
        moderation.maintain();
        assertEquals(0, coordinationCases().size(), "popular places (4+ backers) are not what coord-1 looks at");
        for (String a : areas) assertEquals("TAKE_EXTRA_CARE", outlook(a), "their warnings count in full");
    }

    @Test
    void onePowerUserWhoseReportsStrangersConfirm_isNotAGroup() throws Exception {
        Account local = api.account();
        for (int c = 0; c < 3; c++) {
            String a = area(3, 2 * c);
            for (int k = 0; k < 2; k++) {
                report(local, CONCERNS[k], block(a, k));
                confirm(api.account(), CONCERNS[k], block(a, k));
            }
        }
        moderation.maintain();
        assertEquals(0, coordinationCases().size(), "a star (one person, different strangers) has no 2-core");
    }

    @Test
    void aCoupleBackingTwoPlacesAtHome_andEachTheirOwnAtWork_isNotAFinding() throws Exception {
        List<Account> couple = people(2);
        String home = area(4, 0);
        for (int k = 0; k < 2; k++) {
            report(couple.get(k), CONCERNS[k], block(home, k));
            confirm(couple.get(1 - k), CONCERNS[k], block(home, k));
        }
        report(couple.get(0), "POOR_LIGHTING", block(area(4, 3), 0));
        report(couple.get(1), "POOR_LIGHTING", block(area(4, 6), 0));
        moderation.maintain();
        assertEquals(0, coordinationCases().size(), "two shared places: below Phase 7 and below R2 (3)");
        assertEquals("TAKE_EXTRA_CARE", outlook(home), "both of them count");
    }

    @Test
    void colleaguesWhoAllBackTheCampusRoute_andTheirOwnStreets_areLeftAlone() throws Exception {
        List<Account> colleagues = people(5);
        String campus = area(5, 0);
        for (int k = 0; k < 2; k++) {
            report(colleagues.get(k), CONCERNS[k], block(campus, k));
            for (int i = 0; i < 5; i++) if (i != k) confirm(colleagues.get(i), CONCERNS[k], block(campus, k));
        }
        for (int i = 0; i < 5; i++) report(colleagues.get(i), "BROKEN_PATH", block(area(5, 2 + i), 3));
        moderation.maintain();
        assertEquals(0, coordinationCases().size());
        assertEquals("TAKE_EXTRA_CARE", outlook(campus));
    }
}
