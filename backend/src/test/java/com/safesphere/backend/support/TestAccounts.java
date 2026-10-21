package com.safesphere.backend.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Test helper: creates a real account through the real endpoint and returns its JWT. */
public final class TestAccounts {

    public static final String PASSWORD = "correct-horse-battery";

    private TestAccounts() {
    }

    public static String registerAndGetToken(MockMvc mockMvc, ObjectMapper objectMapper, String email) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("name", "Test User", "email", email, "password", PASSWORD));
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }
}
