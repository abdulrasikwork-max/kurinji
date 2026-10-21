package com.safesphere.backend.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.community.entity.SignalCategory;
import com.safesphere.backend.community.repository.PlaceSignalRepository;
import com.safesphere.backend.community.service.CommunityService;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.evaluation.repository.EvaluationCounterRepository;
import com.safesphere.backend.evaluation.service.WeeklySnapshot;
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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 9B: a counter that cannot be written NEVER fails the operation it counts. Here the counter store is broken
 * (every call throws, as if the table were missing or the database refused it), and every counted operation still
 * succeeds: reports, answers, flags, withdrawals, moderator decisions, the clean-up and the weekly snapshot.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class EvaluationCounterFailureTest {

    @MockitoBean private EvaluationCounterRepository counters;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;
    @Autowired private PlaceSignalRepository signals;
    @Autowired private ModerationCaseRepository cases;
    @Autowired private UserRepository users;
    @Autowired private CommunityService community;
    @Autowired private ModerationService moderation;
    @Autowired private WeeklySnapshot snapshot;

    private CommunityClient api;

    @BeforeEach
    void brokenCounters() {
        clock.reset();
        RuntimeException broken = new DataAccessResourceFailureException("counter store unavailable");
        when(counters.increment(any(), any(), anyString(), anyString(), anyInt())).thenThrow(broken);
        when(counters.insert(any(), anyString(), anyString(), anyString(), anyInt())).thenThrow(broken);
        when(counters.existsByKeyWeekStartAndKeyMetric(any(), any())).thenThrow(broken);
        when(counters.deleteOlderThan(any())).thenThrow(broken);
        when(counters.deleteEverything()).thenThrow(broken);
        api = new CommunityClient(mockMvc, objectMapper);
    }

    @Test
    void everyCountedOperation_stillSucceeds() throws Exception {
        String cell = GeoCell.encode(-37.0, -157.0, GeoCell.BLOCK_PRECISION);
        Account alice = api.account();
        api.report(alice, "POOR_LIGHTING", cell).andExpect(status().isAccepted());
        String id = signals.findByCellAndCategory(cell, SignalCategory.POOR_LIGHTING).orElseThrow().getPublicId();
        api.contribute(api.account(), id, "CONFIRM", cell).andExpect(status().isAccepted());
        community.purge(); // confirmation counted (and lost)
        api.flag(api.account(), id, "FALSE_INFORMATION", cell).andExpect(status().isAccepted());

        Account moderator = api.account();
        User user = users.findById(moderator.id()).orElseThrow();
        user.setRole(UserRole.MODERATOR);
        users.save(user);
        String caseId = cases.findAll().stream().filter(c -> c.getSignalId()
                .equals(signals.findByPublicId(id).orElseThrow().getId())).findFirst().orElseThrow().getPublicId();
        api.postAs(moderator, "/api/moderation/cases/" + caseId + "/actions",
                Map.of("action", "DISMISS", "reason", "NO_VIOLATION")).andExpect(status().isOk());

        String other = GeoCell.encode(-37.5, -157.0, GeoCell.BLOCK_PRECISION);
        api.report(alice, "HARASSMENT", other).andExpect(status().isAccepted());
        api.deleteAs(alice, "/api/community/mine/" + signals.findByCellAndCategory(other, SignalCategory.HARASSMENT)
                .orElseThrow().getPublicId()).andExpect(status().isNoContent());

        moderation.maintain();
        clock.advance(Duration.ofDays(31));
        community.purge(); // expiry counted (and lost)
        assertEquals(0, signals.findAll().stream().filter(s -> s.getCell().equals(cell)).count(),
                "the clean-up still did its work");
        assertFalse(snapshot.run(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).taken(),
                "the snapshot gives up quietly and is tried again next run");
    }
}
