package com.safesphere.backend.auth;

import static com.safesphere.backend.support.TestAccounts.PASSWORD;
import static com.safesphere.backend.support.TestAccounts.bearer;
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
import com.safesphere.backend.auth.service.AuthService;
import com.safesphere.backend.auth.service.RefreshSecrets;
import com.safesphere.backend.common.exception.SessionRefusedException;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * Phase 11 C1 "session continuity": device sessions with a rotating refresh secret (V19). An expired access token is
 * recovered through the refresh secret (an SOS after days without the app must reach the server without the password);
 * reuse, sign-out, password change, deletion and disabling end sessions; accounts stay isolated.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class DeviceSessionApiIntegrationTest {

    private static final String ISSUER = "https://safesphere.example";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private AuthService authService;
    @Autowired private AccountDeletionJob deletionJob;

    @BeforeEach
    @AfterEach
    void resetClock() {
        clock.reset();
    }

    // ---- sign-in and refresh --------------------------------------------------------------------------------

    @Test
    void signIn_returnsARefreshTokenForTheTokensSession_andOnlyItsHashIsStored() throws Exception {
        Session phone = register(email());
        assertThat(phone.refreshToken()).matches("[0-9a-f-]{36}\\.[A-Za-z0-9_-]{43}");
        String sid = claims(phone.accessToken()).get("sid").asText();
        assertThat(phone.refreshToken()).startsWith(sid + ".");

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM device_sessions WHERE session_id = ?", sid);
        String secret = phone.refreshToken().substring(37);
        assertThat(row.get("secret_hash")).isEqualTo(RefreshSecrets.hash(secret));
        assertThat(row.get("previous_secret_hash")).isNull();
        // The raw secret appears in no column of the row (nor does the whole refresh token).
        for (Object value : row.values()) {
            if (value != null) assertThat(value.toString()).doesNotContain(secret).doesNotContain(phone.refreshToken());
        }
    }

    @Test
    void validRefresh_givesAWorkingTokenForTheSameSignIn_andRotatesTheSecret() throws Exception {
        Session phone = register(email());
        Session refreshed = refreshOk(phone.refreshToken());
        assertThat(refreshed.refreshToken()).isNotEqualTo(phone.refreshToken());
        assertThat(claims(refreshed.accessToken()).get("sid").asText()).isEqualTo(claims(phone.accessToken()).get("sid").asText());
        assertThat(claims(refreshed.accessToken()).get("auth_time").asLong())
                .isEqualTo(claims(phone.accessToken()).get("auth_time").asLong());
        me(refreshed.accessToken()).andExpect(status().isOk());

        String sid = claims(phone.accessToken()).get("sid").asText();
        assertThat(jdbc.queryForObject("SELECT secret_hash FROM device_sessions WHERE session_id = ?", String.class, sid))
                .isEqualTo(RefreshSecrets.hash(refreshed.refreshToken().substring(37)));
        assertThat(jdbc.queryForObject("SELECT previous_secret_hash FROM device_sessions WHERE session_id = ?", String.class, sid))
                .isEqualTo(RefreshSecrets.hash(phone.refreshToken().substring(37)));
    }

    @Test
    void anExpiredAccessToken_isRecoveredThroughTheRefreshSecret() throws Exception {
        Session phone = register(email());
        String expired = token(phone.accessToken(), Instant.now().minus(Duration.ofDays(3)), Duration.ofHours(24));
        // The expired token gets the plain answer (no account reason): the phone knows to refresh, not to sign out.
        me(expired).andExpect(status().isUnauthorized()).andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        Session refreshed = refreshOk(phone.refreshToken());
        me(refreshed.accessToken()).andExpect(status().isOk());
        // An SOS can be recorded with the recovered token.
        mockMvc.perform(post("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(refreshed.accessToken())))
                .andExpect(status().isCreated());
    }

    @Test
    void afterWeeksWithoutTheApp_theSessionStillRefreshes_butNotAfterTheIdleTimeout() throws Exception {
        Session phone = register(email());
        clock.advance(Duration.ofDays(60));
        Session later = refreshOk(phone.refreshToken());
        // Sliding: 89 more days after the last use still works...
        clock.advance(Duration.ofDays(89));
        Session muchLater = refreshOk(later.refreshToken());
        // ...but 91 days without any use does not.
        clock.advance(Duration.ofDays(91));
        refresh(muchLater.refreshToken()).andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, refusal("session_revoked")));
        assertThat(revokedReason(phone)).isEqualTo("EXPIRED");
    }

    // ---- rotation and reuse -------------------------------------------------------------------------------------

    @Test
    void theReplacedSecret_shownAgainWithinTheGrace_isALostAnswer_andIsRotatedOnceMore() throws Exception {
        Session phone = register(email());
        refreshOk(phone.refreshToken()); // the answer to this one "got lost"
        clock.advance(Duration.ofSeconds(20));
        Session retried = refreshOk(phone.refreshToken());
        me(retried.accessToken()).andExpect(status().isOk());
        assertThat(revokedReason(phone)).isNull();
    }

    @Test
    void theReplacedSecret_shownAfterTheGrace_isReuse_andEndsTheWholeSession() throws Exception {
        Session phone = register(email());
        Session current = refreshOk(phone.refreshToken());
        clock.advance(Duration.ofMinutes(2));
        refresh(phone.refreshToken()).andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, refusal("session_revoked")));
        assertThat(revokedReason(phone)).isEqualTo("REUSE_DETECTED");
        // The newest secret and the access tokens of that sign-in stop working too.
        refresh(current.refreshToken()).andExpect(status().isUnauthorized());
        me(current.accessToken()).andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, refusal("session_revoked")));
    }

    @Test
    void anOlderSecretOrAGuess_isRefused_withoutEndingTheSession() throws Exception {
        Session phone = register(email());
        Session second = refreshOk(phone.refreshToken());
        Session third = refreshOk(second.refreshToken());
        clock.advance(Duration.ofMinutes(5));
        // Two generations back: not recognised at all (refused, nothing changes).
        refresh(phone.refreshToken()).andExpect(status().isUnauthorized());
        // A guessed secret for a real session id: refused; the owner is not signed out by a stranger.
        String sid = claims(phone.accessToken()).get("sid").asText();
        refresh(sid + "." + RefreshSecrets.newSecret()).andExpect(status().isUnauthorized());
        assertThat(revokedReason(phone)).isNull();
        refreshOk(third.refreshToken());
    }

    @Test
    void malformedRefreshTokens_areRefused() throws Exception {
        refresh("not-a-token").andExpect(status().isUnauthorized());
        refresh(UUID.randomUUID() + "." + RefreshSecrets.newSecret()).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentRefreshesOfOneSession_leaveOneConsistentRow() throws Exception {
        Session phone = register(email());
        String sid = claims(phone.accessToken()).get("sid").asText();
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Callable<String> task = () -> {
                    start.await();
                    try {
                        return authService.refresh(phone.refreshToken()).refreshToken();
                    } catch (SessionRefusedException e) {
                        return null;
                    }
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            List<String> issued = new ArrayList<>();
            for (Future<String> result : results) {
                String token = result.get();
                if (token != null) issued.add(token);
            }
            // The row lock serialises them: the first uses the current secret, the second the just-replaced one (lost
            // answer, within the grace); later ones present a secret two generations old and are refused, while the
            // session itself stays usable (nothing a client bug does here signs the person out).
            assertThat(issued).hasSize(2).doesNotHaveDuplicates();
            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM device_sessions WHERE session_id = ?", sid);
            assertThat(row.get("revoked_at")).isNull();
            long current = issued.stream()
                    .filter(t -> RefreshSecrets.hash(t.substring(37)).equals(row.get("secret_hash"))).count();
            long previous = issued.stream()
                    .filter(t -> RefreshSecrets.hash(t.substring(37)).equals(row.get("previous_secret_hash"))).count();
            assertThat(current).isEqualTo(1);
            assertThat(previous).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- ending sessions ----------------------------------------------------------------------------------------

    @Test
    void signOut_endsExactlyThatSession() throws Exception {
        String email = email();
        Session phone = register(email);
        Session otherPhone = login(email);
        signOut(phone.refreshToken()).andExpect(status().isNoContent());
        refresh(phone.refreshToken()).andExpect(status().isUnauthorized());
        me(phone.accessToken()).andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, refusal("session_revoked")));
        assertThat(revokedReason(phone)).isEqualTo("SIGNED_OUT");
        // The account's other phone is not affected.
        me(otherPhone.accessToken()).andExpect(status().isOk());
        refreshOk(otherPhone.refreshToken());
        // Signing out again, or with nonsense, answers the same.
        signOut(phone.refreshToken()).andExpect(status().isNoContent());
        signOut(UUID.randomUUID() + "." + RefreshSecrets.newSecret()).andExpect(status().isNoContent());
    }

    @Test
    void passwordChange_endsEveryOtherSession_butKeepsTheCallersOne() throws Exception {
        String email = email();
        Session phone = register(email);
        Session otherPhone = login(email);
        JsonNode changed = json(mockMvc.perform(put("/api/users/me/password")
                        .header(HttpHeaders.AUTHORIZATION, bearer(phone.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("currentPassword", PASSWORD,
                                "newPassword", "a-new-long-password"))))
                .andExpect(status().isOk()));
        // The caller already has a session: it keeps its refresh secret (none is sent).
        assertThat(changed.get("refreshToken").isNull()).isTrue();
        refresh(otherPhone.refreshToken()).andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, refusal("session_revoked")));
        assertThat(revokedReason(otherPhone)).isEqualTo("PASSWORD_CHANGED");
        Session recovered = refreshOk(phone.refreshToken());
        me(recovered.accessToken()).andExpect(status().isOk());
        // The caller's sign-in counts as freshly authenticated.
        assertThat(claims(recovered.accessToken()).get("auth_time").asLong())
                .isGreaterThanOrEqualTo(claims(phone.accessToken()).get("auth_time").asLong());
    }

    @Test
    void aDisabledAccount_cannotRefresh() throws Exception {
        Session phone = register(email());
        long userId = claims(phone.accessToken()).get("sub").asLong();
        jdbc.update("UPDATE users SET enabled = FALSE WHERE id = ?", userId);
        try {
            refresh(phone.refreshToken()).andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, refusal("account_unavailable")));
            assertThat(revokedReason(phone)).isEqualTo("ACCOUNT_DISABLED");
        } finally {
            jdbc.update("UPDATE users SET enabled = TRUE WHERE id = ?", userId);
        }
        // Switching the account back on does not bring the ended session back.
        refresh(phone.refreshToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void accountDeletion_removesEverySession() throws Exception {
        String email = email();
        Session phone = register(email);
        Session otherPhone = login(email);
        long userId = claims(phone.accessToken()).get("sub").asLong();
        mockMvc.perform(post("/api/users/me/deletion").header(HttpHeaders.AUTHORIZATION, bearer(phone.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", PASSWORD))))
                .andExpect(status().isAccepted());
        // During the grace period the account (and its sessions) keep working.
        Session during = refreshOk(otherPhone.refreshToken());
        jdbc.update("UPDATE users SET deletion_requested_at = ?, deletion_due_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusDays(8)),
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)), userId);
        deletionJob.run();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_sessions WHERE user_id = ?", Long.class, userId)).isZero();
        refresh(phone.refreshToken()).andExpect(status().isUnauthorized());
        refresh(during.refreshToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void sessionsOfDifferentAccounts_stayApart() throws Exception {
        Session alice = register(email());
        Session bob = register(email());
        String aliceSid = claims(alice.accessToken()).get("sid").asText();
        String bobSecret = bob.refreshToken().substring(37);
        // Bob's secret on Alice's session id is just a wrong secret: refused, and Alice is not signed out.
        refresh(aliceSid + "." + bobSecret).andExpect(status().isUnauthorized());
        signOut(aliceSid + "." + bobSecret).andExpect(status().isNoContent());
        assertThat(revokedReason(alice)).isNull();
        // Alice signing out ends Alice's session only.
        signOut(alice.refreshToken()).andExpect(status().isNoContent());
        refreshOk(bob.refreshToken());
        refresh(alice.refreshToken()).andExpect(status().isUnauthorized());
        // A refresh answers for its own account only.
        Session bobRefreshed = refreshOk(login(bob.email()).refreshToken());
        assertThat(claims(bobRefreshed.accessToken()).get("sub").asLong())
                .isEqualTo(claims(bob.accessToken()).get("sub").asLong());
    }

    // ---- signing in again on the same phone ---------------------------------------------------------------------

    @Test
    void signingInAgainOnTheSamePhone_takesOverTheOldSignIn_andItsRunningSos() throws Exception {
        String email = email();
        Session phone = register(email);
        long emergency = json(mockMvc.perform(post("/api/emergencies")
                        .header(HttpHeaders.AUTHORIZATION, bearer(phone.accessToken())))
                .andExpect(status().isCreated())).get("id").asLong();
        // "Sign in to sync" on the phone: the person types the password again; the phone shows its old refresh token.
        JsonNode body = json(mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD,
                                "previousRefreshToken", phone.refreshToken()))))
                .andExpect(status().isOk()));
        String again = body.get("accessToken").asText();
        assertThat(revokedReason(phone)).isEqualTo("SIGNED_OUT");
        refresh(phone.refreshToken()).andExpect(status().isUnauthorized());
        // Ending the SOS from the new sign-in needs no password: it is the same phone, not "another one".
        mockMvc.perform(put("/api/emergencies/" + emergency + "/resolve").header(HttpHeaders.AUTHORIZATION, bearer(again)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endedElsewhere").value(false));
    }

    @Test
    void anotherAccountsRefreshToken_inALogin_changesNothing() throws Exception {
        Session alice = register(email());
        Session bob = register(email());
        long emergency = json(mockMvc.perform(post("/api/emergencies")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
                .andExpect(status().isCreated())).get("id").asLong();
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", bob.email(), "password", PASSWORD,
                                "previousRefreshToken", alice.refreshToken()))))
                .andExpect(status().isOk());
        assertThat(revokedReason(alice)).isNull();
        assertThat(jdbc.queryForObject("SELECT started_session FROM emergency_events WHERE id = ?", String.class, emergency))
                .isEqualTo(claims(alice.accessToken()).get("sid").asText());
        refreshOk(alice.refreshToken());
    }

    // ---- sign-ins from before V19 ---------------------------------------------------------------------------

    @Test
    void aSignInFromBeforeDeviceSessions_isAdoptedOnce_atItsNextRenewal() throws Exception {
        Session phone = register(email());
        long userId = claims(phone.accessToken()).get("sub").asLong();
        String sid = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String legacy = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().issuer(ISSUER).issuedAt(now).expiresAt(now.plusSeconds(600))
                        .subject(String.valueOf(userId)).claim("sid", sid)
                        .claim("auth_time", now.getEpochSecond()).build())).getTokenValue();
        me(legacy).andExpect(status().isOk()); // a "sid" without a row is still accepted
        JsonNode first = json(renew(legacy).andExpect(status().isOk()));
        String refreshToken = first.get("refreshToken").asText();
        assertThat(refreshToken).startsWith(sid + ".");
        JsonNode second = json(renew(first.get("accessToken").asText()).andExpect(status().isOk()));
        assertThat(second.get("refreshToken").isNull()).isTrue(); // only once
        refreshOk(refreshToken);
    }

    @Test
    void endedSessions_areDeletedAfterTheRetention() throws Exception {
        Session phone = register(email());
        String sid = claims(phone.accessToken()).get("sid").asText();
        signOut(phone.refreshToken()).andExpect(status().isNoContent());
        deletionJob.run();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_sessions WHERE session_id = ?", Long.class, sid)).isOne();
        clock.advance(Duration.ofDays(31));
        deletionJob.run();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_sessions WHERE session_id = ?", Long.class, sid)).isZero();
    }

    // ---- hashing ----------------------------------------------------------------------------------------------

    @Test
    void secretsAreHashedWithSha256_andComparedInConstantTime() throws Exception {
        String secret = RefreshSecrets.newSecret();
        assertThat(secret).matches("[A-Za-z0-9_-]{43}");
        assertThat(RefreshSecrets.hash(secret)).matches("[0-9a-f]{64}");
        assertThat(RefreshSecrets.matches(secret, RefreshSecrets.hash(secret))).isTrue();
        assertThat(RefreshSecrets.matches(secret, RefreshSecrets.hash(RefreshSecrets.newSecret()))).isFalse();
        assertThat(RefreshSecrets.matches(secret, null)).isFalse();
        assertThat(RefreshSecrets.parse("x." + secret)).isNull();
        String source = Files.readString(
                Path.of("src/main/java/com/safesphere/backend/auth/service/RefreshSecrets.java"), StandardCharsets.UTF_8);
        assertThat(source).contains("MessageDigest.isEqual(");
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private record Session(String email, String accessToken, String refreshToken) {
    }

    private static String email() {
        return "device." + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
    }

    private static String refusal(String reason) {
        return "Bearer error=\"invalid_token\", error_description=\"" + reason + "\"";
    }

    /** V25: sign-up, e-mail verified, first sign-in (sign-up itself no longer starts a session). */
    private Session register(String email) throws Exception {
        JsonNode body = com.safesphere.backend.support.TestAccounts.registerVerifiedAndLogin(mockMvc, objectMapper,
                "Test User", email);
        return new Session(email, body.get("accessToken").asText(), body.get("refreshToken").asText());
    }

    private Session login(String email) throws Exception {
        JsonNode body = json(mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk()));
        return new Session(email, body.get("accessToken").asText(), body.get("refreshToken").asText());
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))));
    }

    private Session refreshOk(String refreshToken) throws Exception {
        JsonNode body = json(refresh(refreshToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer")));
        return new Session(body.get("user").get("email").asText(), body.get("accessToken").asText(),
                body.get("refreshToken").asText());
    }

    private ResultActions signOut(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/sign-out").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))));
    }

    private ResultActions renew(String token) throws Exception {
        return mockMvc.perform(post("/api/users/me/token").header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    private ResultActions me(String token) throws Exception {
        return mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    private String revokedReason(Session session) throws Exception {
        String sid = claims(session.accessToken()).get("sid").asText();
        return jdbc.queryForObject("SELECT revoked_reason FROM device_sessions WHERE session_id = ?", String.class, sid);
    }

    /** A token with the same claims as [model], issued at [issuedAt] and valid for [lifetime]. */
    private String token(String model, Instant issuedAt, Duration lifetime) throws Exception {
        JsonNode claims = claims(model);
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder().issuer(ISSUER).issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(lifetime)).subject(claims.get("sub").asText())
                .claim("sid", claims.get("sid").asText()).claim("auth_time", claims.get("auth_time").asLong());
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), builder.build()))
                .getTokenValue();
    }

    private JsonNode claims(String token) throws Exception {
        String payload = token.split("\\.")[1];
        return objectMapper.readTree(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
