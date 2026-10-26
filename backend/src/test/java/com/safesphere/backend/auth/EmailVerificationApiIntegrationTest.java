package com.safesphere.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.auth.mail.VerificationMailer;
import com.safesphere.backend.auth.service.EmailVerificationService;
import com.safesphere.backend.auth.service.RefreshSecrets;
import com.safesphere.backend.common.exception.VerificationLinkInvalidException;
import com.safesphere.backend.security.JwtService;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.TestAccounts;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * V25 e-mail ownership verification through the real API: sign-up without a session, the e-mailed link (captured
 * from the mailer, never from an API answer), single use, expiry, replacement by a resend, replay, concurrency,
 * enumeration-safe resend, limits, the login gate and the defence-in-depth refusals.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class EmailVerificationApiIntegrationTest {

    private static final String PASSWORD = TestAccounts.PASSWORD;
    private static final String BASE = "https://kurinji.example/verify-email#t=";
    private static final Pattern LINK_TOKEN = Pattern.compile("https://kurinji\\.example/verify-email#t=([A-Za-z0-9_-]{43})");
    private static final AtomicInteger ADDRESSES = new AtomicInteger(1);

    /** Records every message instead of sending it (the mailer runs on the background send thread). */
    static final class CapturingMailer implements VerificationMailer {
        record Sent(String to, String link, Duration lifetime) {
        }

        final List<Sent> sent = new CopyOnWriteArrayList<>();

        @Override
        public boolean sendVerification(String toEmail, String link, Duration lifetime) {
            sent.add(new Sent(toEmail, link, lifetime));
            return true;
        }

        @Override
        public boolean configured() {
            return true;
        }

        List<Sent> to(String email) {
            return sent.stream().filter(s -> s.to().equals(email)).toList();
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        CapturingMailer capturingMailer() {
            return new CapturingMailer();
        }

        @Bean
        @Primary
        MutableClock verificationTestClock() {
            return new MutableClock();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CapturingMailer mailer;
    @Autowired private MutableClock clock;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EmailVerificationService verification;
    @Autowired private JwtService jwtService;

    @BeforeEach
    void reset() {
        clock.reset();
    }

    // ---- Registration ----------------------------------------------------------------------------------------

    @Test
    void signUp_createsAnUnverifiedAccount_sendsOneEmail_andTheTokenIsOnlyInTheLink() throws Exception {
        String email = email();
        String answer = register(email).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VERIFICATION_REQUIRED"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String token = awaitToken(email, 1);
        CapturingMailer.Sent sent = mailer.to(email).get(0);
        assertThat(sent.link()).isEqualTo(BASE + token);
        assertThat(sent.lifetime()).isEqualTo(Duration.ofMinutes(60));
        assertThat(answer).doesNotContain(token);
        assertThat(users.findByEmail(email).orElseThrow().isEmailVerified()).isFalse();
        String stored = jdbc.queryForObject("SELECT token_hash FROM email_verification_tokens WHERE user_id = ?",
                String.class, userId(email));
        assertThat(stored).isNotEqualTo(token).isEqualTo(RefreshSecrets.hash(token)).matches("[0-9a-f]{64}");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_sessions WHERE user_id = ?", Integer.class,
                userId(email))).isZero();
    }

    // ---- Login gate ------------------------------------------------------------------------------------------

    @Test
    void anUnverifiedAccount_getsNoSession_rightPasswordSays403EmailNotVerified_wrongPasswordStaysGeneric()
            throws Exception {
        String email = email();
        register(email).andExpect(status().isCreated());
        login(email, PASSWORD).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"))
                .andExpect(jsonPath("$.message").value("Please verify your email before signing in"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        login(email, "not-the-password").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"))
                .andExpect(jsonPath("$.code").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_sessions WHERE user_id = ?", Integer.class,
                userId(email))).isZero();
    }

    @Test
    void aTokenForAnUnverifiedAccount_isRefusedEverywhere_defenceInDepth() throws Exception {
        String email = email();
        register(email).andExpect(status().isCreated());
        User user = users.findByEmail(email).orElseThrow();
        String forged = jwtService.issueToken(user).value();
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, TestAccounts.bearer(forged)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        org.hamcrest.Matchers.containsString("account_unavailable")));
    }

    // ---- Verification ----------------------------------------------------------------------------------------

    @Test
    void theLink_verifiesOnce_thenSignInWorks_andTheSameLinkNeverWorksAgain() throws Exception {
        String email = email();
        register(email).andExpect(status().isCreated());
        String token = awaitToken(email, 1);

        verify(token).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VERIFIED"));
        assertThat(users.findByEmail(email).orElseThrow().isEmailVerified()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_verification_tokens WHERE user_id = ?",
                Integer.class, userId(email))).isZero();
        login(email, PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty());

        verify(token).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFICATION_LINK_INVALID"));
    }

    @Test
    void anExpiredLink_fails_andTheAccountStaysUnverified() throws Exception {
        String email = email();
        register(email).andExpect(status().isCreated());
        String token = awaitToken(email, 1);
        clock.advance(Duration.ofMinutes(61));
        verify(token).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFICATION_LINK_INVALID"));
        assertThat(users.findByEmail(email).orElseThrow().isEmailVerified()).isFalse();
        login(email, PASSWORD).andExpect(status().isForbidden());
    }

    @Test
    void unknownAndMalformedLinks_allGetTheSameAnswer() throws Exception {
        String unknown = verify(RefreshSecrets.newSecret()).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        String malformed = verify("not-a-token").andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(message(unknown)).isEqualTo(message(malformed)).isEqualTo("This link is not valid or has expired");
        verify("").andExpect(status().isBadRequest());
    }

    @Test
    void twoSimultaneousClicks_onOneLink_verifyExactlyOnce() throws Exception {
        String email = email();
        register(email).andExpect(status().isCreated());
        String token = awaitToken(email, 1);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String address = address();
            results.add(pool.submit(() -> {
                start.await();
                try {
                    verification.verify(token, address);
                    return true;
                } catch (VerificationLinkInvalidException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int successes = 0;
        for (Future<Boolean> result : results) if (result.get(10, TimeUnit.SECONDS)) successes++;
        pool.shutdown();
        assertThat(successes).isEqualTo(1);
        assertThat(users.findByEmail(email).orElseThrow().isEmailVerified()).isTrue();
    }

    @Test
    void failedLinkAttempts_areLimitedPerAddress() throws Exception {
        String address = address();
        for (int i = 0; i < 30; i++) verifyFrom(RefreshSecrets.newSecret(), address).andExpect(status().isBadRequest());
        verifyFrom(RefreshSecrets.newSecret(), address).andExpect(status().isTooManyRequests());
        verifyFrom(RefreshSecrets.newSecret(), address()).andExpect(status().isBadRequest());
    }

    @Test
    void tokens_are256RandomBits_andDifferEveryTime() throws Exception {
        String first = email();
        String second = email();
        register(first).andExpect(status().isCreated());
        register(second).andExpect(status().isCreated());
        String a = awaitToken(first, 1);
        String b = awaitToken(second, 1);
        assertThat(a).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        assertThat(java.util.Base64.getUrlDecoder().decode(a)).hasSize(32);
        assertThat(a).isNotEqualTo(b);
    }

    // ---- Resend ----------------------------------------------------------------------------------------------

    @Test
    void resend_replacesTheToken_theOldLinkDies_theNewOneWorks() throws Exception {
        String email = email();
        register(email).andExpect(status().isCreated());
        String first = awaitToken(email, 1);
        resend(email, PASSWORD, address()).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RECEIVED"));
        String second = awaitToken(email, 2);
        assertThat(second).isNotEqualTo(first);

        verify(first).andExpect(status().isBadRequest());
        verify(second).andExpect(status().isOk());
        login(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void resend_givesTheSameAnswerForEverything_andSendsOnlyForTheRightPasswordOfAnUnverifiedAccount()
            throws Exception {
        String unverified = email();
        register(unverified).andExpect(status().isCreated());
        awaitToken(unverified, 1);
        String verified = email();
        TestAccounts.registerVerifiedAndLogin(mockMvc, objectMapper, "Verified", verified);
        int before = mailer.sent.size();

        String wrongPassword = body(resend(unverified, "not-the-password", address()).andExpect(status().isAccepted()));
        String unknown = body(resend(email(), PASSWORD, address()).andExpect(status().isAccepted()));
        String alreadyVerified = body(resend(verified, PASSWORD, address()).andExpect(status().isAccepted()));
        assertThat(wrongPassword).isEqualTo(unknown).isEqualTo(alreadyVerified).isEqualTo("{\"status\":\"RECEIVED\"}");
        Thread.sleep(300);
        assertThat(mailer.sent.size()).isEqualTo(before);
    }

    @Test
    void resend_isLimitedPerAccount_alsoForUnknownAddresses() throws Exception {
        String email = email();
        register(email).andExpect(status().isCreated());
        for (int i = 0; i < 3; i++) resend(email, PASSWORD, address()).andExpect(status().isAccepted());
        resend(email, PASSWORD, address()).andExpect(status().isTooManyRequests());

        String nobody = email();
        for (int i = 0; i < 3; i++) resend(nobody, PASSWORD, address()).andExpect(status().isAccepted());
        resend(nobody, PASSWORD, address()).andExpect(status().isTooManyRequests());
    }

    @Test
    void resend_isLimitedPerAddress() throws Exception {
        String address = address();
        for (int i = 0; i < 20; i++) resend(email(), PASSWORD, address).andExpect(status().isAccepted());
        resend(email(), PASSWORD, address).andExpect(status().isTooManyRequests());
    }

    // ---- Squatting and clean-up ------------------------------------------------------------------------------

    @Test
    void anAddressHeldByAnUnverifiedSignUp_isFreedWhenItsLinkExpires_notBefore() throws Exception {
        String email = email();
        register(email).andExpect(status().isCreated());
        String oldToken = awaitToken(email, 1);
        long oldId = userId(email);
        register(email).andExpect(status().isConflict());

        clock.advance(Duration.ofMinutes(61));
        register(email).andExpect(status().isCreated());
        assertThat(userId(email)).isNotEqualTo(oldId);
        verify(oldToken).andExpect(status().isBadRequest());
        verify(awaitToken(email, 2)).andExpect(status().isOk());
    }

    @Test
    void aVerifiedAddress_isNeverReplacedBySignUp() throws Exception {
        String email = email();
        TestAccounts.registerVerifiedAndLogin(mockMvc, objectMapper, "Owner", email);
        clock.advance(Duration.ofDays(30));
        register(email).andExpect(status().isConflict());
    }

    @Test
    void theCleanUp_removesAccountsNeverVerified_afterSevenDays_andKeepsVerifiedOnes() throws Exception {
        String unverified = email();
        register(unverified).andExpect(status().isCreated());
        String verified = email();
        TestAccounts.registerVerifiedAndLogin(mockMvc, objectMapper, "Kept", verified);

        clock.advance(Duration.ofDays(6));
        cleanUpAll();
        assertThat(users.findByEmail(unverified)).isPresent();

        clock.advance(Duration.ofDays(2));
        cleanUpAll();
        assertThat(users.findByEmail(unverified)).isEmpty();
        assertThat(users.findByEmail(verified)).isPresent();
    }

    // ---- Secrets stay secret ---------------------------------------------------------------------------------

    @Test
    void neitherTheTokenNorTheLinkIsEverLogged(CapturedOutput output) throws Exception {
        String email = email();
        register(email).andExpect(status().isCreated());
        String first = awaitToken(email, 1);
        resend(email, PASSWORD, address()).andExpect(status().isAccepted());
        String second = awaitToken(email, 2);
        verify(first).andExpect(status().isBadRequest());
        verify(second).andExpect(status().isOk());
        assertThat(output.getAll()).doesNotContain(first).doesNotContain(second).doesNotContain(BASE)
                .doesNotContain(RefreshSecrets.hash(second));
    }

    @Test
    void theVerificationPage_isStatic_strictlyLocked_andNeverCached() throws Exception {
        mockMvc.perform(get("/verify-email"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("default-src 'none'")))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(content -> assertThat(content.getResponse().getContentAsString())
                        .contains("Confirm my email").doesNotContain("<script>"));
        mockMvc.perform(get("/verify-email.js")).andExpect(status().isOk())
                .andExpect(content -> assertThat(content.getResponse().getContentAsString())
                        .contains("/api/auth/verify-email").contains("replaceState"));
        mockMvc.perform(get("/verify-email.css")).andExpect(status().isOk());
    }

    // ---- Helpers ---------------------------------------------------------------------------------------------

    private static String email() {
        return "verify." + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
    }

    private static String address() {
        int n = ADDRESSES.getAndIncrement();
        return "10.25." + (n / 256 % 256) + "." + (n % 256);
    }

    /** The shared test database holds other classes' unverified sign-ups too; the job works in batches. */
    private void cleanUpAll() {
        for (int i = 0; i < 100 && verification.cleanUp() > 0; i++) {
            // next batch
        }
    }

    private long userId(String email) {
        return users.findByEmail(email).orElseThrow().getId();
    }

    /** The token of the [count]-th message to [email] (the mailer runs on the background send thread). */
    private String awaitToken(String email, int count) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        while (mailer.to(email).size() < count && Instant.now().isBefore(deadline)) Thread.sleep(20);
        List<CapturingMailer.Sent> sent = mailer.to(email);
        assertThat(sent).hasSizeGreaterThanOrEqualTo(count);
        Matcher matcher = LINK_TOKEN.matcher(sent.get(count - 1).link());
        assertThat(matcher.matches()).isTrue();
        return matcher.group(1);
    }

    private ResultActions register(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", "Verify Test", "email", email,
                        "password", PASSWORD, "dateOfBirth", TestAccounts.ADULT_BIRTH_DATE)))
                .with(request -> {
                    request.setRemoteAddr(address());
                    return request;
                }));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private ResultActions verify(String token) throws Exception {
        return verifyFrom(token, address());
    }

    private ResultActions verifyFrom(String token, String address) throws Exception {
        return mockMvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("token", token)))
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                }));
    }

    private ResultActions resend(String email, String password, String address) throws Exception {
        return mockMvc.perform(post("/api/auth/verification/resend").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password)))
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                }));
    }

    private String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private String message(String json) throws Exception {
        return objectMapper.readTree(json).get("message").asText();
    }
}
