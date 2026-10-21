package com.safesphere.backend.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.community.entity.SignalCategory;
import com.safesphere.backend.community.repository.PlaceSignalRepository;
import com.safesphere.backend.community.repository.SignalContributionRepository;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.config.IntelligenceProperties;
import com.safesphere.backend.config.IntelligenceProperties.Rules2Mode;
import com.safesphere.backend.intelligence.service.AreaModelShadow;
import com.safesphere.backend.support.CommunityClient;
import com.safesphere.backend.support.CommunityClient.Account;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 9D-2: what promoting rules-2 would do (app.intelligence.rules-2-mode=ACTIVE). Shipped is SHADOW; this only
 * proves the switch works end to end (pictures and region packs, version string) and that the shadow then stays idle.
 * Turning it on in a real deployment is the user's decision (docs/PHASE9.md §19).
 */
@SpringBootTest(properties = "app.intelligence.rules-2-mode=ACTIVE")
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class RulesTwoActiveApiIntegrationTest {

    private static final String AREA = GeoCell.encode(-25.5, -144.5, GeoCell.AREA_PRECISION);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;
    @Autowired private PlaceSignalRepository signals;
    @Autowired private SignalContributionRepository contributions;
    @Autowired private AreaModelShadow shadow;

    private CommunityClient api;

    @BeforeEach
    void clean() {
        clock.reset();
        contributions.deleteAll();
        signals.deleteAll();
        api = new CommunityClient(mockMvc, objectMapper);
    }

    private void confirmed(Account a, Account b, String category, String cell) throws Exception {
        api.report(a, category, cell).andExpect(status().isAccepted());
        String id = signals.findByCellAndCategory(cell, SignalCategory.valueOf(category)).orElseThrow().getPublicId();
        api.contribute(b, id, "CONFIRM", cell).andExpect(status().isAccepted());
    }

    @Test
    void active_twoPeopleReachTakeCare_aThirdReachesExtraCare_andThePackSaysRulesTwo() throws Exception {
        Account a = api.account();
        Account b = api.account();
        confirmed(a, b, "POOR_LIGHTING", AREA + "0");
        confirmed(b, a, "ISOLATED_STRETCH", AREA + "y");
        JsonNode picture = api.area(api.account(), AREA).get("picture");
        assertEquals("rules-2", picture.get("modelVersion").asText());
        assertEquals("TAKE_CARE", picture.get("outlook").asText(), "the pair alone: a warning, not extra care");

        confirmed(api.account(), api.account(), "BROKEN_PATH", AREA + "d");
        assertEquals("TAKE_EXTRA_CARE", api.area(api.account(), AREA).get("picture").get("outlook").asText());

        JsonNode pack = api.json(api.getAs(api.account(), "/api/intelligence/region?cell=" + AREA.substring(0, 5))
                .andExpect(status().isOk()));
        assertEquals("rules-2", pack.get("modelVersion").asText());
        assertEquals(0, shadow.run(LocalDateTime.now()).areas(), "no shadow while rules-2 is the active model");
    }

    @Test
    void offAndActive_neverRunTheShadow() {
        for (Rules2Mode mode : new Rules2Mode[] {Rules2Mode.OFF, Rules2Mode.ACTIVE}) {
            IntelligenceProperties properties = new IntelligenceProperties(null, null, null, null, null, null, null,
                    null, null, null, mode, null);
            // No collaborators: a run in these modes must not even read.
            assertEquals(0, new AreaModelShadow(null, null, properties).run(LocalDateTime.now()).areas());
        }
    }
}
