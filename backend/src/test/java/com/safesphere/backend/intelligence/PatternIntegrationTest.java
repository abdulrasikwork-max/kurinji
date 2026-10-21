package com.safesphere.backend.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.community.entity.SignalCategory.SignalGroup;
import com.safesphere.backend.community.repository.ContributorStandingRepository;
import com.safesphere.backend.community.repository.PlaceSignalRepository;
import com.safesphere.backend.community.repository.SignalContributionRepository;
import com.safesphere.backend.community.repository.SignalFlagRepository;
import com.safesphere.backend.community.service.CommunityService;
import com.safesphere.backend.config.IntelligenceProperties;
import com.safesphere.backend.intelligence.entity.AreaPattern;
import com.safesphere.backend.intelligence.repository.AreaPatternRepository;
import com.safesphere.backend.intelligence.service.PatternService;
import com.safesphere.backend.moderation.repository.ModerationActionRepository;
import com.safesphere.backend.moderation.repository.ModerationAppealRepository;
import com.safesphere.backend.moderation.repository.ModerationCaseRepository;
import com.safesphere.backend.support.CommunityClient;
import com.safesphere.backend.support.CommunityClient.Account;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.entity.UserRole;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.time.LocalDate;
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

/**
 * Phase 8C pattern memory through the real flow: only finished, independently confirmed, unmoderated signals become
 * episodes; three make an area RECURRING (shown only as a reason); a moderator removal forgets the area; nothing about
 * a pattern ever leaves the server except that reason.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class PatternIntegrationTest {

    static final String AREA = "tdr1v9";
    static final String CELL = "tdr1v9q";
    static final Duration PAST_INCIDENT_LIFETIME = Duration.ofDays(3).plusMinutes(1);

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
    @Autowired private AreaPatternRepository patterns;
    @Autowired private PatternService patternService;
    @Autowired private CommunityService community;
    @Autowired private UserRepository users;

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
        patterns.deleteAll();
        api = new CommunityClient(mockMvc, objectMapper);
    }

    @Test
    void confirmedIncidentsThatEndNaturally_areEpisodes_andThreeWeeklyOnesMakeTheAreaRecurring() throws Exception {
        confirmedIncidentThatEnds("HARASSMENT");
        AreaPattern row = patterns.findById(new AreaPattern.Key(AREA, "INCIDENT")).orElseThrow();
        assertEquals(1.0, row.getValue(), 1e-9);
        assertFalse(reasons(picture()).contains("RECURRING_CONCERNS"), "one episode is not a pattern");

        clock.advance(Duration.ofDays(4));
        confirmedIncidentThatEnds("FOLLOWING");
        assertFalse(reasons(picture()).contains("RECURRING_CONCERNS"), "two are not either");
        clock.advance(Duration.ofDays(4));
        confirmedIncidentThatEnds("THEFT_OR_SNATCHING");

        JsonNode quiet = picture();
        assertTrue(reasons(quiet).contains("RECURRING_CONCERNS"));
        assertEquals("LITTLE_INFORMATION", quiet.get("outlook").asText(), "a pattern alone never raises the level");
        assertFalse(quiet.toString().contains("pattern"), "no pattern value, date or count in the answer");

        // A new confirmed incident where incidents keep recurring: extra care (rule c).
        confirmedIncident("HARASSMENT", "tdr1v9r");
        assertEquals("TAKE_EXTRA_CARE", picture().get("outlook").asText());
    }

    @Test
    void aBurstWithinOneWeek_isOneEpisode() throws Exception {
        confirmedIncident("HARASSMENT", "tdr1v9q");
        confirmedIncident("FOLLOWING", "tdr1v9r");
        community.purge();
        clock.advance(PAST_INCIDENT_LIFETIME);
        community.purge();
        assertEquals(1.0, patterns.findById(new AreaPattern.Key(AREA, "INCIDENT")).orElseThrow().getValue(), 1e-9);
    }

    @Test
    void unconfirmedRetiredOrReviewedSignals_areNeverEpisodes() throws Exception {
        // A single report that runs out.
        api.report(api.account(), "HARASSMENT", CELL);
        community.purge();
        clock.advance(PAST_INCIDENT_LIFETIME);
        community.purge();
        assertEquals(0, patterns.count(), "one person's report teaches the area nothing");

        // A confirmed condition later retired ("no longer true").
        String retired = confirmedIncident("POOR_LIGHTING", "tdr1v9r");
        community.purge(); // marks it confirmed
        api.contribute(api.account(), retired, "NO_LONGER", "tdr1v9r").andExpect(status().isAccepted());
        api.contribute(api.account(), retired, "NO_LONGER", "tdr1v9r").andExpect(status().isAccepted());
        community.purge();
        assertEquals(0, signals.count(), "retired and deleted");
        assertEquals(0, patterns.count(), "a fixed place is not remembered as a pattern");

        // A confirmed incident with an open review case at its end.
        String reviewed = confirmedIncident("FOLLOWING", "tdr1v9w");
        community.purge();
        api.flag(api.account(), reviewed, "FALSE_INFORMATION", "tdr1v9w").andExpect(status().isAccepted());
        assertEquals(1, cases.count());
        clock.advance(PAST_INCIDENT_LIFETIME);
        community.purge();
        assertEquals(0, patterns.count(), "nothing under review becomes memory");
    }

    @Test
    void aModeratorRemoval_forgetsTheArea() throws Exception {
        AreaPattern remembered = new AreaPattern(AREA, "INCIDENT");
        remembered.update(3.0, today(), today());
        patterns.save(remembered);
        assertTrue(reasons(picture()).contains("RECURRING_CONCERNS"));

        String id = confirmedIncident("HARASSMENT", CELL);
        api.flag(api.account(), id, "TARGETS_SOMEONE", CELL).andExpect(status().isAccepted());
        api.flag(api.account(), id, "TARGETS_SOMEONE", CELL).andExpect(status().isAccepted());
        Account moderator = withRole(api.account(), UserRole.MODERATOR);
        JsonNode queue = api.json(api.getAs(moderator, "/api/moderation/cases").andExpect(status().isOk()));
        api.postAs(moderator, "/api/moderation/cases/" + queue.get(0).get("id").asText() + "/actions",
                Map.of("action", "REMOVE", "reason", "TARGETS_SOMEONE")).andExpect(status().isOk());

        assertFalse(patterns.findById(new AreaPattern.Key(AREA, "INCIDENT")).isPresent());
        assertFalse(reasons(picture()).contains("RECURRING_CONCERNS"));
    }

    @Test
    void fadedRowsAreDeleted_andSwitchingOffForgetsEverything() {
        patternService.episode(AREA, SignalGroup.INCIDENT);
        patternService.episode(AREA, SignalGroup.HAVEN);
        assertEquals(1, patterns.count(), "havens have no memory");
        clock.advance(Duration.ofDays(121));
        assertEquals(1, patternService.forgetFaded());
        assertEquals(0, patterns.count());

        patternService.episode(AREA, SignalGroup.CONDITION);
        PatternService off = new PatternService(patterns, new IntelligenceProperties(false, null, null, null, null,
                null, null, null, null, null, null, null), clock);
        assertTrue(off.recurringIn(List.of(AREA)).isEmpty());
        off.episode(AREA, SignalGroup.INCIDENT);
        assertEquals(1, off.forgetFaded(), "switched off: nothing remembered is kept");
        assertEquals(0, patterns.count());
    }

    // ---- Helpers ---------------------------------------------------------------------------------------------------

    /** Reported by one person, confirmed by another (MODERATE, BLOCK precision). Returns the signal id. */
    private String confirmedIncident(String category, String cell) throws Exception {
        api.report(api.account(), category, cell).andExpect(status().isAccepted());
        String id = null;
        for (JsonNode s : api.area(api.account(), AREA).get("signals")) {
            if (s.get("category").asText().equals(category)) id = s.get("id").asText();
        }
        api.contribute(api.account(), id, "CONFIRM", cell).andExpect(status().isAccepted());
        return id;
    }

    /** A confirmed incident that the job sees confirmed, then sees end by its lifetime. */
    private void confirmedIncidentThatEnds(String category) throws Exception {
        confirmedIncident(category, CELL);
        community.purge();
        clock.advance(PAST_INCIDENT_LIFETIME);
        community.purge();
    }

    private JsonNode picture() throws Exception {
        return api.area(api.account(), AREA).get("picture");
    }

    private static List<String> reasons(JsonNode picture) {
        List<String> list = new ArrayList<>();
        picture.get("reasons").forEach(r -> list.add(r.asText()));
        return list;
    }

    private Account withRole(Account account, UserRole role) {
        User user = users.findById(account.id()).orElseThrow();
        user.setRole(role);
        users.save(user);
        return account;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
