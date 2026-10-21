package com.safesphere.backend.community;

import static com.safesphere.backend.support.TestAccounts.bearer;
import static com.safesphere.backend.support.TestAccounts.registerAndGetToken;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.community.entity.SignalContribution;
import com.safesphere.backend.community.repository.PlaceSignalRepository;
import com.safesphere.backend.community.repository.SignalContributionRepository;
import com.safesphere.backend.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The reporter-safety publication delay (10-30 min) and the new-account probation (24 h), with the real defaults. */
@SpringBootTest(properties = {
        "app.community.publish-delay-min=10m",
        "app.community.publish-delay-max=30m",
        "app.community.new-account-probation=24h"
})
@AutoConfigureMockMvc
class CommunityDelayApiIntegrationTest {

    private static final String CELL = CommunityApiIntegrationTest.CELL;
    private static final String AREA = CommunityApiIntegrationTest.AREA;

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;
    @Autowired private PlaceSignalRepository signalRepository;
    @Autowired private SignalContributionRepository contributionRepository;

    @BeforeEach
    void clean() {
        clock.reset();
        contributionRepository.deleteAll();
        signalRepository.deleteAll();
    }

    @Test
    void newAccountsReport_countsOnlyAfterProbationPlusARandomDelay_andTheRealTimeIsNotStored() throws Exception {
        String alice = account();
        LocalDateTime before = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        mockMvc.perform(post("/api/community/reports").header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("category", "HARASSMENT", "cell", CELL))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.appearsWithinMinutes").value(24 * 60 + 30));

        SignalContribution stored = contributionRepository.findAll().get(0);
        Duration offset = Duration.between(before, stored.getCountsFrom());
        // 24 h probation + 10..30 min: the stored time is NOT the moment of the report.
        assertBetween(offset, Duration.ofHours(24).plusMinutes(10), Duration.ofHours(24).plusMinutes(31));

        String bob = account();
        assertEquals(0, area(bob).size(), "not visible right away");
        mockMvc.perform(get("/api/community/mine").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(jsonPath("$[0].status").value("PENDING"));

        clock.advance(Duration.ofMinutes(31));
        assertEquals(0, area(bob).size(), "still in the new-account probation");

        clock.advance(Duration.ofHours(24));
        assertEquals(1, area(bob).size());
        mockMvc.perform(get("/api/community/mine").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(jsonPath("$[0].status").value("LIVE"));
    }

    @Test
    void mergingIntoAPendingSignal_answersExactlyLikeANewReport() throws Exception {
        String alice = account();
        String bob = account();
        String a = mockMvc.perform(post("/api/community/reports").header(HttpHeaders.AUTHORIZATION, bearer(alice))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("category", "FOLLOWING", "cell", CELL))))
                .andReturn().getResponse().getContentAsString();
        String b = mockMvc.perform(post("/api/community/reports").header(HttpHeaders.AUTHORIZATION, bearer(bob))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("category", "FOLLOWING", "cell", CELL))))
                .andReturn().getResponse().getContentAsString();
        assertEquals(a, b);
    }

    private static void assertBetween(Duration value, Duration min, Duration max) {
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new AssertionError(value + " not in [" + min + ", " + max + "]");
        }
    }

    private String account() throws Exception {
        return registerAndGetToken(mockMvc, objectMapper, "delay." + UUID.randomUUID() + "@example.com");
    }

    private JsonNode area(String token) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/community/area").param("cell", AREA)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("signals");
    }
}
