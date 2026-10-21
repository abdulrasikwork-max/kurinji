package com.safesphere.backend.intelligence;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.support.CommunityClient;
import com.safesphere.backend.support.CommunityClient.Account;
import com.safesphere.backend.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

/** Phase 8D: an account still in its 24 h community probation may download fewer distinct regions (2, not 6). */
@SpringBootTest(properties = "app.community.new-account-probation=24h")
@AutoConfigureMockMvc
class RegionPackNewAccountApiIntegrationTest {

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock newAccountRegionClock() {
            return new MutableClock();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;

    @Test
    void aBrandNewAccount_getsASmallerRegionBudget_untilItsProbationEnds() throws Exception {
        clock.reset();
        CommunityClient api = new CommunityClient(mockMvc, objectMapper);
        Account fresh = api.account();
        for (int i = 0; i < 2; i++) {
            api.getAs(fresh, "/api/intelligence/region?cell=" + GeoCell.encode(12.0 + i * 0.1, 77.0, 5))
                    .andExpect(status().isOk());
        }
        api.getAs(fresh, "/api/intelligence/region?cell=" + GeoCell.encode(13.0, 77.0, 5))
                .andExpect(status().isTooManyRequests());

        clock.advance(Duration.ofHours(25)); // the probation and the budget window are over
        for (int i = 0; i < 6; i++) {
            api.getAs(fresh, "/api/intelligence/region?cell=" + GeoCell.encode(12.0 + i * 0.1, 77.0, 5))
                    .andExpect(status().isOk());
        }
    }
}
