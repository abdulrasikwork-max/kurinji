package com.safesphere.backend.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.community.entity.ContributorStanding;
import com.safesphere.backend.community.repository.ContributorStandingRepository;
import com.safesphere.backend.community.repository.PlaceSignalRepository;
import com.safesphere.backend.community.repository.SignalContributionRepository;
import com.safesphere.backend.community.repository.SignalFlagRepository;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.moderation.repository.ModerationActionRepository;
import com.safesphere.backend.moderation.repository.ModerationAppealRepository;
import com.safesphere.backend.moderation.repository.ModerationCaseRepository;
import com.safesphere.backend.moderation.service.ModerationService;
import com.safesphere.backend.support.CommunityClient;
import com.safesphere.backend.support.CommunityClient.Account;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.TestAccounts;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.entity.UserRole;
import com.safesphere.backend.user.repository.UserRepository;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 7 moderation through the real API: who may moderate, flags and automatic quarantine, case views without
 * identities, decisions and their consequences, restrictions that never touch safety features, appeals to another
 * moderator, role changes, and the audit log.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class ModerationApiIntegrationTest {

    /** Bangalore; all in area cell "tdr1v9". */
    static final String CELL = "tdr1v9q";
    static final String AREA = "tdr1v9";
    static final String FAR = GeoCell.encode(13.0716, 77.5946, 7);

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

    // ---- Who may moderate ---------------------------------------------------------------------------------------------

    @Test
    void normalUsers_cannotReachModerationOrAdministration_andNobodyWithoutAToken() throws Exception {
        Account user = api.account();
        api.getAs(user, "/api/moderation/cases").andExpect(status().isForbidden());
        api.getAs(user, "/api/moderation/appeals").andExpect(status().isForbidden());
        api.getAs(user, "/api/moderation/cases/" + UUID.randomUUID()).andExpect(status().isForbidden());
        api.postAs(user, "/api/moderation/cases/" + UUID.randomUUID() + "/actions",
                Map.of("action", "DISMISS", "reason", "NO_VIOLATION")).andExpect(status().isForbidden());
        api.putAs(user, "/api/admin/roles", Map.of("email", user.email(), "role", "MODERATOR"))
                .andExpect(status().isForbidden());
        api.getAs(user, "/api/admin/moderation-log").andExpect(status().isForbidden());

        Account moderator = withRole(api.account(), UserRole.MODERATOR);
        api.getAs(moderator, "/api/admin/moderation-log").andExpect(status().isForbidden());
        api.putAs(moderator, "/api/admin/roles", Map.of("email", user.email(), "role", "MODERATOR"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/moderation/cases")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/moderation/access")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/moderation-log")).andExpect(status().isUnauthorized());
        assertEquals(UserRole.USER, users.findById(user.id()).orElseThrow().getRole(), "nothing changed");
    }

    @Test
    void access_tellsTheAppWhatToShow() throws Exception {
        api.getAs(api.account(), "/api/moderation/access").andExpect(status().isOk())
                .andExpect(jsonPath("$.canModerate").value(false)).andExpect(jsonPath("$.canAdminister").value(false));
        api.getAs(withRole(api.account(), UserRole.MODERATOR), "/api/moderation/access")
                .andExpect(jsonPath("$.canModerate").value(true)).andExpect(jsonPath("$.canAdminister").value(false));
        api.getAs(withRole(api.account(), UserRole.ADMIN), "/api/moderation/access")
                .andExpect(jsonPath("$.canModerate").value(true)).andExpect(jsonPath("$.canAdminister").value(true));
    }

    @Test
    void aRoleCannotBeClaimedByTheClient() throws Exception {
        String email = "claims.admin." + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "X", "email", email,
                                "password", TestAccounts.PASSWORD, "role", "ADMIN",
                                "dateOfBirth", TestAccounts.ADULT_BIRTH_DATE, "phone", TestAccounts.phoneFor(email)))))
                .andExpect(status().isCreated());
        assertEquals(UserRole.USER, users.findByEmail(email).orElseThrow().getRole());
    }

    // ---- Flags and automatic quarantine ---------------------------------------------------------------------------

    @Test
    void oneFlag_opensACase_butTheSignalStaysVisible() throws Exception {
        Account reporter = api.account();
        api.report(reporter, "POOR_LIGHTING", CELL).andExpect(status().isAccepted());
        Account flagger = api.account();
        String id = api.onlySignalId(flagger, AREA);
        api.flag(flagger, id, "FALSE_INFORMATION", CELL).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RECEIVED"));

        assertEquals(1, cases.count());
        assertEquals(1, api.area(api.account(), AREA).get("signals").size());
        assertTrue(api.area(flagger, AREA).get("signals").get(0).get("youFlagged").asBoolean());
        assertFalse(api.area(reporter, AREA).get("signals").get(0).get("youFlagged").asBoolean(),
                "nobody learns that someone else flagged it");
    }

    @Test
    void twoIndependentFlags_hideTheSignalFromEveryone_contributorsSeeUnderReview_andCannotWithdraw() throws Exception {
        Account reporter = api.account();
        api.report(reporter, "HARASSMENT", CELL);
        Account b = api.account();
        Account c = api.account();
        String id = api.onlySignalId(b, AREA);
        String first = api.flag(b, id, "TARGETS_SOMEONE", CELL).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String second = api.flag(c, id, "TARGETS_SOMEONE", CELL).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, second, "the answer never reveals whether a flag hid the signal");

        Account someone = api.account();
        assertEquals(0, api.area(someone, AREA).get("signals").size());
        api.contribute(someone, id, "CONFIRM", CELL).andExpect(status().isNotFound());
        api.flag(someone, id, "FALSE_INFORMATION", CELL).andExpect(status().isNotFound());

        api.getAs(reporter, "/api/community/mine").andExpect(jsonPath("$[0].status").value("UNDER_REVIEW"));
        api.deleteAs(reporter, "/api/community/mine/" + id).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This signal is being reviewed. You can withdraw it after the review."));
    }

    @Test
    void flagRules_oneFlagPerPerson_neverOnYourOwnSignal_onlyNearby() throws Exception {
        Account reporter = api.account();
        api.report(reporter, "POOR_LIGHTING", CELL);
        Account b = api.account();
        String id = api.onlySignalId(b, AREA);

        api.flag(reporter, id, "FALSE_INFORMATION", CELL).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You added to this signal. To take that back, withdraw your own answer."));
        api.flag(b, id, "FALSE_INFORMATION", FAR).andExpect(status().isConflict());
        api.flag(b, id, "FALSE_INFORMATION", CELL).andExpect(status().isAccepted());
        api.flag(b, id, "SPAM_OR_COORDINATED", CELL).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You have already flagged this signal"));
        api.contribute(b, id, "CONFIRM", CELL).andExpect(status().isConflict());
        long before = contributions.count();
        api.report(b, "POOR_LIGHTING", CELL).andExpect(status().isAccepted());
        assertEquals(before, contributions.count(), "a flagger's report of the same signal is not counted");
        api.flag(b, UUID.randomUUID().toString(), "FALSE_INFORMATION", CELL).andExpect(status().isNotFound());
        api.flag(b, id, "SUSPICIOUS_PERSON", CELL).andExpect(status().isBadRequest());
    }

    @Test
    void flagsPerDay_areLimited() throws Exception {
        Account flagger = api.account();
        for (int i = 0; i < 11; i++) {
            String cell = AREA + "0123456789bcdefghjk".charAt(i);
            api.report(api.account(), "POOR_LIGHTING", cell).andExpect(status().isAccepted());
        }
        JsonNode list = api.area(flagger, AREA).get("signals");
        assertEquals(11, list.size());
        for (int i = 0; i < 10; i++) {
            api.flag(flagger, list.get(i).get("id").asText(), "SPAM_OR_COORDINATED", CELL).andExpect(status().isAccepted());
        }
        api.flag(flagger, list.get(10).get("id").asText(), "SPAM_OR_COORDINATED", CELL)
                .andExpect(status().isTooManyRequests());
    }

    // ---- What a moderator sees -------------------------------------------------------------------------------------

    @Test
    void caseView_usesCaseScopedPseudonyms_andNeverIdentifiesAnyone() throws Exception {
        Account reporter = api.account();
        api.report(reporter, "POOR_LIGHTING", CELL);
        api.report(reporter, "BROKEN_PATH", CELL);
        Account flagger = api.account();
        for (JsonNode signal : api.area(flagger, AREA).get("signals")) {
            api.flag(flagger, signal.get("id").asText(), "FALSE_INFORMATION", CELL).andExpect(status().isAccepted());
        }
        Account moderator = withRole(api.account(), UserRole.MODERATOR);
        JsonNode queue = api.json(api.getAs(moderator, "/api/moderation/cases").andExpect(status().isOk()));
        assertEquals(2, queue.size());
        assertEquals("FLAGS", queue.get(0).get("origin").asText());
        assertEquals(1, queue.get(0).get("flags").get("FALSE_INFORMATION").asInt());

        Set<String> reporterRefs = new HashSet<>();
        for (JsonNode summary : queue) {
            String raw = api.getAs(moderator, "/api/moderation/cases/" + summary.get("id").asText())
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            for (String secret : new String[]{reporter.email(), flagger.email(), "Phase Seven", "\"userId\"", "\"email\""}) {
                assertFalse(raw.contains(secret), "a moderator must not see " + secret);
            }
            JsonNode detail = objectMapper.readTree(raw);
            assertEquals(CELL, detail.get("cell").asText());
            for (JsonNode p : detail.get("participants")) {
                assertTrue(p.get("ref").asText().matches("^[0-9a-f]{12}$"));
                assertOnly(p, "ref", "role", "flag", "tier", "accountAge", "counting", "linkGroup");
                if ("REPORT".equals(p.get("role").asText())) reporterRefs.add(p.get("ref").asText());
            }
        }
        assertEquals(2, reporterRefs.size(), "the same person has a different pseudonym in every case");
    }

    @Test
    void aModeratorWhoTookPart_cannotSeeOrDecideTheCase() throws Exception {
        Account reporter = api.account();
        api.report(reporter, "POOR_LIGHTING", CELL);
        Account involved = withRole(api.account(), UserRole.MODERATOR);
        String id = api.onlySignalId(involved, AREA);
        api.contribute(involved, id, "CONFIRM", CELL).andExpect(status().isAccepted());
        api.flag(api.account(), id, "FALSE_INFORMATION", CELL).andExpect(status().isAccepted());
        Account other = withRole(api.account(), UserRole.MODERATOR);
        String caseId = onlyOpenCaseId(other);

        // The queue says so up front (the app labels the case instead of failing on tap) ...
        api.getAs(involved, "/api/moderation/cases").andExpect(jsonPath("$[0].youTookPart").value(true));
        api.getAs(other, "/api/moderation/cases").andExpect(jsonPath("$[0].youTookPart").value(false));
        // ... and the server refuses anyway, whatever the app shows.
        api.getAs(involved, "/api/moderation/cases/" + caseId).andExpect(status().isForbidden());
        decide(involved, caseId, "DISMISS", "NO_VIOLATION").andExpect(status().isForbidden());
        decide(withRole(api.account(), UserRole.MODERATOR), caseId, "DISMISS", "NO_VIOLATION").andExpect(status().isOk());
    }

    // ---- Decisions ---------------------------------------------------------------------------------------------------

    @Test
    void dismiss_restoresTheSignal_closesTheCase_andTheSameFlaggersCannotFlagAgain() throws Exception {
        Account reporter = api.account();
        api.report(reporter, "POOR_LIGHTING", CELL);
        Account b = api.account();
        Account c = api.account();
        String id = api.onlySignalId(b, AREA);
        api.flag(b, id, "FALSE_INFORMATION", CELL);
        api.flag(c, id, "FALSE_INFORMATION", CELL);
        assertEquals(0, api.area(api.account(), AREA).get("signals").size());

        Account moderator = withRole(api.account(), UserRole.MODERATOR);
        String caseId = onlyOpenCaseId(moderator);
        decide(moderator, caseId, "DISMISS", "FALSE_INFORMATION").andExpect(status().isBadRequest());
        decide(moderator, caseId, "DISMISS", "NO_VIOLATION").andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("CLOSED"))
                .andExpect(jsonPath("$.summary.outcome").value("DISMISSED"));

        assertEquals(1, api.area(api.account(), AREA).get("signals").size(), "visible again");
        decide(moderator, caseId, "REMOVE", "FALSE_INFORMATION").andExpect(status().isConflict());
        api.flag(b, id, "FALSE_INFORMATION", CELL).andExpect(status().isConflict());
        assertEquals(1, standing(b).getFlagsRejected());
        assertEquals(0, standing(reporter).getStrikes());
    }

    @Test
    void aDismissedCoordinationCase_isNotReopenedByTheNextRun_andItsReporterCanWithdrawAgain() throws Exception {
        // Two accounts back the same three places, so they are linked and every signal goes to a human (COORDINATION).
        Account owner = api.account();
        Account partner = api.account();
        for (String cell : new String[]{"tdr1v9q", "tdr1v9r", "tdr1v9w"}) {
            api.report(owner, "POOR_LIGHTING", cell).andExpect(status().isAccepted());
        }
        for (JsonNode signal : api.area(partner, AREA).get("signals")) {
            api.contribute(partner, signal.get("id").asText(), "CONFIRM", CELL).andExpect(status().isAccepted());
        }
        moderationService.maintain();
        Account moderator = withRole(api.account(), UserRole.MODERATOR);
        JsonNode queue = api.json(api.getAs(moderator, "/api/moderation/cases").andExpect(status().isOk()));
        assertEquals(3, queue.size());
        assertEquals("COORDINATION", queue.get(0).get("origin").asText());
        String caseId = queue.get(0).get("id").asText();
        decide(moderator, caseId, "DISMISS", "NO_VIOLATION").andExpect(status().isOk());

        // The next clean-up runs leave the dismissed signal alone; the other two stay open, once each.
        moderationService.maintain();
        moderationService.maintain();
        long dismissedSignal = cases.findByPublicId(caseId).orElseThrow().getSignalId();
        assertEquals(1, cases.findAll().stream().filter(c -> c.getSignalId() == dismissedSignal).count(),
                "a moderator found this one fine: it must not be put in front of them again");
        assertEquals(2, api.json(api.getAs(moderator, "/api/moderation/cases").andExpect(status().isOk())).size());

        // So no reopened case blocks its reporter from withdrawing any more.
        String publicId = signals.findById(dismissedSignal).orElseThrow().getPublicId();
        api.deleteAs(owner, "/api/community/mine/" + publicId).andExpect(status().isNoContent());
    }

    @Test
    void remove_hidesTheSignal_strikesItsReporter_andARemovedFakeCannotSimplyBeRecreated() throws Exception {
        Account reporter = api.account();
        api.report(reporter, "HARASSMENT", CELL);
        Account b = api.account();
        Account c = api.account();
        String id = api.onlySignalId(b, AREA);
        api.flag(b, id, "TARGETS_SOMEONE", CELL);
        api.flag(c, id, "TARGETS_SOMEONE", CELL);
        Account moderator = withRole(api.account(), UserRole.MODERATOR);
        decide(moderator, onlyOpenCaseId(moderator), "REMOVE", "TARGETS_SOMEONE").andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.outcome").value("REMOVED"));

        assertEquals(1, standing(reporter).getStrikes());
        assertEquals(1, standing(b).getFlagsUpheld());
        assertEquals(0, contributions.count(), "what was judged abusive is not kept");
        api.getAs(reporter, "/api/community/mine").andExpect(jsonPath("$.length()").value(0));

        // Re-creating it: accepted with the usual answer, but it stays hidden (tombstone) ...
        Account d = api.account();
        Account e = api.account();
        api.report(d, "HARASSMENT", CELL).andExpect(status().isAccepted());
        api.report(e, "HARASSMENT", CELL).andExpect(status().isAccepted());
        assertEquals(0, api.area(api.account(), AREA).get("signals").size());
        // ... and independent re-reports put it in front of a human again.
        moderationService.maintain();
        JsonNode queue = api.json(api.getAs(moderator, "/api/moderation/cases"));
        assertEquals(1, queue.size());
        assertEquals("REREPORTED", queue.get(0).get("origin").asText());
    }

    @Test
    void aStruckReportersLoneReport_waitsUntilSomeoneIndependentReportsTheSamePlace() throws Exception {
        Account reporter = api.account();
        ContributorStanding standing = new ContributorStanding(reporter.id(), java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        standing.addStrike();
        standings.save(standing);

        api.report(reporter, "POOR_LIGHTING", CELL).andExpect(status().isAccepted());
        assertEquals(0, api.area(api.account(), AREA).get("signals").size());
        api.getAs(reporter, "/api/community/mine").andExpect(jsonPath("$[0].status").value("NOT_SHOWN"));

        api.report(api.account(), "POOR_LIGHTING", CELL).andExpect(status().isAccepted());
        JsonNode signal = api.area(api.account(), AREA).get("signals").get(0);
        assertEquals("BLOCK", signal.get("precision").asText());
    }

    // ---- Restrictions and appeals ----------------------------------------------------------------------------------

    @Test
    void restriction_pausesCommunityContributionsOnly_andAnotherModeratorCanOverturnIt() throws Exception {
        Account reporter = api.account();
        api.report(reporter, "THEFT_OR_SNATCHING", CELL);
        Account flagger = api.account();
        api.flag(flagger, api.onlySignalId(flagger, AREA), "FALSE_INFORMATION", CELL);
        Account first = withRole(api.account(), UserRole.MODERATOR);
        String caseId = onlyOpenCaseId(first);
        String reporterRef = refOf(first, caseId, "REPORT");

        decide(first, caseId, Map.of("action", "RESTRICT", "reason", "FALSE_INFORMATION", "contributorRef", "0123456789ab",
                "days", 7)).andExpect(status().isNotFound());
        decide(first, caseId, Map.of("action", "RESTRICT", "reason", "FALSE_INFORMATION", "contributorRef", reporterRef,
                "days", 7)).andExpect(status().isOk());

        api.report(reporter, "POOR_LIGHTING", "tdr1v9r").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Your community contributions are paused for now. You can still "
                        + "see signals, and every other safety feature works as usual."));
        api.getAs(reporter, "/api/community/area?cell=" + AREA).andExpect(status().isOk());
        // Regression: a community pause never touches SOS, Safe Walk, the Safety Circle or the Evidence Vault.
        api.getAs(reporter, "/api/emergencies").andExpect(status().isOk());
        api.getAs(reporter, "/api/journeys").andExpect(status().isOk());
        api.getAs(reporter, "/api/safety-contacts").andExpect(status().isOk());
        api.getAs(reporter, "/api/evidence/vault-key").andExpect(status().isOk());
        api.postAs(reporter, "/api/emergencies", Map.of("triggerType", "MANUAL")).andExpect(status().isCreated());
        JsonNode mine = api.json(api.getAs(reporter, "/api/community/standing").andExpect(status().isOk()));
        assertTrue(mine.get("contributionsPaused").asBoolean());
        assertEquals("FALSE_INFORMATION", mine.get("reason").asText());
        assertTrue(mine.get("canAppeal").asBoolean());
        assertOnly(mine, "contributionsPaused", "pausedUntil", "reason", "appeal", "canAppeal");

        api.postAs(reporter, "/api/community/appeals", Map.of()).andExpect(status().isAccepted());
        api.postAs(reporter, "/api/community/appeals", Map.of()).andExpect(status().isConflict());

        JsonNode appealsForFirst = api.json(api.getAs(first, "/api/moderation/appeals"));
        assertTrue(appealsForFirst.get(0).get("youDecidedTheRestriction").asBoolean());
        assertOnly(appealsForFirst.get(0), "id", "status", "createdOn", "restrictionReason", "category",
                "restrictedUntil", "currentTier", "strikes", "youDecidedTheRestriction");
        String appealId = appealsForFirst.get(0).get("id").asText();
        api.postAs(first, "/api/moderation/appeals/" + appealId + "/decision", Map.of("decision", "OVERTURNED"))
                .andExpect(status().isForbidden());

        Account second = withRole(api.account(), UserRole.MODERATOR);
        api.postAs(second, "/api/moderation/appeals/" + appealId + "/decision", Map.of("decision", "OVERTURNED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OVERTURNED"));
        api.postAs(second, "/api/moderation/appeals/" + appealId + "/decision", Map.of("decision", "UPHELD"))
                .andExpect(status().isConflict());

        assertFalse(api.json(api.getAs(reporter, "/api/community/standing")).get("contributionsPaused").asBoolean());
        assertEquals(0, standing(reporter).getStrikes());
        api.report(reporter, "POOR_LIGHTING", "tdr1v9r").andExpect(status().isAccepted());

        Account admin = withRole(api.account(), UserRole.ADMIN);
        JsonNode log = api.json(api.getAs(admin, "/api/admin/moderation-log").andExpect(status().isOk()));
        assertEquals("APPEAL_OVERTURNED", log.get(0).get("action").asText());
        assertEquals(second.id(), log.get(0).get("actorId").asLong());
        assertEquals(reporter.id(), log.get(0).get("subjectUserId").asLong());
        assertEquals("RESTRICT", log.get(1).get("action").asText());
        assertEquals(first.id(), log.get(1).get("actorId").asLong());
    }

    @Test
    void everyDecision_isAudited_withActorReasonAndCase_andNothingElseAboutPeople() throws Exception {
        Account reporter = api.account();
        api.report(reporter, "POOR_LIGHTING", CELL);
        Account flagger = api.account();
        api.flag(flagger, api.onlySignalId(flagger, AREA), "FALSE_INFORMATION", CELL);
        Account moderator = withRole(api.account(), UserRole.MODERATOR);
        String caseId = onlyOpenCaseId(moderator);
        decide(moderator, caseId, "QUARANTINE", "NEEDS_REVIEW").andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.moderation").value("QUARANTINED"));
        decide(moderator, caseId, "QUARANTINE", "NEEDS_REVIEW").andExpect(status().isConflict());
        decide(moderator, caseId, "DISMISS", "NO_VIOLATION").andExpect(status().isOk());

        Account admin = withRole(api.account(), UserRole.ADMIN);
        JsonNode log = api.json(api.getAs(admin, "/api/admin/moderation-log").andExpect(status().isOk()));
        assertEquals(2, log.size(), "the refused second quarantine leaves no entry");
        assertEquals("DISMISS", log.get(0).get("action").asText());
        assertEquals("QUARANTINE", log.get(1).get("action").asText());
        for (JsonNode entry : log) {
            assertEquals(moderator.id(), entry.get("actorId").asLong());
            assertEquals(caseId, entry.get("caseRef").asText());
            assertEquals("POOR_LIGHTING", entry.get("category").asText());
            assertTrue(entry.get("subjectUserId").isNull(), "decisions about content name nobody");
            assertOnly(entry, "id", "actorId", "action", "reason", "caseRef", "category", "subjectUserId",
                    "restrictedUntil", "newRole", "occurredAt");
        }
        assertFalse(log.toString().contains(reporter.email()) || log.toString().contains(flagger.email()));
    }

    @Test
    void aRestrictedPersonsContributions_stopCountingAtOnce() throws Exception {
        Account reporter = api.account();
        api.report(reporter, "POOR_LIGHTING", CELL);
        Account confirmer = api.account();
        String id = api.onlySignalId(confirmer, AREA);
        api.contribute(confirmer, id, "CONFIRM", CELL).andExpect(status().isAccepted());
        assertEquals("BLOCK", api.area(api.account(), AREA).get("signals").get(0).get("precision").asText());

        api.flag(api.account(), id, "SPAM_OR_COORDINATED", CELL);
        Account moderator = withRole(api.account(), UserRole.MODERATOR);
        String caseId = onlyOpenCaseId(moderator);
        decide(moderator, caseId, Map.of("action", "RESTRICT", "reason", "SPAM_OR_COORDINATED",
                "contributorRef", refOf(moderator, caseId, "CONFIRM"))).andExpect(status().isOk());

        JsonNode signal = api.area(api.account(), AREA).get("signals").get(0);
        assertEquals("AREA", signal.get("precision").asText(), "the restricted confirmation no longer counts");
        assertEquals("LOW", signal.get("confidence").asText());
    }

    @Test
    void noAppealWithoutARestriction() throws Exception {
        Account someone = api.account();
        api.postAs(someone, "/api/community/appeals", Map.of()).andExpect(status().isConflict());
        api.getAs(someone, "/api/community/standing").andExpect(jsonPath("$.contributionsPaused").value(false))
                .andExpect(jsonPath("$.canAppeal").value(false));
    }

    // ---- Roles -------------------------------------------------------------------------------------------------------

    @Test
    void roleChanges_takeEffectOnTheVeryNextRequest_withTheSameToken_andAreAudited() throws Exception {
        Account admin = withRole(api.account(), UserRole.ADMIN);
        Account helper = api.account();
        api.getAs(helper, "/api/moderation/cases").andExpect(status().isForbidden());

        api.putAs(admin, "/api/admin/roles", Map.of("email", helper.email().toUpperCase(), "role", "MODERATOR"))
                .andExpect(status().isNoContent());
        api.getAs(helper, "/api/moderation/cases").andExpect(status().isOk());

        api.putAs(admin, "/api/admin/roles", Map.of("email", helper.email(), "role", "USER")).andExpect(status().isNoContent());
        api.getAs(helper, "/api/moderation/cases").andExpect(status().isForbidden());

        api.putAs(admin, "/api/admin/roles", Map.of("email", helper.email(), "role", "ADMIN")).andExpect(status().isBadRequest());
        api.putAs(admin, "/api/admin/roles", Map.of("email", admin.email(), "role", "USER")).andExpect(status().isConflict());
        Account otherAdmin = withRole(api.account(), UserRole.ADMIN);
        api.putAs(admin, "/api/admin/roles", Map.of("email", otherAdmin.email(), "role", "USER")).andExpect(status().isConflict());
        api.putAs(admin, "/api/admin/roles", Map.of("email", "nobody." + UUID.randomUUID() + "@example.com", "role", "MODERATOR"))
                .andExpect(status().isNotFound());

        JsonNode log = api.json(api.getAs(admin, "/api/admin/moderation-log"));
        assertEquals("ROLE_REVOKED", log.get(0).get("action").asText());
        assertEquals("ROLE_GRANTED", log.get(1).get("action").asText());
        assertEquals(helper.id(), log.get(1).get("subjectUserId").asLong());
    }

    @Test
    void aDisabledModerator_hasNoAccess() throws Exception {
        Account moderator = withRole(api.account(), UserRole.MODERATOR);
        User user = users.findById(moderator.id()).orElseThrow();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "enabled", false);
        users.save(user);
        // Phase 10A: refused even earlier now. The per-request account check (ActiveAccountCheck) rejects a disabled
        // account's token on every endpoint (401); before Phase 10A only the role check refused it here (403).
        api.getAs(moderator, "/api/moderation/cases").andExpect(status().isUnauthorized());
        api.getAs(moderator, "/api/moderation/access").andExpect(status().isUnauthorized());
    }

    @Test
    void caseIdsAreRandom_andUnknownOnesAre404() throws Exception {
        Account moderator = withRole(api.account(), UserRole.MODERATOR);
        api.getAs(moderator, "/api/moderation/cases/" + UUID.randomUUID()).andExpect(status().isNotFound());
        api.getAs(moderator, "/api/moderation/cases?status=WHATEVER").andExpect(status().isBadRequest());
        api.postAs(moderator, "/api/moderation/appeals/" + UUID.randomUUID() + "/decision", Map.of("decision", "UPHELD"))
                .andExpect(status().isNotFound());
        api.postAs(moderator, "/api/moderation/appeals/" + UUID.randomUUID() + "/decision", Map.of("decision", "OPEN"))
                .andExpect(status().isBadRequest());
    }

    // ---- Helpers ---------------------------------------------------------------------------------------------------

    private Account withRole(Account account, UserRole role) {
        User user = users.findById(account.id()).orElseThrow();
        user.setRole(role);
        users.save(user);
        return account;
    }

    /** The private standing row, or an all-zero one when the person never needed a row. */
    private ContributorStanding standing(Account account) {
        return standings.findById(account.id())
                .orElseGet(() -> new ContributorStanding(account.id(), java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)));
    }

    private String onlyOpenCaseId(Account moderator) throws Exception {
        JsonNode queue = api.json(api.getAs(moderator, "/api/moderation/cases").andExpect(status().isOk()));
        assertEquals(1, queue.size(), "expected exactly one open case");
        return queue.get(0).get("id").asText();
    }

    private String refOf(Account moderator, String caseId, String role) throws Exception {
        JsonNode detail = api.json(api.getAs(moderator, "/api/moderation/cases/" + caseId).andExpect(status().isOk()));
        for (JsonNode p : detail.get("participants")) {
            if (role.equals(p.get("role").asText())) return p.get("ref").asText();
        }
        throw new AssertionError("no participant with role " + role);
    }

    private org.springframework.test.web.servlet.ResultActions decide(Account moderator, String caseId, String action,
                                                                      String reason) throws Exception {
        return decide(moderator, caseId, Map.of("action", action, "reason", reason));
    }

    private org.springframework.test.web.servlet.ResultActions decide(Account moderator, String caseId,
                                                                      Map<String, Object> body) throws Exception {
        return api.postAs(moderator, "/api/moderation/cases/" + caseId + "/actions", body);
    }

    private static void assertOnly(JsonNode node, String... allowed) {
        Set<String> names = Set.of(allowed);
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            assertTrue(names.contains(field), "unexpected field: " + field);
        }
    }
}
