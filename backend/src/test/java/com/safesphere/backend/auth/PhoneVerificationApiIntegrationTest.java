package com.safesphere.backend.auth;

import static com.safesphere.backend.support.TestAccounts.ADULT_BIRTH_DATE;
import static com.safesphere.backend.support.TestAccounts.PASSWORD;
import static com.safesphere.backend.support.TestAccounts.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.auth.phone.PhoneVerificationService;
import com.safesphere.backend.support.CapturingOtpSenderConfig;
import com.safesphere.backend.support.CapturingOtpSenderConfig.CapturingOtpSender;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Phase 17B through the real API: sign-up → code → session, wrong / expired / locked codes, resend cooldown and caps,
 * an interrupted sign-up resumed by signing in, an account from before 17B adding a number, changing the number,
 * one verified account per number, a refusing SMS provider, the clean-up rule, and no code in the logs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({MutableClockConfig.class, CapturingOtpSenderConfig.class})
@ExtendWith(OutputCaptureExtension.class)
class PhoneVerificationApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;
    @Autowired private CapturingOtpSender sms;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PhoneVerificationService verification;

    @BeforeEach
    @AfterEach
    void reset() {
        clock.reset();
        sms.setFailing(false);
    }

    // ---------------------------------------------------------------- the happy path

    @Test
    void newAccount_theTextedCodeSignsIn_andTheAccountCanSignInAgainLater() throws Exception {
        String email = email();
        String phone = number();
        JsonNode challenge = register(email, phone).andExpect(status().isCreated()).json();
        assertThat(challenge.get("phoneHint").asText()).endsWith(phone.substring(6)).doesNotContain(phone);

        JsonNode session = verify(challenge, sms.lastCodeFor("+91" + phone)).andExpect(status().isOk()).json();
        String token = session.get("accessToken").asText();
        assertThat(session.get("refreshToken").asText()).isNotBlank();
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ageConfirmed").value(true));
        assertThat(users.findByEmail(email).orElseThrow().isPhoneVerified()).isTrue();

        login(email, PASSWORD).andExpect(status().isOk());
        start(email, PASSWORD, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHONE_ALREADY_VERIFIED"));
        // The used challenge is gone.
        verify(challenge, sms.lastCodeFor("+91" + phone)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHALLENGE_INVALID"));
    }

    @Test
    void numbersAreAcceptedAsPeopleTypeThem() throws Exception {
        String phone = number();
        JsonNode challenge = register(email(), "+91 " + phone.substring(0, 5) + "-" + phone.substring(5))
                .andExpect(status().isCreated()).json();
        verify(challenge, sms.lastCodeFor("+91" + phone)).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- wrong, expired, locked

    @Test
    void wrongCodes_sayHowManyTriesAreLeft_thenTheCodeIsDead_andANewOneWorks() throws Exception {
        String phone = number();
        JsonNode challenge = register(email(), phone).json();
        String right = sms.lastCodeFor("+91" + phone);
        String wrong = right.equals("000000") ? "111111" : "000000";

        for (int left = 4; left >= 1; left--) {
            verify(challenge, wrong).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("OTP_INVALID"))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(String.valueOf(left))));
        }
        verify(challenge, wrong).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("OTP_LOCKED"));
        verify(challenge, right).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("OTP_LOCKED"));

        clock.advance(Duration.ofSeconds(61));
        resend(challenge).andExpect(status().isOk()).andExpect(jsonPath("$.codeSent").value(true));
        verify(challenge, sms.lastCodeFor("+91" + phone)).andExpect(status().isOk());
    }

    @Test
    void anExpiredCode_isRefused_andResendGivesAWorkingOne() throws Exception {
        String phone = number();
        JsonNode challenge = register(email(), phone).json();
        String old = sms.lastCodeFor("+91" + phone);

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        verify(challenge, old).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OTP_EXPIRED"));

        resend(challenge).andExpect(status().isOk());
        verify(challenge, sms.lastCodeFor("+91" + phone)).andExpect(status().isOk());
    }

    @Test
    void resend_waitsForTheCooldown_replacesTheOldCode_andIsCapped() throws Exception {
        String phone = number();
        JsonNode challenge = register(email(), phone).json();
        assertThat(challenge.get("resendInSeconds").asLong()).isBetween(55L, 60L);
        String first = sms.lastCodeFor("+91" + phone);

        resend(challenge).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("OTP_WAIT"));
        for (int i = 2; i <= 5; i++) {
            clock.advance(Duration.ofSeconds(61));
            resend(challenge).andExpect(status().isOk());
        }
        assertThat(sms.countFor("+91" + phone)).isEqualTo(5);
        clock.advance(Duration.ofSeconds(61));
        resend(challenge).andExpect(status().isTooManyRequests());
        assertThat(sms.countFor("+91" + phone)).isEqualTo(5);

        String latest = sms.lastCodeFor("+91" + phone);
        if (!latest.equals(first)) {
            verify(challenge, first).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OTP_INVALID"));
        }
        verify(challenge, latest).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- interrupted sign-up, older accounts

    @Test
    void anInterruptedSignUp_resumesBySigningIn_withoutASecondText() throws Exception {
        String email = email();
        String phone = number();
        JsonNode lost = register(email, phone).json(); // the app was closed: this token is gone
        String code = sms.lastCodeFor("+91" + phone);

        login(email, PASSWORD).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PHONE_NOT_VERIFIED"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        start(email, "wrong-password", null).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
        JsonNode resumed = start(email, PASSWORD, null).andExpect(status().isOk()).json();
        assertThat(resumed.get("codeSent").asBoolean()).isTrue();
        assertThat(sms.countFor("+91" + phone)).isEqualTo(1); // the code already sent still works

        verify(lost, code).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CHALLENGE_INVALID"));
        verify(resumed, code).andExpect(status().isOk());
    }

    @Test
    void anAccountFromBefore17B_keepsItsSession_andAddsANumberAtItsNextSignIn() throws Exception {
        String email = email();
        String token = com.safesphere.backend.support.TestAccounts.registerAndGetToken(mockMvc, objectMapper, email);
        // As V25 left it: activated by e-mail, no number.
        jdbc.update("UPDATE users SET phone_verified_at = NULL, phone_e164 = NULL, email_verified_at = created_at "
                + "WHERE email = ?", email);

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk());
        login(email, PASSWORD).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PHONE_NOT_VERIFIED"));

        JsonNode challenge = start(email, PASSWORD, null).andExpect(status().isOk()).json();
        assertThat(challenge.get("needsPhone").asBoolean()).isTrue();
        assertThat(challenge.get("codeSent").asBoolean()).isFalse();
        verify(challenge, "123456").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PHONE_REQUIRED"));

        String phone = number();
        change(challenge, phone).andExpect(status().isOk()).andExpect(jsonPath("$.needsPhone").value(false));
        verify(challenge, sms.lastCodeFor("+91" + phone)).andExpect(status().isOk());
        login(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void changingTheNumber_sendsToTheNewOne_andTheOldCodeStopsWorking() throws Exception {
        String first = number();
        String second = number();
        JsonNode challenge = register(email(), first).json();
        String oldCode = sms.lastCodeFor("+91" + first);

        change(challenge, second).andExpect(status().isOk());
        verify(challenge, oldCode).andExpect(status().isBadRequest());
        change(challenge, "12345").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PHONE_INVALID"));
        verify(challenge, sms.lastCodeFor("+91" + second)).andExpect(status().isOk());
    }

    @Test
    void oneVerifiedAccountPerNumber() throws Exception {
        String phone = number();
        verify(register(email(), phone).json(), sms.lastCodeFor("+91" + phone)).andExpect(status().isOk());

        JsonNode second = register(email(), phone).andExpect(status().isCreated()).json();
        verify(second, sms.lastCodeFor("+91" + phone)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHONE_IN_USE"));
        String other = number();
        change(second, other).andExpect(status().isOk());
        verify(second, sms.lastCodeFor("+91" + other)).andExpect(status().isOk());
    }

    @Test
    void aRefusingSmsProvider_doesNotCount_andAllowsAnImmediateRetry() throws Exception {
        String phone = number();
        sms.setFailing(true);
        JsonNode challenge = register(email(), phone).andExpect(status().isCreated()).json();
        assertThat(challenge.get("codeSent").asBoolean()).isFalse();
        assertThat(challenge.get("resendInSeconds").asLong()).isZero();

        sms.setFailing(false);
        resend(challenge).andExpect(status().isOk()).andExpect(jsonPath("$.codeSent").value(true));
        verify(challenge, sms.lastCodeFor("+91" + phone)).andExpect(status().isOk());
    }

    @Test
    void aNonsenseChallenge_isTheSameGeneric400() throws Exception {
        post("/api/auth/phone/verify", Map.of("challenge", "not-a-token", "code", "123456"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CHALLENGE_INVALID"));
    }

    // ---------------------------------------------------------------- sign-up again, clean-up

    @Test
    void anAddressIsOnlyFreeAgainOnceTheUnfinishedSignUpHasEnded() throws Exception {
        String email = email();
        register(email, number()).andExpect(status().isCreated());
        register(email, number()).andExpect(status().isConflict());

        clock.advance(Duration.ofMinutes(31));
        String phone = number();
        JsonNode again = register(email, phone).andExpect(status().isCreated()).json();
        verify(again, sms.lastCodeFor("+91" + phone)).andExpect(status().isOk());
    }

    @Test
    void cleanUp_removesAccountsNeverActivated_butNeverAPhoneVerifiedOne() throws Exception {
        String waiting = email();
        register(waiting, number());
        String done = email();
        String phone = number();
        verify(register(done, phone).json(), sms.lastCodeFor("+91" + phone)).andExpect(status().isOk());

        clock.advance(Duration.ofDays(8));
        verification.cleanUp();

        assertThat(users.findByEmail(waiting)).isEmpty();
        assertThat(users.findByEmail(done)).isPresent();
    }

    @Test
    void codesNeverReachTheLogs(CapturedOutput output) throws Exception {
        String phone = number();
        JsonNode challenge = register(email(), phone).json();
        String code = sms.lastCodeFor("+91" + phone);
        verify(challenge, code.equals("000000") ? "111111" : "000000");
        verify(challenge, code).andExpect(status().isOk());
        assertThat(output.getAll()).doesNotContain(code).doesNotContain(phone)
                .doesNotContain(challenge.get("challenge").asText());
    }

    // ---------------------------------------------------------------- helpers

    private record Call(ResultActions actions, ObjectMapper mapper) {
        Call andExpect(org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
            actions.andExpect(matcher);
            return this;
        }

        JsonNode json() throws Exception {
            return mapper.readTree(actions.andReturn().getResponse().getContentAsString());
        }
    }

    private Call register(String email, String phone) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("name", "Phone Test", "email", email, "password", PASSWORD,
                "dateOfBirth", ADULT_BIRTH_DATE, "phone", phone));
        return post("/api/auth/register", body);
    }

    private Call login(String email, String password) throws Exception {
        return post("/api/auth/login", Map.of("email", email, "password", password));
    }

    private Call start(String email, String password, String phone) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("email", email, "password", password));
        if (phone != null) body.put("phone", phone);
        return post("/api/auth/phone/start", body);
    }

    private Call verify(JsonNode challenge, String code) throws Exception {
        return post("/api/auth/phone/verify", Map.of("challenge", challenge.get("challenge").asText(), "code", code));
    }

    private Call resend(JsonNode challenge) throws Exception {
        return post("/api/auth/phone/resend", Map.of("challenge", challenge.get("challenge").asText()));
    }

    private Call change(JsonNode challenge, String phone) throws Exception {
        return post("/api/auth/phone/change", Map.of("challenge", challenge.get("challenge").asText(), "phone", phone));
    }

    private Call post(String path, Map<String, ?> body) throws Exception {
        return new Call(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))), objectMapper);
    }

    private static String email() {
        return "phone17b." + UUID.randomUUID() + "@example.com";
    }

    /** A fresh 10-digit Indian mobile number (starts with 9). */
    private static String number() {
        long n = Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000L);
        return "9" + String.format("%09d", n);
    }
}
