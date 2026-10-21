package com.safesphere.backend.community;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.support.CommunityClient;
import com.safesphere.backend.support.CommunityClient.Account;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Phase 7: scripted bursts of community writes are refused (real limit lowered to 3 per 10 minutes here). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
@TestPropertySource(properties = "app.community.writes-per-10-minutes=3")
class CommunityBurstApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;

    @Test
    void aBurstOfWrites_isRefused_untilTenMinutesPass_butResendsAreFree() throws Exception {
        clock.reset();
        CommunityClient api = new CommunityClient(mockMvc, objectMapper);
        Account bot = api.account();
        for (int i = 0; i < 3; i++) {
            api.report(bot, "HELP_POINT", GeoCell.encode(15.0 + i * 0.05, 75.0, 7)).andExpect(status().isAccepted());
        }
        String first = GeoCell.encode(15.0, 75.0, 7);
        api.report(bot, "HELP_POINT", first).andExpect(status().isAccepted()); // an outbox re-send costs nothing
        api.report(bot, "HELP_POINT", GeoCell.encode(16.0, 75.0, 7)).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("You are sending a lot very quickly. Please wait a few minutes."));
        clock.advance(Duration.ofMinutes(10));
        api.report(bot, "HELP_POINT", GeoCell.encode(16.0, 75.0, 7)).andExpect(status().isAccepted());
    }
}
