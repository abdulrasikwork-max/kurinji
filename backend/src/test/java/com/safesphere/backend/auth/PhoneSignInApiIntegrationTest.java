package com.safesphere.backend.auth;

import static com.safesphere.backend.support.TestAccounts.ADULT_BIRTH_DATE;
import static com.safesphere.backend.support.TestAccounts.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.auth.phone.PhoneCode;
import com.safesphere.backend.auth.phone.PhoneCodeRepository;
import com.safesphere.backend.auth.phone.PhoneCodeService;
import com.safesphere.backend.support.CapturingOtpSenderConfig;
import com.safesphere.backend.support.CapturingOtpSenderConfig.CapturingOtpSender;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * Phase N2 through the real API: the phone number and a texted code are the only way in. A new number gets a sign-up
 * ticket (then a real name and an adult date of birth make the account), a known number signs in; the start answer is
 * the same for both; wrong / expired / locked codes, resend cooldown and caps, a number typo, a refusing SMS provider,
 * the one-use ticket, the 18+ and name rules, one account per number, switched-off accounts, accounts left without a
 * verified number (N-22), the clean-up, and no code, number or ticket in the logs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({MutableClockConfig.class, CapturingOtpSenderConfig.class})
@ExtendWith(OutputCaptureExtension.class)
class PhoneSignInApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;
    @Autowired private CapturingOtpSender sms;
    @Autowired private UserRepository users;
    @Autowired private PhoneCodeRepository codes;
    @Autowired private PhoneCodeService phoneCodes;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void reset() {
        clock.reset();
        sms.setFailing(false);
    }

    // ---------------------------------------------------------------- the happy paths

    @Test
    void aNewNumber_getsATicket_signUpMakesTheAccount_andTheNumberSignsInAgainLater() throws Exception {
        String phone = number();
        JsonNode challenge = start(phone).andExpect(status().isOk()).json();
        assertThat(challenge.get("phoneHint").asText()).endsWith(phone.substring(6)).doesNotContain(phone);
        assertThat(challenge.get("codeSent").asBoolean()).isTrue();

        JsonNode verified = verify(challenge, code(phone)).andExpect(status().isOk()).json();
        assertThat(verified.get("status").asText()).isEqualTo("SIGN_UP_NEEDED");
        assertThat(verified.get("session").isNull()).isTrue();
        String ticket = verified.get("signUp").get("ticket").asText();
        assertThat(verified.get("signUp").get("expiresInSeconds").asLong()).isEqualTo(1800);

        JsonNode session = signUp(ticket, "  Kavya   Raman ", ADULT_BIRTH_DATE).andExpect(status().isCreated()).json();
        String token = session.get("accessToken").asText();
        assertThat(session.get("refreshToken").asText()).isNotBlank();
        assertThat(session.get("user").get("name").asText()).isEqualTo("Kavya Raman");
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Kavya Raman"))
                .andExpect(jsonPath("$.ageConfirmed").value(true));
        User user = users.findByPhoneE164AndPhoneVerifiedAtIsNotNull("+91" + phone).orElseThrow();
        assertThat(user.isPhoneVerified()).isTrue();
        assertThat(user.isAgeConfirmed()).isTrue();

        clock.advance(Duration.ofSeconds(61));
        JsonNode again = start(phone).andExpect(status().isOk()).json();
        JsonNode signedIn = verify(again, code(phone)).andExpect(status().isOk()).json();
        assertThat(signedIn.get("status").asText()).isEqualTo("SIGNED_IN");
        assertThat(signedIn.get("signUp").isNull()).isTrue();
        assertThat(signedIn.get("session").get("user").get("id").asLong()).isEqualTo(user.getId());
        assertThat(signedIn.get("session").get("refreshToken").asText()).isNotBlank();
    }

    @Test
    void startAnswersTheSame_forANumberWithAndWithoutAnAccount() throws Exception {
        String known = number();
        makeAccount(known);
        long knownBefore = sms.countFor("+91" + known);
        String unknown = number();

        JsonNode a = start(known).andExpect(status().isOk()).json();
        JsonNode b = start(unknown).andExpect(status().isOk()).json();
        assertThat(fieldNames(a)).isEqualTo(fieldNames(b));
        for (String field : new String[] {"codeSent", "resendInSeconds", "codeExpiresInSeconds"}) {
            assertThat(a.get(field)).as(field).isEqualTo(b.get(field));
        }
        assertThat(sms.countFor("+91" + known)).isEqualTo(knownBefore + 1);
        assertThat(sms.countFor("+91" + unknown)).isEqualTo(1);
    }

    @Test
    void numbersAreAcceptedAsPeopleTypeThem_andNonsenseIsRefused() throws Exception {
        String phone = number();
        start("+91 " + phone.substring(0, 5) + " " + phone.substring(5)).andExpect(status().isOk());
        assertThat(sms.countFor("+91" + phone)).isEqualTo(1);
        start("12345").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PHONE_INVALID"));
        start("").andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- wrong, expired, locked, resend

    @Test
    void wrongCodes_sayHowManyTriesAreLeft_thenTheCodeIsDead_andANewOneWorks() throws Exception {
        String phone = number();
        JsonNode challenge = start(phone).json();
        String right = code(phone);
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
        verify(challenge, code(phone)).andExpect(status().isOk());
    }

    @Test
    void anExpiredCode_isRefused_andResendGivesAWorkingOne() throws Exception {
        String phone = number();
        JsonNode challenge = start(phone).json();
        String old = code(phone);

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        verify(challenge, old).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OTP_EXPIRED"));

        resend(challenge).andExpect(status().isOk());
        verify(challenge, code(phone)).andExpect(status().isOk());
    }

    @Test
    void resend_waitsForTheCooldown_replacesTheOldCode_andIsCapped() throws Exception {
        String phone = number();
        JsonNode challenge = start(phone).json();
        assertThat(challenge.get("resendInSeconds").asLong()).isBetween(55L, 60L);
        String first = code(phone);

        resend(challenge).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("OTP_WAIT"));
        for (int i = 2; i <= 5; i++) {
            clock.advance(Duration.ofSeconds(61));
            resend(challenge).andExpect(status().isOk());
        }
        assertThat(sms.countFor("+91" + phone)).isEqualTo(5);
        clock.advance(Duration.ofSeconds(61));
        resend(challenge).andExpect(status().isTooManyRequests());
        assertThat(sms.countFor("+91" + phone)).isEqualTo(5);

        String latest = code(phone);
        if (!latest.equals(first)) {
            verify(challenge, first).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OTP_INVALID"));
        }
        verify(challenge, latest).andExpect(status().isOk());
    }

    @Test
    void startingAgain_whileACodeIsLive_keepsThatCode_withoutASecondText() throws Exception {
        String phone = number();
        JsonNode first = start(phone).json();
        String code = code(phone);
        JsonNode second = start(phone).andExpect(status().isOk()).json();

        assertThat(sms.countFor("+91" + phone)).isEqualTo(1);
        verify(first, code).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CHALLENGE_INVALID"));
        verify(second, code).andExpect(status().isOk());
    }

    @Test
    void changingTheNumber_sendsToTheNewOne_andTheOldCodeStopsWorking() throws Exception {
        String typo = number();
        JsonNode challenge = start(typo).json();
        String oldCode = code(typo);
        String right = number();

        JsonNode moved = change(challenge, right).andExpect(status().isOk()).json();
        assertThat(moved.get("phoneHint").asText()).endsWith(right.substring(6));
        verify(challenge, oldCode).andExpect(status().isBadRequest());
        verify(moved, code(right)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGN_UP_NEEDED"));
    }

    @Test
    void aRefusingSmsProvider_doesNotCount_andAllowsAnImmediateRetry() throws Exception {
        String phone = number();
        sms.setFailing(true);
        JsonNode challenge = start(phone).andExpect(status().isOk()).json();
        assertThat(challenge.get("codeSent").asBoolean()).isFalse();
        assertThat(challenge.get("resendInSeconds").asLong()).isZero();

        sms.setFailing(false);
        resend(challenge).andExpect(status().isOk()).andExpect(jsonPath("$.codeSent").value(true));
        verify(challenge, code(phone)).andExpect(status().isOk());
    }

    @Test
    void aNonsenseChallengeOrTicket_isTheSameGeneric400() throws Exception {
        post("/api/auth/phone/verify", Map.of("challenge", "not-a-token", "code", "123456"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CHALLENGE_INVALID"));
        signUp("not-a-ticket", "Kavya Raman", ADULT_BIRTH_DATE)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TICKET_INVALID"));
    }

    // ---------------------------------------------------------------- the ticket, the name, the age

    @Test
    void theTicket_worksOnce_andOnlyForAWhile() throws Exception {
        String ticket = ticketFor(number());
        signUp(ticket, "Kavya Raman", ADULT_BIRTH_DATE).andExpect(status().isCreated());
        signUp(ticket, "Kavya Raman", ADULT_BIRTH_DATE)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TICKET_INVALID"));

        String late = ticketFor(number());
        clock.advance(Duration.ofMinutes(31));
        signUp(late, "Kavya Raman", ADULT_BIRTH_DATE)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TICKET_INVALID"));
    }

    @Test
    void aRefusedNameOrAge_leavesTheTicketUsable_forACorrectedTry() throws Exception {
        String phone = number();
        String ticket = ticketFor(phone);
        String underAge = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).toLocalDate().minusYears(17).toString();

        signUp(ticket, "Kavya Raman", underAge).andExpect(status().isBadRequest());
        signUp(ticket, "K4vya", ADULT_BIRTH_DATE).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Use letters only (spaces and . ' - are fine)"));
        signUp(ticket, "K", ADULT_BIRTH_DATE).andExpect(status().isBadRequest());
        signUp(ticket, "Kavya 😀", ADULT_BIRTH_DATE).andExpect(status().isBadRequest());
        assertThat(users.findByPhoneE164AndPhoneVerifiedAtIsNotNull("+91" + phone)).isEmpty();

        signUp(ticket, "கவியா ராமன்", ADULT_BIRTH_DATE).andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.name").value("கவியா ராமன்"));
    }

    @Test
    void signUp_withMissingOrMalformedFields_isA400WithFieldErrors() throws Exception {
        post("/api/auth/sign-up", Map.of("ticket", ticketFor(number())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.dateOfBirth").exists());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/sign-up")
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oneAccountPerNumber_aSecondSignUpForTheSameNumberIsRefused() throws Exception {
        String phone = number();
        String ticket = ticketFor(phone);
        users.saveAndFlush(new User("Someone Else", "+91" + phone,
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));

        signUp(ticket, "Kavya Raman", ADULT_BIRTH_DATE)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PHONE_IN_USE"));
    }

    // ---------------------------------------------------------------- accounts that cannot be used

    @Test
    void aSwitchedOffAccount_isTold_onlyAfterTheRightCode() throws Exception {
        String phone = number();
        makeAccount(phone);
        jdbc.update("UPDATE users SET enabled = FALSE WHERE phone_e164 = ?", "+91" + phone);
        clock.advance(Duration.ofSeconds(61));

        JsonNode challenge = start(phone).andExpect(status().isOk()).json();
        verify(challenge, code(phone)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_UNAVAILABLE"));
    }

    /** N-22: an account left without a verified number (from before 17B) keeps no session. */
    @Test
    void anAccountWithoutAVerifiedNumber_cannotUseItsSession() throws Exception {
        JsonNode session = signUp(ticketFor(number()), "Kavya Raman", ADULT_BIRTH_DATE).json();
        long id = session.get("user").get("id").asLong();
        jdbc.update("UPDATE users SET phone_verified_at = NULL WHERE id = ?", id);

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(session.get("accessToken").asText())))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        org.hamcrest.Matchers.containsString("account_unavailable")));
        post("/api/auth/refresh", Map.of("refreshToken", session.get("refreshToken").asText()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void thePasswordEndpoints_areGone() throws Exception {
        post("/api/auth/login", Map.of("email", "a@example.com", "password", "correct-horse-battery"))
                .andExpect(status().isUnauthorized());
        post("/api/auth/register", Map.of("email", "a@example.com", "password", "correct-horse-battery"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- clean-up, logs

    @Test
    void cleanUp_removesEndedCodesAndTickets_butNotLiveOnes() throws Exception {
        String old = number();
        start(old);
        ticketFor(number());
        clock.advance(Duration.ofMinutes(31));
        String live = number();
        start(live);

        phoneCodes.cleanUp();

        assertThat(codes.findByPurposeAndPhoneE164(PhoneCode.Purpose.SIGN_IN, "+91" + old)).isEmpty();
        assertThat(codes.findByPurposeAndPhoneE164(PhoneCode.Purpose.SIGN_IN, "+91" + live)).isPresent();
    }

    @Test
    void codesNumbersAndTicketsNeverReachTheLogs(CapturedOutput output) throws Exception {
        String phone = number();
        JsonNode challenge = start(phone).json();
        String code = code(phone);
        verify(challenge, code.equals("000000") ? "111111" : "000000");
        JsonNode verified = verify(challenge, code).andExpect(status().isOk()).json();
        String ticket = verified.get("signUp").get("ticket").asText();
        signUp(ticket, "Kavya Raman", ADULT_BIRTH_DATE).andExpect(status().isCreated());

        assertThat(output.getAll()).doesNotContain(code).doesNotContain(phone).doesNotContain(ticket)
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

    /** A real account for [phone] through the full code flow. */
    private void makeAccount(String phone) throws Exception {
        signUp(ticketFor(phone), "Kavya Raman", ADULT_BIRTH_DATE).andExpect(status().isCreated());
    }

    /** A ticket for [phone] through the real code flow. */
    private String ticketFor(String phone) throws Exception {
        JsonNode challenge = start(phone).andExpect(status().isOk()).json();
        return verify(challenge, code(phone)).andExpect(status().isOk()).json().get("signUp").get("ticket").asText();
    }

    private String code(String phone) {
        return sms.lastCodeFor("+91" + phone);
    }

    private Call start(String phone) throws Exception {
        return post("/api/auth/phone/start", Map.of("phone", phone));
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

    private Call signUp(String ticket, String name, String dateOfBirth) throws Exception {
        return post("/api/auth/sign-up", Map.of("ticket", ticket, "name", name, "dateOfBirth", dateOfBirth));
    }

    private Call post(String path, Map<String, ?> body) throws Exception {
        return new Call(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))), objectMapper);
    }

    private static java.util.List<String> fieldNames(JsonNode node) {
        java.util.List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** A fresh 10-digit Indian mobile number (starts with 9). */
    private static String number() {
        long n = Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000L);
        return "9" + String.format("%09d", n);
    }
}
