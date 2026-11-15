package com.safesphere.backend.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.auth.phone.PhoneCode;
import com.safesphere.backend.auth.phone.PhoneCodeRepository;
import com.safesphere.backend.auth.service.RefreshSecrets;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Test helper: creates a real account through the real sign-up endpoint and returns its tokens.
 *
 * Phase N2: an account is created only with a sign-up ticket, which the right texted code gives. The full code flow is
 * tested in PhoneSignInApiIntegrationTest; everywhere else this helper stores a ticket for the account's number
 * directly (as if its owner had typed the right code), then calls POST /api/auth/sign-up. A test key (an e-mail-like
 * string, kept from before N2 so every caller stays the same) picks the account's number ([phoneFor]).
 */
public final class TestAccounts {

    public static final String PASSWORD = "correct-horse-battery";
    /** Phase 13A: every test account declares an adult date of birth (sign-up requires one). */
    public static final String ADULT_BIRTH_DATE = "1998-05-14";

    private TestAccounts() {
    }

    public static String registerAndGetToken(MockMvc mockMvc, ObjectMapper objectMapper, String key) throws Exception {
        return registerVerifiedAndLogin(mockMvc, objectMapper, "Test User", key).get("accessToken").asText();
    }

    /** Signs up through POST /api/auth/sign-up with a ticket for [key]'s number; returns the answer (tokens + user). */
    public static JsonNode registerVerifiedAndLogin(MockMvc mockMvc, ObjectMapper objectMapper, String name,
                                                    String key) throws Exception {
        String ticket = seedTicket(mockMvc, e164For(key));
        String body = objectMapper.writeValueAsString(Map.of("ticket", ticket, "name", name,
                "dateOfBirth", ADULT_BIRTH_DATE));
        String answer = mockMvc.perform(post("/api/auth/sign-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(answer);
        legacyCredentials(mockMvc, json.get("user").get("id").asLong(), key);
        return json;
    }

    /**
     * Signs [key]'s existing account in again through the real code flow (start, the texted code, verify); returns the
     * session part of the answer. [previousRefreshToken] (may be null) is this phone's old sign-in (Phase 11 C1).
     * Needs CapturingOtpSenderConfig in the test's context, to read the texted code.
     */
    public static JsonNode signIn(MockMvc mockMvc, ObjectMapper objectMapper, String key, String previousRefreshToken)
            throws Exception {
        String phone = e164For(key);
        String started = mockMvc.perform(post("/api/auth/phone/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", phone))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String code = context(mockMvc).getBean(CapturingOtpSenderConfig.CapturingOtpSender.class).lastCodeFor(phone);
        Map<String, String> verify = new java.util.HashMap<>(Map.of(
                "challenge", objectMapper.readTree(started).get("challenge").asText(), "code", code));
        if (previousRefreshToken != null) verify.put("previousRefreshToken", previousRefreshToken);
        String answer = mockMvc.perform(post("/api/auth/phone/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verify)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(answer);
        if (!"SIGNED_IN".equals(json.get("status").asText())) throw new IllegalStateException("not signed in");
        return json.get("session");
    }

    /**
     * A sign-up ticket for [phoneE164], stored as the right sign-in code would leave it (only its hash is stored).
     * Returns the ticket itself.
     */
    public static String seedTicket(MockMvc mockMvc, String phoneE164) {
        ApplicationContext context = context(mockMvc);
        PhoneCodeRepository codes = context.getBean(PhoneCodeRepository.class);
        LocalDateTime now = LocalDateTime.ofInstant(context.getBean(Clock.class).instant(), ZoneOffset.UTC);
        codes.findByPurposeAndPhoneE164(PhoneCode.Purpose.SIGN_IN, phoneE164).ifPresent(codes::delete);
        String ticket = RefreshSecrets.newSecret();
        PhoneCode row = new PhoneCode(PhoneCode.Purpose.SIGN_IN, phoneE164, null,
                RefreshSecrets.hash(RefreshSecrets.newSecret()), now, now.plusMinutes(30));
        row.issueTicket(RefreshSecrets.hash(ticket), now.plusMinutes(30));
        codes.saveAndFlush(row);
        return ticket;
    }

    /**
     * N2 transition (removed in unit N2-B2): the account tests of the password-confirmed actions still send a password
     * and look accounts up by e-mail until B2 replaces both with a texted code; this writes the two old columns.
     */
    private static void legacyCredentials(MockMvc mockMvc, long userId, String key) {
        ApplicationContext context = context(mockMvc);
        String hash = context.getBean(PasswordEncoder.class).encode(PASSWORD);
        context.getBean(JdbcTemplate.class).update("UPDATE users SET email = ?, password_hash = ? WHERE id = ?",
                key.trim().toLowerCase(Locale.ROOT), hash, userId);
    }

    /** A valid Indian mobile number (10 digits) derived from [key], so every test account gets its own. */
    public static String phoneFor(String key) {
        long n = Math.floorMod(UUID.nameUUIDFromBytes(key.trim().toLowerCase(Locale.ROOT)
                .getBytes(StandardCharsets.UTF_8)).getMostSignificantBits(), 1_000_000_000L);
        return "9" + String.format("%09d", n);
    }

    /** [phoneFor] in the stored form (+91 and 10 digits). */
    public static String e164For(String key) {
        return "+91" + phoneFor(key);
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    private static ApplicationContext context(MockMvc mockMvc) {
        return mockMvc.getDispatcherServlet().getWebApplicationContext();
    }
}
