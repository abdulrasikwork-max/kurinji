package com.safesphere.backend.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.community.entity.PlaceSignal;
import com.safesphere.backend.community.entity.SignalCategory;
import com.safesphere.backend.community.repository.ContributorStandingRepository;
import com.safesphere.backend.community.repository.PlaceSignalRepository;
import com.safesphere.backend.community.repository.SignalContributionRepository;
import com.safesphere.backend.community.repository.SignalFlagRepository;
import com.safesphere.backend.community.service.CommunityService;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Phase 9C.1, S15 Option B (docs/PHASE9.md §9): when a moderator DISMISSES a COORDINATION case ("these are separate
 * people"), the people who were counting behind THAT signal count separately on it, and only on it. Phase 7 still
 * links them everywhere else, nobody who joins later is freed by the decision, and the effect ends with the closed
 * case row. Runs in the shipped mode (SHADOW): the cases here are Phase 7's own COORDINATION cases (R0).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class DismissedCoordinationApiIntegrationTest {

    private static final String[] CONCERNS = {"POOR_LIGHTING", "ISOLATED_STRETCH", "UNSAFE_CROSSING", "BROKEN_PATH"};

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
    @Autowired private CommunityService community;
    @Autowired private PlatformTransactionManager transactions;

    private CommunityClient api;
    /** Open ocean, far from every other test's places. */
    private final String area = GeoCell.encode(-25.0, -145.0, 6);

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

    private String cell(int k) {
        return area + "0123456789bcdefghjkmnpqrstuvwxyz".charAt(k);
    }

    private PlaceSignal signal(int k) {
        return signals.findByCellAndCategory(cell(k), SignalCategory.valueOf(CONCERNS[k])).orElseThrow();
    }

    /** [people] back [places] places of their own street; place k is reported by person k % n, confirmed by the rest. */
    private List<Account> group(int people, int places) throws Exception {
        List<Account> group = new ArrayList<>();
        for (int i = 0; i < people; i++) group.add(api.account());
        for (int k = 0; k < places; k++) {
            api.report(group.get(k % people), CONCERNS[k], cell(k)).andExpect(status().isAccepted());
        }
        for (int k = 0; k < places; k++) {
            for (int i = 0; i < people; i++) {
                if (i != k % people) confirm(group.get(i), k);
            }
        }
        return group;
    }

    private void confirm(Account who, int k) throws Exception {
        api.contribute(who, signal(k).getPublicId(), "CONFIRM", cell(k)).andExpect(status().isAccepted());
    }

    private Account moderator() throws Exception {
        Account account = api.account();
        User user = users.findById(account.id()).orElseThrow();
        user.setRole(UserRole.MODERATOR);
        users.save(user);
        return account;
    }

    private String caseOf(int k) {
        long id = signal(k).getId();
        return cases.findAll().stream().filter(c -> c.getSignalId() == id && c.getStatus() == CaseStatus.OPEN)
                .map(ModerationCase::getPublicId).findFirst().orElseThrow();
    }

    private void dismiss(Account moderator, int k) throws Exception {
        api.postAs(moderator, "/api/moderation/cases/" + caseOf(k) + "/actions",
                Map.of("action", "DISMISS", "reason", "NO_VIOLATION")).andExpect(status().isOk());
    }

    /** What a viewer of the area sees of place k (confidence), or "HIDDEN". */
    private String confidence(int k) throws Exception {
        for (JsonNode s : api.area(api.account(), area).get("signals")) {
            if (s.get("id").asText().equals(signal(k).getPublicId())) return s.get("confidence").asText();
        }
        return "HIDDEN";
    }

    private JsonNode seen(int k) throws Exception {
        for (JsonNode s : api.area(api.account(), area).get("signals")) {
            if (s.get("id").asText().equals(signal(k).getPublicId())) return s;
        }
        throw new AssertionError("place " + k + " not visible");
    }

    private String outlook() throws Exception {
        for (JsonNode cell : api.area(api.account(), area).get("picture").get("areas")) {
            if (cell.get("cell").asText().equals(area)) return cell.get("outlook").asText();
        }
        throw new AssertionError("no picture for the area");
    }

    private long openCoordinationCases() {
        return cases.findAll().stream()
                .filter(c -> c.getOrigin() == CaseOrigin.COORDINATION && c.getStatus() == CaseStatus.OPEN).count();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    @Test
    void anHonestGroupsWarnings_countAgain_oneSignalAtATime_asAModeratorDismissesEachCase() throws Exception {
        group(4, 3); // S15: four residents back the same three places of their street, and nothing else
        moderation.maintain();
        assertEquals("LOW", confidence(0), "Phase 7 links all six pairs: the four count as one person");
        assertEquals("LITTLE_INFORMATION", outlook(), "MEASURED S15 false negative, before any decision");
        assertEquals(3, openCoordinationCases());

        Account moderator = moderator();
        dismiss(moderator, 0);
        assertEquals("HIGH", confidence(0), "a human found these four separate people: on THIS place they count as four");
        assertEquals("LOW", confidence(1), "their other places are still counted once (per signal, not per people)");
        assertEquals("LOW", confidence(2));
        assertEquals("TAKE_CARE", outlook());

        dismiss(moderator, 1);
        dismiss(moderator, 2);
        assertEquals("TAKE_EXTRA_CARE", outlook(), "their real warnings count again");
        moderation.maintain();
        moderation.maintain();
        assertEquals(0, openCoordinationCases(), "a dismissed signal is not put in front of a moderator again");
        assertEquals(3, cases.count());

        community.purge(); // the clean-up job credits reports that now have independent support
        for (int k = 0; k < 3; k++) {
            long reporter = contributions.findBySignalId(signal(k).getId()).stream()
                    .filter(c -> c.getKind().name().equals("REPORT")).findFirst().orElseThrow().getUserId();
            assertEquals(1, standings.findById(reporter).orElseThrow().getCorroborated(),
                    "the private record follows the same human decision");
        }
    }

    @Test
    void aMistakenDismissalOfOwnerAndPuppet_freesThatOneSignal_butLinkedAccountsAddedLaterStillCountOnce()
            throws Exception {
        group(2, 3); // owner + puppet: linked by Phase 7
        moderation.maintain();
        assertEquals(3, openCoordinationCases());
        dismiss(moderator(), 0);           // DOCUMENTED RISK: a careless moderator frees one puppet signal
        assertEquals("MODERATE", confidence(0));
        assertEquals("LOW", confidence(1), "the pair's other signals are unaffected");

        clock.advance(Duration.ofMinutes(5));
        Account secondPuppet = api.account(); // backs everything the owner backs: linked to both
        for (int k = 0; k < 3; k++) confirm(secondPuppet, k);
        JsonNode s0 = seen(0);
        assertEquals("MODERATE", s0.get("confidence").asText(),
                "the decision covered the people the moderator saw counting, not puppets added afterwards");
        assertTrue(s0.get("reasons").toString().contains("LINKED_SUPPORT_COUNTED_ONCE"));
        assertEquals("LOW", confidence(1));
    }

    @Test
    void theEffectEnds_withTheClosedCaseRow() throws Exception {
        group(2, 3);
        moderation.maintain();
        dismiss(moderator(), 0);
        assertEquals("MODERATE", confidence(0));
        // The case-retention step of the clean-up (app.moderation.closed-case-retention, 90 days in production).
        new TransactionTemplate(transactions).executeWithoutResult(
                tx -> cases.deleteClosedBefore(CaseStatus.CLOSED, now().plusMinutes(1)));
        assertEquals("LOW", confidence(0), "no stored exemption outlives the case row");
    }

    @Test
    void dismissingAFlagsCase_doesNotChangeHowLinkedSupportCounts() throws Exception {
        group(2, 3);
        Account outsider = api.account();
        api.flag(outsider, signal(0).getPublicId(), "FALSE_INFORMATION", cell(0)).andExpect(status().isAccepted());
        moderation.maintain();
        ModerationCase flagsCase = cases.findAll().stream().filter(c -> c.getSignalId().equals(signal(0).getId()))
                .findFirst().orElseThrow();
        assertEquals(CaseOrigin.FLAGS, flagsCase.getOrigin());
        dismiss(moderator(), 0);
        assertEquals("LOW", confidence(0),
                "\"the flags were wrong\" is not \"these are separate people\": only a COORDINATION dismissal frees");
    }

    @Test
    void aModeratorBehindTheSignal_cannotDismissIt() throws Exception {
        List<Account> pair = group(2, 3);
        moderation.maintain();
        User owner = users.findById(pair.get(0).id()).orElseThrow();
        owner.setRole(UserRole.MODERATOR);
        users.save(owner);
        String caseId = caseOf(0);
        api.getAs(pair.get(0), "/api/moderation/cases/" + caseId).andExpect(status().isForbidden());
        api.postAs(pair.get(0), "/api/moderation/cases/" + caseId + "/actions",
                Map.of("action", "DISMISS", "reason", "NO_VIOLATION")).andExpect(status().isForbidden());
        assertEquals("LOW", confidence(0), "nobody can free their own support");
    }
}
