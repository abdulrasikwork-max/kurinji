package com.safesphere.backend.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.TestAccounts;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Phase 7 sign-in / sign-up abuse limits through the real endpoints, with small limits so the tests stay short.
 * Every request carries its own network address (MockMvc remoteAddr), like real clients on different networks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.auth.login-failures-per-account=3",
        "app.auth.login-failures-per-address=5",
        "app.auth.registrations-per-address=2"
})
class AuthThrottleApiIntegrationTest {

    private static final String TOO_MANY_SIGN_INS = "Too many sign-in attempts. Please wait a few minutes and try again.";
    private static final AtomicInteger ADDRESSES = new AtomicInteger(1);

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock authTestClock() {
            return new MutableClock();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void wrongPasswordsForOneAccount_pauseThatAccount_evenForTheRightPassword_untilTheWindowEnds() throws Exception {
        String email = register();
        for (int i = 0; i < 3; i++) login(email, "wrong-password-" + i, newAddress()).andExpect(status().isUnauthorized());

        login(email, TestAccounts.PASSWORD, newAddress()).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(TOO_MANY_SIGN_INS));
        login(register(), TestAccounts.PASSWORD, newAddress()).andExpect(status().isOk()); // other people unaffected

        clock.advance(Duration.ofMinutes(15));
        login(email, TestAccounts.PASSWORD, newAddress()).andExpect(status().isOk());
    }

    @Test
    void unknownEmail_isLimitedExactlyLikeAKnownOne_soTheLimitRevealsNothing() throws Exception {
        String unknown = "nobody." + UUID.randomUUID() + "@example.com";
        for (int i = 0; i < 3; i++) login(unknown, "whatever-password", newAddress()).andExpect(status().isUnauthorized());
        login(unknown, "whatever-password", newAddress()).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(TOO_MANY_SIGN_INS));
    }

    @Test
    void aSuccessfulSignIn_clearsTheAccountCounter() throws Exception {
        String email = register();
        login(email, "wrong-1", newAddress()).andExpect(status().isUnauthorized());
        login(email, "wrong-2", newAddress()).andExpect(status().isUnauthorized());
        login(email, TestAccounts.PASSWORD, newAddress()).andExpect(status().isOk());
        login(email, "wrong-3", newAddress()).andExpect(status().isUnauthorized());
        login(email, "wrong-4", newAddress()).andExpect(status().isUnauthorized());
        login(email, TestAccounts.PASSWORD, newAddress()).andExpect(status().isOk());
    }

    @Test
    void oneAddressTryingManyAccounts_isStopped_otherAddressesAreNot() throws Exception {
        String stuffer = newAddress();
        for (int i = 0; i < 5; i++) {
            login("victim" + i + "." + UUID.randomUUID() + "@example.com", "guess", stuffer).andExpect(status().isUnauthorized());
        }
        String someone = register();
        login(someone, TestAccounts.PASSWORD, stuffer).andExpect(status().isTooManyRequests());
        login(someone, TestAccounts.PASSWORD, newAddress()).andExpect(status().isOk());
    }

    @Test
    void accountFarmingFromOneAddress_isLimited() throws Exception {
        String farm = newAddress();
        registerFrom(farm).andExpect(status().isCreated());
        registerFrom(farm).andExpect(status().isCreated());
        registerFrom(farm).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many new accounts from this network. Please try again later."));
        registerFrom(newAddress()).andExpect(status().isCreated());
        clock.advance(Duration.ofHours(1));
        registerFrom(farm).andExpect(status().isCreated());
    }

    // ---- Helpers ---------------------------------------------------------------------------------------------------

    private static String newAddress() {
        int n = ADDRESSES.getAndIncrement();
        return "10." + (n / 65536 % 256) + "." + (n / 256 % 256) + "." + (n % 256);
    }

    private String register() throws Exception {
        String email = "throttle." + UUID.randomUUID() + "@example.com";
        registerFrom(newAddress(), email).andExpect(status().isCreated());
        return email;
    }

    private ResultActions registerFrom(String address) throws Exception {
        return registerFrom(address, "farm." + UUID.randomUUID() + "@example.com");
    }

    private ResultActions registerFrom(String address, String email) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("name", "Test User", "email", email,
                "password", TestAccounts.PASSWORD));
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                }));
    }

    private ResultActions login(String email, String password, String address) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("email", email, "password", password));
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                }));
    }
}
