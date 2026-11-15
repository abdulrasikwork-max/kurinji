package com.safesphere.backend.account;

import static com.safesphere.backend.support.TestAccounts.bearer;
import static com.safesphere.backend.support.TestAccounts.e164For;
import static com.safesphere.backend.support.TestAccounts.registerAndGetToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.account.service.AccountDeletionJob;
import com.safesphere.backend.account.service.AccountEraser.Outcome;
import com.safesphere.backend.support.CapturingOtpSenderConfig;
import com.safesphere.backend.support.CapturingOtpSenderConfig.CapturingOtpSender;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.TestAccounts;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Phase 10A.2 / 10A.3 through the real API, confirmed since N2 with a fresh code texted to the account's number (N-7)
 * instead of the password: revocation by the credentials stamp ("Sign out all other phones", N-26), the per-request
 * account check, the shared limit on wrong codes, one-use codes that belong to one account, the real-name change
 * (N-24), the coercion-aware deletion (refusals, grace period, cancel, postponement by an emergency, files before rows)
 * and the anonymisation of an account the moderation audit log refers to. Other accounts are never touched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({MutableClockConfig.class, CapturingOtpSenderConfig.class})
class AccountLifecycleApiIntegrationTest {

    private static final String WRONG_CODE = "That code is not right, or it has ended. Ask for a new code.";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;
    @Autowired private AccountDeletionJob job;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private CapturingOtpSender sms;

    @BeforeEach
    @AfterEach
    void resetClock() {
        clock.reset();
    }

    // ---- 10A.2 / N-26: revocation ------------------------------------------------------------------------------

