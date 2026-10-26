package com.safesphere.backend.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Test helper: creates a real account through the real endpoints and returns its JWT.
 *
 * V25: sign-up no longer signs in; the e-mail must be verified first. The full link flow is tested in
 * EmailVerificationApiIntegrationTest; everywhere else this helper marks the new account's e-mail verified directly
 * (as if its owner had opened the link), then signs in through POST /api/auth/login.
 */
public final class TestAccounts {

    public static final String PASSWORD = "correct-horse-battery";
    /** Phase 13A: every test account declares an adult date of birth (registration requires one). */
    public static final String ADULT_BIRTH_DATE = "1998-05-14";

    private TestAccounts() {
    }

    public static String registerAndGetToken(MockMvc mockMvc, ObjectMapper objectMapper, String email) throws Exception {
        return registerVerifiedAndLogin(mockMvc, objectMapper, "Test User", email).get("accessToken").asText();
    }

    /** Signs up, verifies the e-mail (directly), signs in; returns the login answer (tokens + user). */
    public static JsonNode registerVerifiedAndLogin(MockMvc mockMvc, ObjectMapper objectMapper, String name,
                                                    String email) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("name", name, "email", email, "password", PASSWORD,
                "dateOfBirth", ADULT_BIRTH_DATE));
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
        markVerified(mockMvc, email);
        String login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(login);
    }

    /** Marks an account's e-mail verified, as opening its link would (tests outside the verification flow). */
    public static void markVerified(MockMvc mockMvc, String email) {
        UserRepository users = mockMvc.getDispatcherServlet().getWebApplicationContext().getBean(UserRepository.class);
        var user = users.findByEmail(email.trim().toLowerCase(Locale.ROOT)).orElseThrow();
        user.markEmailVerified(LocalDateTime.now(ZoneOffset.UTC));
        users.saveAndFlush(user);
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }
}
