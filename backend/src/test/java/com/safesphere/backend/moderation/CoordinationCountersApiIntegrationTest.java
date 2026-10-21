package com.safesphere.backend.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import com.safesphere.backend.evaluation.entity.EvaluationCounter;
import com.safesphere.backend.evaluation.entity.Metric;
import com.safesphere.backend.evaluation.repository.EvaluationCounterRepository;
import com.safesphere.backend.moderation.entity.ModerationCase;
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
import java.util.ArrayList;
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
 * Phase 9B with coord-1 in ACTIVE (the same context as CoordinationApiIntegrationTest): a coord-1 case is counted
 * against coord-1's rule when it is opened AND when it is decided, even after a whole-group pause made the pattern
 * disappear. That is why V15 stores the opening rule on the case: recomputing it at decision time (the design's plan)
 * would attribute this decision to nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
@TestPropertySource(properties = {"app.moderation.coordination.mode=ACTIVE",
        "app.moderation.coordination.max-cases-per-day=1"})
class CoordinationCountersApiIntegrationTest {

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
    @Autowired private EvaluationCounterRepository counters;
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
        counters.deleteAll();
        api = new CommunityClient(mockMvc, objectMapper);
    }

    @Test
    void aCoordOneDecision_isCountedAgainstItsRule_evenAfterTheWholeGroupWasPaused() throws Exception {
        List<Account> ring = new ArrayList<>();
        for (int i = 0; i < 4; i++) ring.add(api.account());
        int p = 0;
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                String area = GeoCell.encode(-35 + (p / 4) * 0.05, -155 + (p % 4) * 0.05, 6);
                api.report(ring.get(i), "POOR_LIGHTING", area + "0").andExpect(status().isAccepted());
                api.report(ring.get(j), "ISOLATED_STRETCH", area + "1").andExpect(status().isAccepted());
                confirm(ring.get(j), "POOR_LIGHTING", area + "0");
                confirm(ring.get(i), "ISOLATED_STRETCH", area + "1");
                p++;
            }
        }
        community.purge(); // the clean-up sees the ring's places confirmed (they were shown as "take extra care")
        moderation.maintain();
        moderation.maintain();
        List<ModerationCase> open = cases.findAll();
        assertEquals(1, open.size());
        assertEquals(CoordinationRule.SUPPORT_CORE, open.get(0).getCoordinationRule(), "the opening rule is stored");
        assertEquals(1, count(Metric.CASE_OPENED, "SUPPORT_CORE", "coord-1"));
        assertEquals(0, count(Metric.COORDINATION_SHADOW, "SUPPORT_CORE", "coord-1"), "ACTIVE is not counted as shadow");

        Account moderator = api.account();
        User user = users.findById(moderator.id()).orElseThrow();
        user.setRole(UserRole.MODERATOR);
        users.save(user);
        String url = "/api/moderation/cases/" + open.get(0).getPublicId();
        api.postAs(moderator, url + "/actions", Map.of("action", "RESTRICT", "reason", "SPAM_OR_COORDINATED",
                "wholeGroup", true, "days", 7)).andExpect(status().isOk());
        JsonNode pattern = api.json(api.getAs(moderator, url).andExpect(status().isOk())).get("coordination");
        assertTrue(pattern == null || pattern.isNull(),
                "the pattern is gone once everyone is paused: it cannot be recomputed at decision time");

        api.postAs(moderator, url + "/actions", Map.of("action", "REMOVE", "reason", "SPAM_OR_COORDINATED"))
                .andExpect(status().isOk());
        assertEquals(1, count(Metric.CASE_CLOSED, "SUPPORT_CORE.REMOVED", "coord-1"));
        assertEquals(1, count(Metric.SIGNAL_REMOVED, "CONDITION.CONFIRMED_SPAM_OR_COORDINATED", "-"));
        assertEquals(0, count(Metric.CASE_CLOSED, "COORDINATION.REMOVED", "-"));
    }

    private void confirm(Account who, String category, String cell) throws Exception {
        String id = signals.findByCellAndCategory(cell, SignalCategory.valueOf(category)).orElseThrow().getPublicId();
        api.contribute(who, id, "CONFIRM", cell).andExpect(status().isAccepted());
    }

    private int count(Metric metric, String dimension, String version) {
        return counters.findAll().stream().filter(c -> c.getKey().getMetric() == metric
                        && c.getKey().getDimension().equals(dimension) && c.getKey().getModelVersion().equals(version))
                .mapToInt(EvaluationCounter::getOccurrences).sum();
    }
}
