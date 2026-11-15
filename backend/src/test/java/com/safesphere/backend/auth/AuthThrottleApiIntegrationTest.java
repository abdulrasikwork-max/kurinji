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
 * Sign-in / sign-up abuse limits through the real endpoints, with small limits so the tests stay short. Every request
 * carries its own network address (MockMvc remoteAddr), like real clients on different networks.
 * Phase N2: the password limits are gone with the password; what is left is new accounts per address (Phase 7), wrong
 * codes per address, and codes per number per day (Phase 17B, anti SMS bombing).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.auth.registrations-per-address=2",
        "app.otp.verify-failures-per-address=3",
        "app.otp.sends-per-number-per-day=2",
        "app.otp.resend-cooldown=1s"
})
class AuthThrottleApiIntegrationTest {

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
    void accountFarmingFromOneAddress_isLimited() throws Exception {
        String farm = newAddress();
        signUpFrom(farm).andExpect(status().isCreated());
        signUpFrom(farm).andExpect(status().isCreated());
        signUpFrom(farm).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many new accounts from this network. Please try again later."));
        signUpFrom(newAddress()).andExpect(status().isCreated());
        clock.advance(Duration.ofHours(1));
        signUpFrom(farm).andExpect(status().isCreated());
    }

    @Test
    void guessingCodesFromOneAddress_isStopped_otherAddressesAreNot() throws Exception {
        String guesser = newAddress();
        for (int i = 0; i < 3; i++) {
            verifyFrom(guesser).andExpect(status().isBadRequest());
        }
        verifyFrom(guesser).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("OTP_WAIT"));
        verifyFrom(newAddress()).andExpect(status().isBadRequest());
    }

    @Test
    void oneNumber_getsALimitedNumberOfTextsPerDay_whoeverAsks() throws Exception {
        String phone = "9" + String.format("%09d", Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000L));
        startFrom(newAddress(), phone).andExpect(status().isOk());
        // A new code is made only when the old one is dead: make it expire, then ask again from elsewhere.
        clock.advance(Duration.ofMinutes(31));
        startFrom(newAddress(), phone).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(31));
        startFrom(newAddress(), phone).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("OTP_WAIT"));
        clock.advance(Duration.ofDays(1));
        startFrom(newAddress(), phone).andExpect(status().isOk());
    }

    // ---- Helpers ---------------------------------------------------------------------------------------------------

    private static String newAddress() {
        int n = ADDRESSES.getAndIncrement();
        return "10." + (n / 65536 % 256) + "." + (n / 256 % 256) + "." + (n % 256);
    }

    private ResultActions signUpFrom(String address) throws Exception {
        String ticket = TestAccounts.seedTicket(mockMvc, TestAccounts.e164For("farm." + UUID.randomUUID()));
        String body = objectMapper.writeValueAsString(Map.of("ticket", ticket, "name", "Test User",
                "dateOfBirth", TestAccounts.ADULT_BIRTH_DATE));
        return send("/api/auth/sign-up", body, address);
    }

    private ResultActions verifyFrom(String address) throws Exception {
        String challenge = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 43);
        return send("/api/auth/phone/verify",
                objectMapper.writeValueAsString(Map.of("challenge", challenge, "code", "123456")), address);
    }

    private ResultActions startFrom(String address, String phone) throws Exception {
        return send("/api/auth/phone/start", objectMapper.writeValueAsString(Map.of("phone", phone)), address);
    }

    private ResultActions send(String path, String body, String address) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                }));
    }
}