    @Test
    void signingOutTheOtherPhones_endsEveryEarlierToken_andReturnsAWorkingOne() throws Exception {
        String key = key();
        String first = registerAndGetToken(mockMvc, objectMapper, key);
        String second = signedIn(key).get("accessToken").asText();

        JsonNode answer = json(signOutOthers(first, confirm(first, key)).andExpect(status().isOk()));
        String fresh = answer.get("accessToken").asText();
        assertThat(answer.get("user").get("phoneHint").asText()).endsWith(e164For(key).substring(9));

        // Both earlier tokens (including the one that asked) are refused, with the reason for the phone.
        for (String old : List.of(first, second)) {
            mockMvc.perform(get("/api/safety-contacts").header(HttpHeaders.AUTHORIZATION, bearer(old)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                            "Bearer error=\"invalid_token\", error_description=\"credentials_changed\""));
        }
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(fresh))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_sessions WHERE user_id = ? AND revoked_reason = ?",
                Long.class, id(key), "OTHERS_SIGNED_OUT")).isEqualTo(1);
        // Signing in again later is a new code as always.
        String later = signedIn(key).get("accessToken").asText();
        mockMvc.perform(get("/api/safety-contacts").header(HttpHeaders.AUTHORIZATION, bearer(later))).andExpect(status().isOk());
    }

    @Test
    void wrongCodes_are409_andTheSixthAttemptIs429EvenWithTheRightCode_forEveryConfirmedAction() throws Exception {
        String key = key();
        String token = registerAndGetToken(mockMvc, objectMapper, key);
        for (int i = 0; i < 5; i++) {
            requestDeletion(token, wrongCode(confirm(token, key))).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(WRONG_CODE));
        }
        requestDeletion(token, confirm(token, key)).andExpect(status().isTooManyRequests());
        // The limit is shared: signing out the other phones is locked as well, and the token still works for the rest.
        clock.advance(Duration.ofSeconds(61));
        signOutOthers(token, confirm(token, key)).andExpect(status().isTooManyRequests());
        mockMvc.perform(get("/api/safety-contacts").header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(16));
        requestDeletion(token, confirm(token, key)).andExpect(status().isAccepted());
    }

    @Test
    void aCode_worksOnce_andOnlyForItsOwnAccount() throws Exception {
        String aKey = key();
        String a = registerAndGetToken(mockMvc, objectMapper, aKey);
        String bKey = key();
        String b = registerAndGetToken(mockMvc, objectMapper, bKey);

        Map<String, String> aCode = confirm(a, aKey);
        requestDeletion(b, aCode).andExpect(status().isConflict()); // someone else's code
        requestDeletion(a, aCode).andExpect(status().isAccepted());
        cancel(a, aCode).andExpect(status().isConflict()); // already used
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(b)))
                .andExpect(jsonPath("$.deletionDueAt").isEmpty());
        // A code that was never asked for, and a missing one.
        requestDeletion(a, Map.of("challenge", "x".repeat(43), "code", "123456")).andExpect(status().isConflict());
        mockMvc.perform(post("/api/users/me/deletion").header(HttpHeaders.AUTHORIZATION, bearer(a))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void askingForACode_needsASignedInCaller_andTextsTheAccountsOwnNumber() throws Exception {
        mockMvc.perform(post("/api/users/me/confirmation")).andExpect(status().isUnauthorized());
        String key = key();
        String token = registerAndGetToken(mockMvc, objectMapper, key);
        long before = sms.countFor(e164For(key));
        JsonNode challenge = json(mockMvc.perform(post("/api/users/me/confirmation")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk()));
        assertThat(challenge.get("phoneHint").asText()).endsWith(e164For(key).substring(9));
        assertThat(challenge.get("codeSent").asBoolean()).isTrue();
        assertThat(sms.countFor(e164For(key))).isEqualTo(before + 1);
    }

    @Test
    void signingOutTheOtherPhones_isRefusedWhileAnSosOrASafeWalkRuns_soNoSecondSessionCanCutItOff() throws Exception {
        String key = key();
        String token = registerAndGetToken(mockMvc, objectMapper, key);
        long emergency = startEmergency(token);
        Map<String, String> code = confirm(token, key);
        signOutOthers(token, code).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You can sign out your other phones once your SOS or Safe Walk has ended."));
        // The running emergency keeps working with the same token, and the refused action did not use the code.
        mockMvc.perform(get("/api/emergencies/" + emergency).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        resolve(token, emergency);

        Map<String, Object> walk = new LinkedHashMap<>();
        walk.put("checkInIntervalMinutes", 10);
        mockMvc.perform(post("/api/journeys").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(walk)))
                .andExpect(status().isCreated());
        signOutOthers(token, code).andExpect(status().isConflict());
    }

    @Test
    void theRealName_canBeCorrected_andFollowsTheNameRule() throws Exception {
        String key = key();
        String token = registerAndGetToken(mockMvc, objectMapper, key);
        mockMvc.perform(put("/api/users/me/name").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("name", "  Kavya   Raman "))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Kavya Raman"));
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.name").value("Kavya Raman"));
        mockMvc.perform(put("/api/users/me/name").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("name", "K4vya"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/users/me/name").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "Kavya Raman"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void thePasswordEndpoint_isGone() throws Exception {
        String token = registerAndGetToken(mockMvc, objectMapper, key());
        mockMvc.perform(put("/api/users/me/password").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("currentPassword", "x", "newPassword", "y"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void aDisabledAccountsToken_isRefusedOnEveryEndpoint_notOnlyOnMe() throws Exception {
        String key = key();
        String token = registerAndGetToken(mockMvc, objectMapper, key);
        jdbc.update("UPDATE users SET enabled = FALSE WHERE id = ?", id(key));
        for (String path : List.of("/api/safety-contacts", "/api/emergencies", "/api/journeys", "/api/profiles/me")) {
            mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                            "Bearer error=\"invalid_token\", error_description=\"account_unavailable\""));
        }
    }

    @Test
    void tokensWithoutAStamp_keepWorkingUntilTheFirstChange_andAWrongStampIsRefused() throws Exception {
        String key = key();
        String token = registerAndGetToken(mockMvc, objectMapper, key);
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk());
        // A correctly signed token with a stamp the account never had (e.g. from before an earlier change) is refused.
        Instant now = Instant.now();
        String stamped = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().issuer("https://safesphere.example").issuedAt(now).expiresAt(now.plusSeconds(600))
                        .subject(String.valueOf(id(key))).claim("cca", 123L).build())).getTokenValue();
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(stamped)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer error=\"invalid_token\", error_description=\"credentials_changed\""));
        // A missing or expired token keeps the plain answer (no reason is given to someone without a valid token).
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
    }

    // ---- 10A.3: deletion ------------------------------------------------------------------------------------

    @Test
    void deletion_isRefusedDuringAnEmergencyAndFor24HoursAfter_thenScheduledWithAGracePeriod() throws Exception {
        String key = key();
        String token = registerAndGetToken(mockMvc, objectMapper, key);
        long emergency = startEmergency(token);
        requestDeletion(token, confirm(token, key)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Your account cannot be deleted while an emergency is active."));
        resolve(token, emergency);
        Map<String, String> code = confirm(token, key);
        requestDeletion(token, code).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Your account can be deleted 24 hours after your last emergency ended."));
        clock.advance(Duration.ofHours(1));
        requestDeletion(token, code).andExpect(status().isConflict());

        clock.advance(Duration.ofHours(24));
        requestDeletion(token, wrongCode(confirm(token, key))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(WRONG_CODE));
        clock.advance(Duration.ofSeconds(61));
        Instant before = clock.instant();
        JsonNode scheduled = json(requestDeletion(token, confirm(token, key)).andExpect(status().isAccepted()));
        Instant due = Instant.parse(scheduled.get("deletionDueAt").asText());
        assertThat(Duration.between(before, due)).isBetween(Duration.ofDays(7).minusSeconds(5), Duration.ofDays(7).plusSeconds(5));

        // A second request does not move the date; /me and sign-in show it; sign-in does NOT cancel it.
        clock.advance(Duration.ofDays(1));
        JsonNode again = json(requestDeletion(token, confirm(token, key)).andExpect(status().isAccepted()));
        assertThat(again.get("deletionDueAt").asText()).isEqualTo(scheduled.get("deletionDueAt").asText());
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletionDueAt").value(scheduled.get("deletionDueAt").asText()))
                .andExpect(jsonPath("$.deletionRequestedAt").value(scheduled.get("deletionRequestedAt").asText()));
        assertThat(signedIn(key).get("user").get("deletionDueAt").asText())
                .isEqualTo(scheduled.get("deletionDueAt").asText());
        // During the grace period everything keeps working, SOS included.
        long during = startEmergency(token);
        resolve(token, during);
        assertThat(job.run()).doesNotContainKey(Outcome.DELETED);
        assertThat(account(key)).isPresent();
    }

    @Test
    void deletion_canOnlyBeCancelledWithACode() throws Exception {
        String key = key();
        String token = registerAndGetToken(mockMvc, objectMapper, key);
        requestDeletion(token, confirm(token, key)).andExpect(status().isAccepted());
        clock.advance(Duration.ofSeconds(61));
        cancel(token, wrongCode(confirm(token, key))).andExpect(status().isConflict());
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.deletionDueAt").isNotEmpty());
        clock.advance(Duration.ofSeconds(61));
        cancel(token, confirm(token, key)).andExpect(status().isNoContent());
        clock.advance(Duration.ofSeconds(61));
        cancel(token, confirm(token, key)).andExpect(status().isNoContent()); // idempotent
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.deletionDueAt").isEmpty())
                .andExpect(jsonPath("$.deletionRequestedAt").isEmpty());
        clock.advance(Duration.ofDays(8));
        job.run();
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk());
    }

    @Test
    void deletion_afterTheGracePeriod_removesEveryOwnedRow_andNobodyElsesData() throws Exception {
        String key = key();
        String token = registerAndGetToken(mockMvc, objectMapper, key);
        String other = registerAndGetToken(mockMvc, objectMapper, key());
        addContact(token);
        addContact(other);
        long emergency = startEmergency(token);
        long otherEmergency = startEmergency(other);
        resolve(token, emergency);
        clock.advance(Duration.ofHours(25));
        requestDeletion(token, confirm(token, key)).andExpect(status().isAccepted());
        long userId = id(key);

        clock.advance(Duration.ofDays(7).minusMinutes(1));
        assertThat(job.run()).isEmpty(); // not due yet
        clock.advance(Duration.ofMinutes(2));
        assertThat(job.run()).containsEntry(Outcome.DELETED, 1);

        assertThat(account(key)).isEmpty();
        for (String table : List.of("safety_contacts", "emergency_events", "phone_codes")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Long.class, userId))
                    .as(table).isZero();
        }
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer error=\"invalid_token\", error_description=\"account_deleted\""));
        assertThat(codeAnswer(key)).isEqualTo("SIGN_UP_NEEDED"); // the number has no account any more
        registerAndGetToken(mockMvc, objectMapper, key); // the number is free again

        // The other person's data is untouched.
        mockMvc.perform(get("/api/safety-contacts").header(HttpHeaders.AUTHORIZATION, bearer(other)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/emergencies/" + otherEmergency).header(HttpHeaders.AUTHORIZATION, bearer(other)))
                .andExpect(status().isOk());
    }

    @Test
    void anEmergencyDuringTheGracePeriod_postponesTheDeletionUntil24HoursAfterItEnded() throws Exception {
        String key = key();
        String token = registerAndGetToken(mockMvc, objectMapper, key);
        requestDeletion(token, confirm(token, key)).andExpect(status().isAccepted());
        clock.advance(Duration.ofDays(7).plusMinutes(1));
        long emergency = startEmergency(token);
        assertThat(job.run()).containsEntry(Outcome.POSTPONED, 1);
        assertThat(account(key)).isPresent();
        mockMvc.perform(get("/api/emergencies/" + emergency).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
        resolve(token, emergency);
        clock.advance(Duration.ofHours(23));
        assertThat(job.run()).doesNotContainKey(Outcome.DELETED);
        User postponed = account(key).orElseThrow();
        assertThat(postponed.getDeletionDueAt()).isAfter(postponed.getDeletionRequestedAt().plusDays(7));
        clock.advance(Duration.ofHours(26));
        assertThat(job.run()).containsEntry(Outcome.DELETED, 1);
        assertThat(account(key)).isEmpty();
    }

    @Test
    void aModeratorWhoseDecisionsAreInTheAuditLog_isAnonymised_notDeleted_andTheAuditRowStays() throws Exception {
        String key = key();
        String token = registerAndGetToken(mockMvc, objectMapper, key);
        addContact(token);
        long userId = id(key);
        jdbc.update("UPDATE users SET role = 'MODERATOR' WHERE id = ?", userId);
        jdbc.update("INSERT INTO moderation_actions (public_id, actor_id, action, reason, occurred_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), userId, "DISMISS", "NO_VIOLATION", java.time.LocalDateTime.now());
        requestDeletion(token, confirm(token, key)).andExpect(status().isAccepted());
        clock.advance(Duration.ofDays(8));
        assertThat(job.run()).containsEntry(Outcome.ANONYMISED, 1);

        User tombstone = users.findById(userId).orElseThrow();
        assertThat(tombstone.isErased()).isTrue();
        assertThat(tombstone.isEnabled()).isFalse();
        assertThat(tombstone.getName()).isEqualTo("Deleted account");
        assertThat(tombstone.getPhoneE164()).isNull();
        assertThat(tombstone.isPhoneVerified()).isFalse();
        assertThat(tombstone.getRole().name()).isEqualTo("USER");
        assertThat(tombstone.getDeletionDueAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM moderation_actions WHERE actor_id = ?", Long.class, userId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM safety_contacts WHERE user_id = ?", Long.class, userId)).isZero();
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer error=\"invalid_token\", error_description=\"account_deleted\""));
        assertThat(codeAnswer(key)).isEqualTo("SIGN_UP_NEEDED"); // a tombstone keeps no number
        registerAndGetToken(mockMvc, objectMapper, key); // the number is free again
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private static String key() {
        return "account." + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
    }

    private Optional<User> account(String key) {
        return users.findByPhoneE164AndPhoneVerifiedAtIsNotNull(e164For(key));
    }

    private long id(String key) {
        return account(key).orElseThrow().getId();
    }

    /** A fresh confirmation code for [key]'s account, asked for with [token]. */
    private Map<String, String> confirm(String token, String key) throws Exception {
        return TestAccounts.confirmation(mockMvc, objectMapper, token, key);
    }

    private static Map<String, String> wrongCode(Map<String, String> confirmation) {
        Map<String, String> wrong = new HashMap<>(confirmation);
        wrong.put("code", confirmation.get("code").equals("000000") ? "111111" : "000000");
        return wrong;
    }

    /** Phase N2: [key]'s account signs in again with a texted code; the session part of the answer. */
    private JsonNode signedIn(String key) throws Exception {
        return TestAccounts.signIn(mockMvc, objectMapper, key, null);
    }

    /** Phase N2: what the right code for [key]'s number leads to (SIGNED_IN or SIGN_UP_NEEDED). */
    private String codeAnswer(String key) throws Exception {
        String phone = e164For(key);
        JsonNode challenge = json(mockMvc.perform(post("/api/auth/phone/start").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("phone", phone)))).andExpect(status().isOk()));
        String code = sms.lastCodeFor(phone);
        return json(mockMvc.perform(post("/api/auth/phone/verify").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("challenge", challenge.get("challenge").asText(), "code", code))))
                .andExpect(status().isOk())).get("status").asText();
    }

    private ResultActions signOutOthers(String token, Map<String, String> confirmation) throws Exception {
        return mockMvc.perform(post("/api/users/me/sessions/sign-out-others").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body(confirmation)));
    }

    private ResultActions requestDeletion(String token, Map<String, String> confirmation) throws Exception {
        return mockMvc.perform(post("/api/users/me/deletion").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body(confirmation)));
    }

    private ResultActions cancel(String token, Map<String, String> confirmation) throws Exception {
        return mockMvc.perform(post("/api/users/me/deletion/cancel").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body(confirmation)));
    }

    private long startEmergency(String token) throws Exception {
        return json(mockMvc.perform(post("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private void resolve(String token, long emergency) throws Exception {
        mockMvc.perform(put("/api/emergencies/" + emergency + "/resolve").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
    }

    private void addContact(String token) throws Exception {
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("name", "Contact");
        contact.put("phoneNumber", "98765" + (10000 + (int) (Math.random() * 89999)));
        contact.put("relationship", "FRIEND");
        contact.put("priority", 1);
        contact.put("primary", false);
        contact.put("enabled", true);
        mockMvc.perform(post("/api/safety-contacts").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(contact)))
                .andExpect(status().isCreated());
    }

    private String body(Map<String, ?> values) throws Exception {
        return objectMapper.writeValueAsString(values);
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
