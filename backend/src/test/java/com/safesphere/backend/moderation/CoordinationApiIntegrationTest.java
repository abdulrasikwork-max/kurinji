package com.safesphere.backend.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.safesphere.backend.moderation.service.ModerationService;
import com.safesphere.backend.support.CommunityClient;
import com.safesphere.backend.support.CommunityClient.Account;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.entity.UserRole;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 9C coord-1 in ACTIVE mode through the real API (the shipped default is SHADOW). A ring of accounts that never
 * repeats a pair gets ONE review case for a human, and nothing else changes for anyone: no hiding, no confidence or
 * weight change, identical answers to viewers, withdrawal still possible. The moderator sees the pattern in bands only,
 * the rest of the group as case pseudonyms, and can pause the whole group in one decision. Legitimate groups:
 * CoordinationFalsePositiveApiIntegrationTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
@TestPropertySource(properties = {"app.moderation.coordination.mode=ACTIVE",
        "app.moderation.coordination.max-cases-per-day=1"})
class CoordinationApiIntegrationTest {

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

    /** A ring: every pair of members backs two conditions in an area of its own; areas are ~5.5 km apart. */
    private record Ring(List<Account> members, List<String> areas) {
    }

    private Ring ring(int size, int row) throws Exception {
        List<Account> members = new ArrayList<>();
        for (int i = 0; i < size; i++) members.add(api.account());
        List<String> areas = new ArrayList<>();
        int p = 0;
        for (int i = 0; i < size; i++) {
            for (int j = i + 1; j < size; j++) {
                String area = GeoCell.encode(-20 + row * 0.5 + (p / 4) * 0.05, -140 + (p % 4) * 0.05, 6);
                areas.add(area);
                api.report(members.get(i), "POOR_LIGHTING", area + "0").andExpect(status().isAccepted());
                api.report(members.get(j), "ISOLATED_STRETCH", area + "1").andExpect(status().isAccepted());
                confirm(members.get(j), "POOR_LIGHTING", area + "0");
                confirm(members.get(i), "ISOLATED_STRETCH", area + "1");
                p++;
            }
        }
        return new Ring(members, areas);
    }

    private void confirm(Account who, String category, String cell) throws Exception {
        api.contribute(who, publicId(category, cell), "CONFIRM", cell).andExpect(status().isAccepted());
    }

    private String publicId(String category, String cell) {
        return signals.findByCellAndCategory(cell, SignalCategory.valueOf(category)).orElseThrow().getPublicId();
    }

    private List<ModerationCase> coordinationCases(CaseStatus status) {
        return cases.findAll().stream()
                .filter(c -> c.getOrigin() == CaseOrigin.COORDINATION && c.getStatus() == status).toList();
    }

    private Account moderator() throws Exception {
        Account account = api.account();
        User user = users.findById(account.id()).orElseThrow();
        user.setRole(UserRole.MODERATOR);
        users.save(user);
        return account;
    }

    private Map<String, String> outlooks(Account viewer, List<String> areas) throws Exception {
        Map<String, String> result = new HashMap<>();
        for (String area : areas) {
            for (JsonNode cell : api.area(viewer, area).get("picture").get("areas")) {
                if (cell.get("cell").asText().equals(area)) result.put(area, cell.get("outlook").asText());
            }
        }
        return result;
    }

    @Test
    void aRing_getsOneCaseForAHuman_andNothingElseChangesForAnyone() throws Exception {
        Ring ring = ring(4, 0);
        Account viewer = api.account();
        Map<String, String> before = new HashMap<>();
        for (String area : ring.areas()) before.put(area, api.area(viewer, area).get("signals").toString());

        moderation.maintain();
        moderation.maintain();

        assertEquals(1, coordinationCases(CaseStatus.OPEN).size(), "one case per group, not one per run or signal");
        for (PlaceSignal s : signals.findAll()) assertEquals(ModerationState.NONE, s.getModeration(), "nothing hidden");
        for (String area : ring.areas()) {
            assertEquals(before.get(area), api.area(viewer, area).get("signals").toString(),
                    "viewers get exactly the same answer: no hint, no change");
        }
        PlaceSignal target = signals.findById(coordinationCases(CaseStatus.OPEN).get(0).getSignalId()).orElseThrow();
        assertEquals(ring.areas().get(0) + "0", target.getCell(), "the case is on the signal doing the most harm");
        api.deleteAs(ring.members().get(0), "/api/community/mine/" + target.getPublicId())
                .andExpect(status().isNoContent()); // no withdrawal lock: a 409 would tell the ring it was detected
    }

