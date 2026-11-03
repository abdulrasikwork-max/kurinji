package com.safesphere.backend.explore;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.support.TestAccounts;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 17G-3: with the DEFAULT configuration (mode OFF) nothing is available and nothing is ever called: the whole
 * feature is dormant until an operator switches it on. No stub is running here, so any outbound call would show as a failure.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UnderstandingOffApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void byDefault_theFeatureIsOff() throws Exception {
        String token = TestAccounts.registerAndGetToken(mockMvc, objectMapper, "off." + UUID.randomUUID() + "@example.com");
        mockMvc.perform(post("/api/explore/understand").header("Authorization", TestAccounts.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"quiet cafe\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("UNDERSTANDING_OFF"));
        mockMvc.perform(get("/api/explore/understanding").header("Authorization", TestAccounts.bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false));
    }
}
