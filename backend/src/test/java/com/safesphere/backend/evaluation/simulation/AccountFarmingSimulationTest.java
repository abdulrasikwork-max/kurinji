package com.safesphere.backend.evaluation.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.evaluation.simulation.AttackReport.Row;
import com.safesphere.backend.support.CommunityClient;
import com.safesphere.backend.support.CommunityClient.Account;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.TestAccounts;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 9A account farming with the REAL sign-up limit (10 new accounts per network address per hour) and the real
 * 24 h probation and publication delay. Network addresses are set per request (TEST-NET-3 documentation addresses,
 * RFC 5737). Results go to target/attack-simulation/account-farming.md (SIMULATED).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
@TestPropertySource(properties = {
        "app.auth.registrations-per-address=10",
        "app.auth.registration-window=1h",
        "app.community.publish-delay-min=10m",
        "app.community.publish-delay-max=30m",
        "app.community.new-account-probation=24h"})
class AccountFarmingSimulationTest {

    static final String SECTION = "account-farming";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;

    @AfterAll
    static void writeReport() throws IOException {
        AttackReport.write(SECTION, "Phase 9A: account farming");
    }

    @Test
    void newAccountsAreLimitedPerNetworkAddressOnly_andCountOnlyAfterTheirProbation() throws Exception {
        clock.reset();
        Instant start = clock.instant();
        CommunityClient api = new CommunityClient(mockMvc, objectMapper);
        String place = AttackHarness.block(AttackHarness.area(40, 0), 0);

        // Two farmed accounts try to confirm a fake straight away.
        Account first = register("203.0.113.7");
        Account second = register("203.0.113.7");
        JsonNode accepted = api.json(api.report(first, "POOR_LIGHTING", place));
        assertTrue(accepted.get("appearsWithinMinutes").asInt() >= 24 * 60, "the phone is told: about a day");
        clock.advance(AttackHarness.SETTLE);
        Account viewer = register("203.0.113.9");
        assertEquals(0, api.area(viewer, place.substring(0, 6)).get("signals").size(), "nothing for a day");
        clock.advance(Duration.ofHours(24));
        JsonNode seen = api.area(viewer, place.substring(0, 6)).get("signals");
        assertEquals(1, seen.size(), "after the probation the report counts");
        String id = seen.get(0).get("id").asText();
        assertEquals(202, api.contribute(second, id, "CONFIRM", place).andReturn().getResponse().getStatus());
        clock.advance(AttackHarness.SETTLE);
        assertEquals("MODERATE", api.area(viewer, place.substring(0, 6)).get("signals").get(0).get("confidence").asText());
        Duration farmed = Duration.between(start, clock.instant());

        // The sign-up limit: per address only.
        List<Integer> sameAddress = new ArrayList<>();
        for (int i = 0; i < 11; i++) sameAddress.add(status(registerRaw("203.0.113.20")));
        assertEquals(List.of(201, 201, 201, 201, 201, 201, 201, 201, 201, 201, 429), sameAddress);
        int otherAddress = 0;
        for (int i = 0; i < 10; i++) if (status(registerRaw("203.0.113.21")) == 201) otherAddress++;
        assertEquals(10, otherAddress, "a second address has its own budget");
        clock.advance(Duration.ofHours(1).plusMinutes(1));
        assertEquals(201, status(registerRaw("203.0.113.20")), "and the first one again after an hour");

        AttackReport.add(SECTION, new Row("F1 farmed pair, fresh accounts", "How soon can new accounts confirm a "
                + "fake?", "2 new accounts, 2 writes", farmed.toHours() + " h " + farmed.toMinutesPart() + " min",
                "The report counts only after the 24 h "
                + "probation; the confirmation 10-30 min later", "Nothing for about a day, then a MODERATE signal"));
        AttackReport.add(SECTION, new Row("F2 sign-up limit", "How many accounts can one network address make?",
                "21 sign-ups from 2 addresses", "1 h", "10 per address per hour, the 11th refused (429); a second "
                + "address gets its own 10; the first again after an hour",
                "Nothing. Derived: up to 240 a day per address; the limit only costs addresses (mobile networks, "
                        + "proxies), it cannot stop a farm"));
    }

    /** Phase 17B: the farm also has to verify a phone per account (done directly here), then sign in. */
    private Account register(String address) throws Exception {
        String email = "phase9.farm." + UUID.randomUUID() + "@example.com";
        assertEquals(201, status(registerRaw(address, email)));
        TestAccounts.markVerified(mockMvc, email);
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", TestAccounts.PASSWORD))))
                .andReturn();
        assertEquals(200, status(login));
        JsonNode body = objectMapper.readTree(login.getResponse().getContentAsString());
        return new Account(body.get("accessToken").asText(), body.get("user").get("id").asLong(),
                body.get("user").get("email").asText());
    }

    private MvcResult registerRaw(String address) throws Exception {
        return registerRaw(address, "phase9.farm." + UUID.randomUUID() + "@example.com");
    }

    private MvcResult registerRaw(String address, String email) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("name", "Phase Nine Farm",
                "email", email, "password", TestAccounts.PASSWORD,
                "dateOfBirth", TestAccounts.ADULT_BIRTH_DATE, "phone", TestAccounts.phoneFor(email)));
        return mockMvc.perform(post("/api/auth/register").with(request -> {
            request.setRemoteAddr(address);
            return request;
        }).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }
}