    @Test
    void theModeratorSeesThePatternInBands_andNoOtherPlace() throws Exception {
        Ring ring = ring(4, 0);
        moderation.maintain();
        Account moderator = moderator();
        String caseId = coordinationCases(CaseStatus.OPEN).get(0).getPublicId();
        String raw = api.getAs(moderator, "/api/moderation/cases/" + caseId).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode coordination = objectMapper.readTree(raw).get("coordination");
        assertEquals("[\"SUPPORT_CORE\"]", coordination.get("reasons").toString());
        assertEquals("THREE_TO_FIVE", coordination.get("groupSize").asText());
        assertEquals("TEN_OR_MORE", coordination.get("groupPlaces").asText(), "12 signals only this group backs");
        for (String area : ring.areas().subList(1, ring.areas().size())) {
            assertFalse(raw.contains(area), "no other place of the group reaches the moderator");
        }
    }

    @Test
    void aGroupIsAskedAboutOnce_andNotAgainAfterAModeratorFoundItFine() throws Exception {
        ring(3, 0);
        moderation.maintain();
        String caseId = coordinationCases(CaseStatus.OPEN).get(0).getPublicId();
        api.postAs(moderator(), "/api/moderation/cases/" + caseId + "/actions",
                Map.of("action", "DISMISS", "reason", "NO_VIOLATION")).andExpect(status().isOk());
        clock.advance(Duration.ofDays(2));
        moderation.maintain();
        assertEquals(0, coordinationCases(CaseStatus.OPEN).size(), "no other signal of the same group is picked instead");
        assertEquals(1, cases.count());
    }

    @Test
    void theDailyBudgetHoldsFurtherGroupsBack_untilTheNextDay() throws Exception {
        ring(3, 0);
        ring(3, 1);
        moderation.maintain();
        assertEquals(1, coordinationCases(CaseStatus.OPEN).size(), "budget: 1 per day here (5 by default)");
        clock.advance(Duration.ofHours(25));
        moderation.maintain();
        assertEquals(2, coordinationCases(CaseStatus.OPEN).size());
    }

    @Test
    void flagsOnASignalUnderACoordinationCase_lockWithdrawalAgain() throws Exception {
        Ring ring = ring(3, 0);
        moderation.maintain();
        PlaceSignal target = signals.findById(coordinationCases(CaseStatus.OPEN).get(0).getSignalId()).orElseThrow();
        api.flag(api.account(), target.getPublicId(), "FALSE_INFORMATION", target.getCell())
                .andExpect(status().isAccepted());
        api.deleteAs(ring.members().get(0), "/api/community/mine/" + target.getPublicId())
                .andExpect(status().isConflict()); // a person's complaint is under review now
    }

    // ---- Group members as case participants (Phase 9C, PHASE9.md §10) -------------------------------------------------

    private String caseUrl() {
        return "/api/moderation/cases/" + coordinationCases(CaseStatus.OPEN).get(0).getPublicId();
    }

    private long marked(Map<String, String> outlooks) {
        return outlooks.values().stream().filter(o -> o.startsWith("TAKE_")).count();
    }

    private Map<String, Object> pauseEveryone() {
        return Map.of("action", "RESTRICT", "reason", "SPAM_OR_COORDINATED", "wholeGroup", true, "days", 7);
    }

    @Test
    void pausingOnlyTheCasesTwoParticipants_leavesPartOfARingOfFour_andTheRestDropsOutOfTheCase() throws Exception {
        Ring ring = ring(4, 0);
        Account viewer = api.account();
        assertEquals(6, outlooks(viewer, ring.areas()).values().stream().filter("TAKE_EXTRA_CARE"::equals).count());
        moderation.maintain();
        Account moderator = moderator();
        JsonNode participants = api.json(api.getAs(moderator, caseUrl())).get("participants");
        assertEquals(2, participants.size(), "participants are the people behind ITS signal");
        JsonNode last = null;
        for (JsonNode p : participants) {
            last = api.json(api.postAs(moderator, caseUrl() + "/actions", Map.of("action", "RESTRICT",
                    "reason", "SPAM_OR_COORDINATED", "contributorRef", p.get("ref").asText(), "days", 7))
                    .andExpect(status().isOk()));
        }
        Map<String, String> after = outlooks(viewer, ring.areas());
        assertEquals(1, after.values().stream().filter("TAKE_EXTRA_CARE"::equals).count(),
                "MEASURED: the pair not behind the case's signal still marks its area");
        assertEquals(0, last.get("groupMembers").size(), "once their support stopped counting, the pattern is gone "
                + "from the case: one by one is fragile, which is why one decision can pause the whole group");
    }

