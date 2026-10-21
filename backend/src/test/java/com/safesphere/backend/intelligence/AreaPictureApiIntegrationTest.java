package com.safesphere.backend.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.safesphere.backend.support.CommunityClient;
import com.safesphere.backend.support.CommunityClient.Account;
import com.safesphere.backend.support.MutableClock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 8A: the area picture through the real API. A fixed 10-minute publication delay is ON here, so the tests can
 * prove that nothing still pending influences the picture (no timing oracle), and the phone's list is cut at 2
 * signals, so they can prove the picture is computed from the full visible set.
 */
@SpringBootTest(properties = {
        "app.community.publish-delay-min=10m",
        "app.community.publish-delay-max=10m",
        "app.community.new-account-probation=0s",
        "app.community.max-signals-per-area=2"
})
@AutoConfigureMockMvc
class AreaPictureApiIntegrationTest {

    static final String AREA = "tdr1v9";
    static final String CELL = "tdr1v9q";
    static final Duration AFTER_DELAY = Duration.ofMinutes(11);

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock pictureTestClock() {
            return new MutableClock();
        }
    }

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

    @Test
    void picture_isAnAllowListOfCodes_andExplainsAConfirmedCondition() throws Exception {
        String id = confirmedSignal("POOR_LIGHTING", CELL);

        String raw = api.getAs(api.account(), "/api/community/area?cell=" + AREA).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode picture = objectMapper.readTree(raw).get("picture");
        assertEquals("rules-1.1", picture.get("modelVersion").asText(), "Phase 9D: the active rules (rules-2 is SHADOW)");
        assertEquals("TAKE_CARE", picture.get("outlook").asText());
        assertEquals("SOME", picture.get("concern").asText());
        assertEquals("LITTLE", picture.get("information").asText());
        assertEquals(List.of("CONFIRMED_CONDITION", "LITTLE_INFORMATION"), texts(picture.get("reasons")));
        assertEquals(List.of("POOR_LIGHTING"), texts(picture.get("concernCategories")));
        assertFalse(picture.get("conflicting").asBoolean());
        assertEquals(AREA, picture.get("highestConcernCell").asText(), "an area cell, never the block");
        assertEquals(9, picture.get("areas").size());

        assertOnlyFields(picture, Set.of("modelVersion", "outlook", "concern", "information", "reasons",
                "concernCategories", "conflicting", "highestConcernCell", "areas"));
        for (JsonNode cell : picture.get("areas")) {
            assertOnlyFields(cell, Set.of("cell", "outlook", "information"));
            assertEquals(6, cell.get("cell").asText().length());
        }
        String pictureJson = picture.toString();
        assertFalse(pictureJson.contains(id), "no signal id in the picture");
        assertFalse(pictureJson.contains(CELL), "no block cell in the picture");
        for (String hidden : new String[]{"weight", "tier", "userId", "count", "supporters", "pattern", "time"}) {
            assertFalse(pictureJson.contains(hidden), "the picture must not contain " + hidden);
        }
    }

    @Test
    void anEmptyNeighbourhood_isNoInformation_neverSafe() throws Exception {
        JsonNode picture = api.area(api.account(), AREA).get("picture");
        assertEquals("NO_INFORMATION", picture.get("outlook").asText());
        assertEquals("NONE", picture.get("information").asText());
        assertTrue(picture.get("highestConcernCell").isNull());
        assertEquals(0, picture.get("reasons").size());
    }

    @Test
    void somethingStillInItsPublicationDelay_changesNothing() throws Exception {
        Account viewer = api.account();
        JsonNode before = api.area(viewer, AREA).get("picture");

        api.report(api.account(), "HARASSMENT", CELL).andExpect(status().isAccepted());
        assertEquals(before, api.area(viewer, AREA).get("picture"), "a pending report must not move the picture");

        clock.advance(AFTER_DELAY);
        JsonNode single = api.area(viewer, AREA).get("picture");
        assertEquals("LITTLE_INFORMATION", single.get("outlook").asText());

        // A pending CONFIRMATION does not move it either: no way to time when someone answered.
        String id = api.onlySignalId(viewer, AREA);
        api.contribute(api.account(), id, "CONFIRM", CELL).andExpect(status().isAccepted());
        api.contribute(api.account(), id, "CONFIRM", CELL).andExpect(status().isAccepted());
        assertEquals(single, api.area(viewer, AREA).get("picture"));

        clock.advance(AFTER_DELAY);
        assertEquals("TAKE_CARE", api.area(viewer, AREA).get("picture").get("outlook").asText(),
                "three standard people: MODERATE, an incident this hour");
    }

    @Test
    void aQuarantinedSignal_isAsIfItNeverExisted() throws Exception {
        String id = confirmedSignal("HARASSMENT", CELL);
        assertEquals("TAKE_CARE", picture().get("outlook").asText());

        api.flag(api.account(), id, "FALSE_INFORMATION", CELL).andExpect(status().isAccepted());
        api.flag(api.account(), id, "FALSE_INFORMATION", CELL).andExpect(status().isAccepted());

        JsonNode after = picture();
        assertEquals("NO_INFORMATION", after.get("outlook").asText(), "hidden for review = not evidence");
        assertEquals("NONE", after.get("information").asText());
    }

    @Test
    void aRetiredSignal_isExcluded() throws Exception {
        api.report(api.account(), "BROKEN_PATH", CELL);
        clock.advance(AFTER_DELAY);
        String id = api.onlySignalId(api.account(), AREA);
        api.contribute(api.account(), id, "NO_LONGER", CELL).andExpect(status().isAccepted());
        api.contribute(api.account(), id, "NO_LONGER", CELL).andExpect(status().isAccepted());
        clock.advance(AFTER_DELAY);
        assertEquals("NO_INFORMATION", picture().get("outlook").asText());
    }

    @Test
    void thePictureUsesTheFullVisibleSet_beforeThePhonesListIsCut() throws Exception {
        // Two single reports at the viewer's area centre fill the 2-signal list; the confirmed concern is farther.
        api.report(api.account(), "HELP_POINT", CELL);
        api.report(api.account(), "OPEN_LATE_STAFFED", CELL);
        double[] centre = GeoCell.centre(AREA);
        String north = GeoCell.encode(centre[0] + 0.006, centre[1], 6);
        String northBlock = north + "q";
        confirmedSignal("ISOLATED_STRETCH", northBlock);

        JsonNode answer = api.area(api.account(), AREA);
        assertEquals(2, answer.get("signals").size(), "the phone's list is cut");
        for (JsonNode s : answer.get("signals")) assertFalse(s.get("category").asText().equals("ISOLATED_STRETCH"));
        JsonNode picture = answer.get("picture");
        assertEquals("TAKE_CARE", picture.get("outlook").asText(), "but the picture still knows the concern");
        assertEquals(north, picture.get("highestConcernCell").asText());
    }

    @Test
    void aPausedViewer_stillSeesThePicture() throws Exception {
        confirmedSignal("POOR_LIGHTING", CELL);
        Account paused = api.account();
        ContributorStanding standing = new ContributorStanding(paused.id(), now());
        standing.restrictUntil(now().plusDays(7));
        standings.save(standing);
        assertEquals("TAKE_CARE", api.area(paused, AREA).get("picture").get("outlook").asText(),
                "safety information is never withheld from a paused contributor");
    }

    @Test
    void theAreaNeedsAToken() throws Exception {
        mockMvc.perform(get("/api/community/area").param("cell", AREA)).andExpect(status().isUnauthorized());
    }

    // ---- Helpers ---------------------------------------------------------------------------------------------------

    /** A signal reported by one person and confirmed by another, both counted (MODERATE, BLOCK). Returns its id. */
    private String confirmedSignal(String category, String cell) throws Exception {
        api.report(api.account(), category, cell).andExpect(status().isAccepted());
        clock.advance(AFTER_DELAY);
        String area = cell.substring(0, 6);
        String id = null;
        for (JsonNode s : api.area(api.account(), area).get("signals")) {
            if (s.get("category").asText().equals(category)) id = s.get("id").asText();
        }
        api.contribute(api.account(), id, "CONFIRM", cell).andExpect(status().isAccepted());
        clock.advance(AFTER_DELAY);
        return id;
    }

    private JsonNode picture() throws Exception {
        return api.area(api.account(), AREA).get("picture");
    }

    private static List<String> texts(JsonNode array) {
        List<String> list = new ArrayList<>();
        array.forEach(n -> list.add(n.asText()));
        return list;
    }

    private static void assertOnlyFields(JsonNode node, Set<String> allowed) {
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            assertTrue(allowed.contains(field), "unexpected field: " + field);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
