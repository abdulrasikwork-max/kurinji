package com.safesphere.backend.account;

import static com.safesphere.backend.support.TestAccounts.PASSWORD;
import static com.safesphere.backend.support.TestAccounts.bearer;
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
import com.safesphere.backend.evidence.repository.EvidenceItemRepository;
import com.safesphere.backend.evidence.service.EvidenceStorage;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Phase 10A.2 / 10A.3 through the real API: revocation by the credentials stamp, the per-request account check, password
 * change rules, the coercion-aware deletion (refusals, grace period, cancel, postponement by an emergency, files before
 * rows) and the anonymisation of an account the moderation audit log refers to. Other accounts are never touched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class AccountLifecycleApiIntegrationTest {

    private static final String NEW_PASSWORD = "a-new-long-passphrase";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;
    @Autowired private AccountDeletionJob job;
    @Autowired private UserRepository users;
    @Autowired private EvidenceItemRepository evidence;
    @Autowired private EvidenceStorage storage;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtEncoder jwtEncoder;

    @BeforeEach
    @AfterEach
    void resetClock() {
        clock.reset();
    }

    // ---- 10A.2: revocation ----------------------------------------------------------------------------------

    @Test
    void passwordChange_endsEveryOtherSession_andReturnsAWorkingToken() throws Exception {
        String email = email();
        String first = registerAndGetToken(mockMvc, objectMapper, email);
        String second = login(email, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String secondToken = objectMapper.readTree(second).get("accessToken").asText();

        JsonNode changed = json(changePassword(first, PASSWORD, NEW_PASSWORD).andExpect(status().isOk()));
        String fresh = changed.get("accessToken").asText();
        assertThat(changed.get("user").get("email").asText()).isEqualTo(email);

        // Both earlier tokens (including the one that made the change) are refused, with the reason for the phone.
        for (String old : List.of(first, secondToken)) {
            mockMvc.perform(get("/api/safety-contacts").header(HttpHeaders.AUTHORIZATION, bearer(old)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                            "Bearer error=\"invalid_token\", error_description=\"credentials_changed\""));
        }
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(fresh))).andExpect(status().isOk());
        login(email, PASSWORD).andExpect(status().isUnauthorized());
        String later = objectMapper.readTree(login(email, NEW_PASSWORD).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("accessToken").asText();
        mockMvc.perform(get("/api/safety-contacts").header(HttpHeaders.AUTHORIZATION, bearer(later))).andExpect(status().isOk());
    }

    @Test
    void passwordChange_wrongCurrentPassword_is409_andTheSixthAttemptIs429EvenWithTheRightOne() throws Exception {
        String token = registerAndGetToken(mockMvc, objectMapper, email());
        for (int i = 0; i < 5; i++) {
            changePassword(token, "not-my-password", NEW_PASSWORD).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Password is incorrect"));
        }
        changePassword(token, PASSWORD, NEW_PASSWORD).andExpect(status().isTooManyRequests());
        // The limit is shared: deleting is locked as well, and the token still works for everything else.
        requestDeletion(token, PASSWORD).andExpect(status().isTooManyRequests());
        mockMvc.perform(get("/api/safety-contacts").header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(16));
        changePassword(token, PASSWORD, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void passwordChange_isRefusedWhileAnSosOrASafeWalkRuns_soNoSecondSessionCanCutItOff() throws Exception {
        String token = registerAndGetToken(mockMvc, objectMapper, email());
        long emergency = startEmergency(token);
        changePassword(token, PASSWORD, NEW_PASSWORD).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You can change your password once your SOS or Safe Walk has ended."));
        // The running emergency keeps working with the same token.
        mockMvc.perform(get("/api/emergencies/" + emergency).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        resolve(token, emergency);

        Map<String, Object> walk = new LinkedHashMap<>();
        walk.put("checkInIntervalMinutes", 10);
        mockMvc.perform(post("/api/journeys").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(walk)))
                .andExpect(status().isCreated());
        changePassword(token, PASSWORD, NEW_PASSWORD).andExpect(status().isConflict());
    }

    @Test
    void passwordChange_validatesTheNewPassword() throws Exception {
        String token = registerAndGetToken(mockMvc, objectMapper, email());
        changePassword(token, PASSWORD, PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Choose a new password that is different from the current one"));
        changePassword(token, PASSWORD, "short").andExpect(status().isBadRequest());
        changePassword(token, PASSWORD, "é".repeat(40)).andExpect(status().isBadRequest()); // 80 bytes
        mockMvc.perform(put("/api/users/me/password").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aDisabledAccountsToken_isRefusedOnEveryEndpoint_notOnlyOnMe() throws Exception {
        String email = email();
        String token = registerAndGetToken(mockMvc, objectMapper, email);
        jdbc.update("UPDATE users SET enabled = FALSE WHERE email = ?", email);
        for (String path : List.of("/api/safety-contacts", "/api/emergencies", "/api/journeys", "/api/community/mine")) {
            mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                            "Bearer error=\"invalid_token\", error_description=\"account_unavailable\""));
        }
    }

    @Test
    void tokensWithoutAStamp_keepWorkingUntilTheFirstChange_andAWrongStampIsRefused() throws Exception {
        String email = email();
        String token = registerAndGetToken(mockMvc, objectMapper, email);
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk());
        long userId = users.findByEmail(email).orElseThrow().getId();
        // A correctly signed token with a stamp the account never had (e.g. from before an earlier change) is refused.
        Instant now = Instant.now();
        String stamped = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().issuer("https://safesphere.example").issuedAt(now).expiresAt(now.plusSeconds(600))
                        .subject(String.valueOf(userId)).claim("cca", 123L).build())).getTokenValue();
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
        String token = registerAndGetToken(mockMvc, objectMapper, email());
        long emergency = startEmergency(token);
        requestDeletion(token, PASSWORD).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Your account cannot be deleted while an emergency is active."));
        resolve(token, emergency);
        requestDeletion(token, PASSWORD).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Your account can be deleted 24 hours after your last emergency ended."));
        clock.advance(Duration.ofHours(1));
        requestDeletion(token, PASSWORD).andExpect(status().isConflict());

        clock.advance(Duration.ofHours(24));
        requestDeletion(token, "wrong-password").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Password is incorrect"));
        Instant before = clock.instant();
        JsonNode scheduled = json(requestDeletion(token, PASSWORD).andExpect(status().isAccepted()));
        Instant due = Instant.parse(scheduled.get("deletionDueAt").asText());
        assertThat(Duration.between(before, due)).isBetween(Duration.ofDays(7).minusSeconds(5), Duration.ofDays(7).plusSeconds(5));

        // A second request does not move the date; /me and sign-in show it; sign-in does NOT cancel it.
        clock.advance(Duration.ofDays(1));
        JsonNode again = json(requestDeletion(token, PASSWORD).andExpect(status().isAccepted()));
        assertThat(again.get("deletionDueAt").asText()).isEqualTo(scheduled.get("deletionDueAt").asText());
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletionDueAt").value(scheduled.get("deletionDueAt").asText()))
                .andExpect(jsonPath("$.deletionRequestedAt").value(scheduled.get("deletionRequestedAt").asText()));
        String email = json(mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token))))
                .get("email").asText();
        login(email, PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.deletionDueAt").value(scheduled.get("deletionDueAt").asText()));
        // During the grace period everything keeps working, SOS included.
        long during = startEmergency(token);
        resolve(token, during);
        assertThat(job.run()).doesNotContainKey(Outcome.DELETED);
        assertThat(users.findByEmail(email)).isPresent();
    }

    @Test
    void deletion_canOnlyBeCancelledWithThePassword() throws Exception {
        String token = registerAndGetToken(mockMvc, objectMapper, email());
        requestDeletion(token, PASSWORD).andExpect(status().isAccepted());
        cancel(token, "wrong-password").andExpect(status().isConflict());
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.deletionDueAt").isNotEmpty());
        cancel(token, PASSWORD).andExpect(status().isNoContent());
        cancel(token, PASSWORD).andExpect(status().isNoContent()); // idempotent
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.deletionDueAt").isEmpty())
                .andExpect(jsonPath("$.deletionRequestedAt").isEmpty());
        clock.advance(Duration.ofDays(8));
        job.run();
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk());
    }

    @Test
    void deletion_afterTheGracePeriod_removesTheEvidenceFilesAndEveryOwnedRow_andNobodyElsesData() throws Exception {
        String email = email();
        String token = registerAndGetToken(mockMvc, objectMapper, email);
        String otherEmail = email();
        String other = registerAndGetToken(mockMvc, objectMapper, otherEmail);
        addContact(token);
        addContact(other);
        long emergency = startEmergency(token);
        long otherEmergency = startEmergency(other);
        long item = declareEvidence(token, emergency);
        long otherItem = declareEvidence(other, otherEmergency);
        Path file = storage.root().resolve(evidence.findById(item).orElseThrow().getStorageKey() + ".part");
        Path otherFile = storage.root().resolve(evidence.findById(otherItem).orElseThrow().getStorageKey() + ".part");
        assertThat(file).exists();
        resolve(token, emergency);
        clock.advance(Duration.ofHours(25));
        requestDeletion(token, PASSWORD).andExpect(status().isAccepted());
        long userId = users.findByEmail(email).orElseThrow().getId();

        clock.advance(Duration.ofDays(7).minusMinutes(1));
        assertThat(job.run()).isEmpty(); // not due yet
        clock.advance(Duration.ofMinutes(2));
        assertThat(job.run()).containsEntry(Outcome.DELETED, 1);

        assertThat(Files.exists(file)).isFalse();
        assertThat(users.findByEmail(email)).isEmpty();
        for (String table : List.of("safety_contacts", "emergency_events", "evidence_custody_events")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Long.class, userId))
                    .as(table).isZero();
        }
        assertThat(evidence.findById(item)).isEmpty();
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer error=\"invalid_token\", error_description=\"account_deleted\""));
        login(email, PASSWORD).andExpect(status().isUnauthorized());
        registerAndGetToken(mockMvc, objectMapper, email); // the address is free again

        // The other person's data is untouched.
        assertThat(otherFile).exists();
        assertThat(evidence.findById(otherItem)).isPresent();
        mockMvc.perform(get("/api/safety-contacts").header(HttpHeaders.AUTHORIZATION, bearer(other)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/emergencies/" + otherEmergency).header(HttpHeaders.AUTHORIZATION, bearer(other)))
                .andExpect(status().isOk());
    }

    @Test
    void anEmergencyDuringTheGracePeriod_postponesTheDeletionUntil24HoursAfterItEnded() throws Exception {
        String email = email();
        String token = registerAndGetToken(mockMvc, objectMapper, email);
        requestDeletion(token, PASSWORD).andExpect(status().isAccepted());
        clock.advance(Duration.ofDays(7).plusMinutes(1));
        long emergency = startEmergency(token);
        assertThat(job.run()).containsEntry(Outcome.POSTPONED, 1);
        assertThat(users.findByEmail(email)).isPresent();
        mockMvc.perform(get("/api/emergencies/" + emergency).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
        resolve(token, emergency);
        clock.advance(Duration.ofHours(23));
        assertThat(job.run()).doesNotContainKey(Outcome.DELETED);
        assertThat(users.findByEmail(email)).isPresent();
        User postponed = users.findByEmail(email).orElseThrow();
        assertThat(postponed.getDeletionDueAt()).isAfter(postponed.getDeletionRequestedAt().plusDays(7));
        clock.advance(Duration.ofHours(26));
        assertThat(job.run()).containsEntry(Outcome.DELETED, 1);
        assertThat(users.findByEmail(email)).isEmpty();
    }

    @Test
    void aModeratorWhoseDecisionsAreInTheAuditLog_isAnonymised_notDeleted_andTheAuditRowStays() throws Exception {
        String email = email();
        String token = registerAndGetToken(mockMvc, objectMapper, email);
        addContact(token);
        long userId = users.findByEmail(email).orElseThrow().getId();
        jdbc.update("UPDATE users SET role = 'MODERATOR' WHERE id = ?", userId);
        jdbc.update("INSERT INTO moderation_actions (public_id, actor_id, action, reason, occurred_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), userId, "DISMISS", "NO_VIOLATION", java.time.LocalDateTime.now());
        requestDeletion(token, PASSWORD).andExpect(status().isAccepted());
        clock.advance(Duration.ofDays(8));
        assertThat(job.run()).containsEntry(Outcome.ANONYMISED, 1);

        User tombstone = users.findById(userId).orElseThrow();
        assertThat(tombstone.isErased()).isTrue();
        assertThat(tombstone.isEnabled()).isFalse();
        assertThat(tombstone.getName()).isEqualTo("Deleted account");
        assertThat(tombstone.getEmail()).isEqualTo("erased-" + userId + "@erased.invalid");
        assertThat(tombstone.getRole().name()).isEqualTo("USER");
        assertThat(tombstone.getDeletionDueAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM moderation_actions WHERE actor_id = ?", Long.class, userId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM safety_contacts WHERE user_id = ?", Long.class, userId)).isZero();
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer error=\"invalid_token\", error_description=\"account_deleted\""));
        login(email, PASSWORD).andExpect(status().isUnauthorized());
        login("erased-" + userId + "@erased.invalid", PASSWORD).andExpect(status().isUnauthorized());
        registerAndGetToken(mockMvc, objectMapper, email); // the original address is free again
    }

    @Test
    void deletion_withdrawsTheCommunityContributions_andRemovesASignalNobodyElseBacks() throws Exception {
        String email = email();
        String token = registerAndGetToken(mockMvc, objectMapper, email);
        String cell = "tdr1v9" + "bcdefghjkmnpqrstuvwxyz".charAt((int) (Math.random() * 22));
        mockMvc.perform(post("/api/community/reports").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("category", "POOR_LIGHTING", "cell", cell))))
                .andExpect(status().isAccepted());
        long userId = users.findByEmail(email).orElseThrow().getId();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM signal_contributions WHERE user_id = ?", Long.class, userId))
                .isEqualTo(1);
        requestDeletion(token, PASSWORD).andExpect(status().isAccepted());
        clock.advance(Duration.ofDays(8));
        assertThat(job.run()).containsEntry(Outcome.DELETED, 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM signal_contributions WHERE user_id = ?", Long.class, userId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM place_signals WHERE cell = ? AND category = 'POOR_LIGHTING'",
                Long.class, cell)).isZero();
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private static String email() {
        return "account." + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("email", email, "password", password))));
    }

    private ResultActions changePassword(String token, String current, String next) throws Exception {
        return mockMvc.perform(put("/api/users/me/password").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("currentPassword", current, "newPassword", next))));
    }

    private ResultActions requestDeletion(String token, String password) throws Exception {
        return mockMvc.perform(post("/api/users/me/deletion").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("password", password))));
    }

    private ResultActions cancel(String token, String password) throws Exception {
        return mockMvc.perform(post("/api/users/me/deletion/cancel").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("password", password))));
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

    private long declareEvidence(String token, long emergency) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientRef", UUID.randomUUID().toString());
        body.put("kind", "PHOTO");
        body.put("mediaType", "image/jpeg");
        body.put("sequenceNo", 0);
        body.put("sizeBytes", 100);
        body.put("ciphertextSha256", "a".repeat(64));
        body.put("plaintextSha256", "b".repeat(64));
        body.put("encryption", "SSEV1");
        body.put("capturedStartedAt", Instant.now().minusSeconds(5).toString());
        body.put("capturedEndedAt", Instant.now().toString());
        return json(mockMvc.perform(post("/api/emergencies/" + emergency + "/evidence")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private String body(Map<String, ?> values) throws Exception {
        return objectMapper.writeValueAsString(values);
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