    @Test
    void theCaseListsTheRestOfTheGroup_andOneDecisionPausesThemAll_quietingEveryArea() throws Exception {
        Ring ring = ring(4, 0);
        Account viewer = api.account();
        moderation.maintain();
        Account moderator = moderator();
        JsonNode detail = api.json(api.getAs(moderator, caseUrl()).andExpect(status().isOk()));
        assertEquals(2, detail.get("participants").size());
        JsonNode members = detail.get("groupMembers");
        assertEquals(2, members.size(), "the two ring members not behind this signal are listed too");
        for (JsonNode m : members) {
            assertEquals(Set.of("ref", "role", "flag", "tier", "accountAge", "counting", "linkGroup"), fields(m),
                    "a case pseudonym and bands, nothing else");
            assertTrue(m.get("ref").asText().matches("[0-9a-f]{12}"));
            assertTrue(m.get("role").isNull() && m.get("flag").isNull());
            assertTrue(m.get("counting").asBoolean());
        }

        JsonNode after = api.json(api.postAs(moderator, caseUrl() + "/actions", pauseEveryone())
                .andExpect(status().isOk()));
        for (Account member : ring.members()) {
            assertTrue(standings.findById(member.id()).orElseThrow().isRestricted(
                    java.time.LocalDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC)));
            assertEquals(1, standings.findById(member.id()).orElseThrow().getStrikes());
        }
        assertEquals(4, actions.findAll().stream().filter(a -> a.getAction().name().equals("RESTRICT")).count(),
                "one audit entry per person, so each can appeal on their own");
        assertEquals(0, marked(outlooks(viewer, ring.areas())), "every area of the ring is quiet");
        assertTrue(after.get("coordination").isNull(), "the case now says the pattern is gone");
        assertEquals("OPEN", after.get("summary").get("status").asText(), "the case itself still needs a decision");
    }

    @Test
    void aRingOfSix_getsOneCase_andOneDecisionQuietsAllFifteenAreas() throws Exception {
        Ring ring = ring(6, 0);
        Account viewer = api.account();
        assertEquals(15, marked(outlooks(viewer, ring.areas())));
        moderation.maintain();
        moderation.maintain();
        assertEquals(1, coordinationCases(CaseStatus.OPEN).size());
        Account moderator = moderator();
        JsonNode detail = api.json(api.getAs(moderator, caseUrl()));
        assertEquals(6, detail.get("participants").size() + detail.get("groupMembers").size());
        assertEquals("SIX_OR_MORE", detail.get("coordination").get("groupSize").asText());
        api.postAs(moderator, caseUrl() + "/actions", pauseEveryone()).andExpect(status().isOk());
        assertEquals(0, marked(outlooks(viewer, ring.areas())));
    }

    @Test
    void aGroupMemberNotBehindTheSignal_canBePausedByTheirCasePseudonym_andNobodyElse() throws Exception {
        Ring ring = ring(4, 0);
        moderation.maintain();
        Account moderator = moderator();
        String ref = api.json(api.getAs(moderator, caseUrl())).get("groupMembers").get(0).get("ref").asText();
        api.postAs(moderator, caseUrl() + "/actions", Map.of("action", "RESTRICT", "reason", "SPAM_OR_COORDINATED",
                "contributorRef", ref, "days", 7)).andExpect(status().isOk());
        assertEquals(1, ring.members().stream()
                .filter(m -> standings.findById(m.id()).map(s -> s.getStrikes() == 1).orElse(false)).count());
        api.postAs(moderator, caseUrl() + "/actions", Map.of("action", "RESTRICT", "reason", "SPAM_OR_COORDINATED",
                "contributorRef", "0123456789ab", "days", 7)).andExpect(status().isNotFound());
    }

    @Test
    void aModeratorInTheGroup_butNotBehindTheCasesSignal_cannotSeeOrDecideIt() throws Exception {
        Ring ring = ring(4, 0);
        moderation.maintain();
        PlaceSignal target = signals.findById(coordinationCases(CaseStatus.OPEN).get(0).getSignalId()).orElseThrow();
        Account insider = ring.members().stream().filter(m -> contributions.findBySignalId(target.getId()).stream()
                .noneMatch(c -> c.getUserId() == m.id())).findFirst().orElseThrow();
        User user = users.findById(insider.id()).orElseThrow();
        user.setRole(UserRole.MODERATOR);
        users.save(user);
        JsonNode queue = api.json(api.getAs(insider, "/api/moderation/cases").andExpect(status().isOk()));
        assertTrue(queue.get(0).get("youTookPart").asBoolean(), "the queue labels it, so the app does not offer it");
        api.getAs(insider, caseUrl()).andExpect(status().isForbidden());
        api.postAs(insider, caseUrl() + "/actions", Map.of("action", "DISMISS", "reason", "NO_VIOLATION"))
                .andExpect(status().isForbidden());
    }

    @Test
    void wholeGroupIsOnlyForAPause_andOnlyWhileAGroupStandsBehindTheSignal() throws Exception {
        ring(3, 0);
        moderation.maintain();
        Account moderator = moderator();
        api.postAs(moderator, caseUrl() + "/actions", Map.of("action", "DISMISS", "reason", "NO_VIOLATION",
                "wholeGroup", true)).andExpect(status().isBadRequest());
        String ref = api.json(api.getAs(moderator, caseUrl())).get("participants").get(0).get("ref").asText();
        api.postAs(moderator, caseUrl() + "/actions", Map.of("action", "RESTRICT", "reason", "SPAM_OR_COORDINATED",
                "wholeGroup", true, "contributorRef", ref)).andExpect(status().isBadRequest());

        // A Phase 7 case (owner + puppet, linked, no coord-1 group): there is no group to pause.
        cases.deleteAll();
        Account owner = api.account();
        Account puppet = api.account();
        String home = GeoCell.encode(-30, -150, 6);
        for (int k = 0; k < 3; k++) api.report(owner, "POOR_LIGHTING", home + k).andExpect(status().isAccepted());
        for (int k = 0; k < 3; k++) confirm(puppet, "POOR_LIGHTING", home + k);
        moderation.maintain();
        String linked = cases.findAll().stream().filter(c -> c.getStatus() == CaseStatus.OPEN
                && signals.findById(c.getSignalId()).orElseThrow().getAreaCell().equals(home))
                .findFirst().orElseThrow().getPublicId();
        api.postAs(moderator, "/api/moderation/cases/" + linked + "/actions", pauseEveryone())
                .andExpect(status().isConflict());
    }

    // ---- Attacks ------------------------------------------------------------------------------------------------------

    @Test
    void aPaddedPair_isFoundAsAnExclusivePair_andPausingThePairQuietsItsAreas_butNotHonestPlaces() throws Exception {
        Account a = api.account();
        Account b = api.account();
        String east = GeoCell.encode(-20, -140, 6);
        String west = GeoCell.encode(-20, -139.8, 6);
        for (String area : List.of(east, west)) { // two fake conditions per area, backed only by the pair
            api.report(a, "POOR_LIGHTING", area + "0").andExpect(status().isAccepted());
            api.report(b, "ISOLATED_STRETCH", area + "1").andExpect(status().isAccepted());
            confirm(b, "POOR_LIGHTING", area + "0");
            confirm(a, "ISOLATED_STRETCH", area + "1");
        }
        // Padding: each confirms five DIFFERENT popular honest places, so 4 shared < half of 9 each (Phase 7: not linked).
        List<String> honestAreas = new ArrayList<>();
        for (int k = 0; k < 10; k++) {
            String area = GeoCell.encode(-21 - (k / 5) * 0.05, -140 + (k % 5) * 0.05, 6);
            honestAreas.add(area);
            Account local = api.account();
            api.report(local, "BROKEN_PATH", area + "2").andExpect(status().isAccepted());
            for (int i = 0; i < 2; i++) confirm(api.account(), "BROKEN_PATH", area + "2");
            confirm(k < 5 ? a : b, "BROKEN_PATH", area + "2");
        }
        Account viewer = api.account();
        assertEquals("TAKE_EXTRA_CARE", outlooks(viewer, List.of(east)).get(east));
        moderation.maintain();
        assertEquals(1, coordinationCases(CaseStatus.OPEN).size());
        Account moderator = moderator();
        JsonNode detail = api.json(api.getAs(moderator, caseUrl()));
        assertEquals("[\"EXCLUSIVE_PAIR\"]", detail.get("coordination").get("reasons").toString());
        assertEquals(0, detail.get("groupMembers").size(), "both of the pair are behind the signal itself");

        api.postAs(moderator, caseUrl() + "/actions", pauseEveryone()).andExpect(status().isOk());
        Map<String, String> after = outlooks(viewer, List.of(east, west));
        assertEquals(0, marked(after), "the pair's areas are quiet");
        Account second = api.account();
        for (String area : honestAreas.subList(0, 5)) {
            assertEquals("TAKE_CARE", outlooks(second, List.of(area)).get(area),
                    "honest places the pair padded with still stand on their own people");
        }
    }

    @Test
    void aRingMemberWhoConfirmsAnHonestPairsPlace_pullsThemIntoTheCore_butTheyAreNeitherListedNorPaused()
            throws Exception {
        Ring ring = ring(4, 0);
        Account a = api.account();
        Account b = api.account();
        String far = GeoCell.encode(-24, -144, 6);
        api.report(a, "BROKEN_PATH", far + "5").andExpect(status().isAccepted());
        confirm(b, "BROKEN_PATH", far + "5");
        confirm(ring.members().get(0), "BROKEN_PATH", far + "5"); // the ring member backs a real place too
        moderation.maintain();
        Account moderator = moderator();
        JsonNode detail = api.json(api.getAs(moderator, caseUrl()));
        assertEquals(2, detail.get("groupMembers").size(), "only the ring's own members are listed");
        assertEquals("THREE_TO_FIVE", detail.get("coordination").get("groupSize").asText());
        api.postAs(moderator, caseUrl() + "/actions", pauseEveryone()).andExpect(status().isOk());
        java.time.LocalDateTime now = java.time.LocalDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC);
        for (Account honest : List.of(a, b)) {
            assertFalse(standings.findById(honest.id()).map(s -> s.isRestricted(now)).orElse(false),
                    "an honest pair pulled into the core through one place is not paused with the ring");
        }
        for (Account member : ring.members()) {
            assertTrue(standings.findById(member.id()).orElseThrow().isRestricted(now));
        }
    }

    @Test
    void aRingCannotShelterBehindAnHonestPlaceAModeratorFoundFine() throws Exception {
        // An honest couple backs the same three places at home: Phase 7 links them and a moderator finds one fine.
        Account a = api.account();
        Account b = api.account();
        String home = GeoCell.encode(-24, -144, 6);
        String[] categories = {"POOR_LIGHTING", "ISOLATED_STRETCH", "BROKEN_PATH"};
        for (int k = 0; k < 3; k++) {
            api.report(a, categories[k], home + k).andExpect(status().isAccepted());
            confirm(b, categories[k], home + k);
        }
        moderation.maintain();
        long sheltering = signals.findByCellAndCategory(home + "0", SignalCategory.POOR_LIGHTING).orElseThrow().getId();
        String dismissed = cases.findAll().stream().filter(c -> c.getSignalId() == sheltering).findFirst().orElseThrow()
                .getPublicId();
        api.postAs(moderator(), "/api/moderation/cases/" + dismissed + "/actions",
                Map.of("action", "DISMISS", "reason", "NO_VIOLATION")).andExpect(status().isOk());

        clock.advance(Duration.ofHours(25)); // a new day for the case budget
        Ring ring = ring(3, 0);
        confirm(ring.members().get(0), "POOR_LIGHTING", home + "0"); // the ring backs the place found fine
        moderation.maintain();
        assertTrue(coordinationCases(CaseStatus.OPEN).stream().anyMatch(c -> ring.areas().contains(
                signals.findById(c.getSignalId()).orElseThrow().getAreaCell())),
                "the couple's dismissed place is not the ring's: the ring still goes to a human");
    }

    @Test
    void aRingThatStaysInsideOneNeighbourhood_isNotFound_documentedEvasion() throws Exception {
        List<Account> ring = new ArrayList<>();
        for (int i = 0; i < 4; i++) ring.add(api.account());
        String centre = GeoCell.encode(-22, -142, 6);
        List<String> areas = List.copyOf(new java.util.TreeSet<>(GeoCell.neighbourhood(centre)));
        int p = 0;
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                String area = areas.get(p++);
                api.report(ring.get(i), "POOR_LIGHTING", area + "0").andExpect(status().isAccepted());
                api.report(ring.get(j), "ISOLATED_STRETCH", area + "1").andExpect(status().isAccepted());
                confirm(ring.get(j), "POOR_LIGHTING", area + "0");
                confirm(ring.get(i), "ISOLATED_STRETCH", area + "1");
            }
        }
        moderation.maintain();
        assertEquals(0, coordinationCases(CaseStatus.OPEN).size(),
                "MEASURED EVASION: inside one ~3.6 x 1.8 km neighbourhood coord-1 stays quiet, by design (a hostel "
                        + "or a street looks the same); the damage stays in that neighbourhood");
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> names = new java.util.TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
