package com.safesphere.backend.evidence;

import static com.safesphere.backend.support.TestAccounts.PASSWORD;
import static com.safesphere.backend.support.TestAccounts.bearer;
import static com.safesphere.backend.support.TestAccounts.registerAndGetToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.emergency.repository.EmergencyEventRepository;
import com.safesphere.backend.evidence.repository.CustodyEventRepository;
import com.safesphere.backend.evidence.repository.EvidenceItemRepository;
import com.safesphere.backend.safetycontact.repository.SafetyContactRepository;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The deletion lock is temporary: after the failure window the right password works again. Runs with a 2-second
 * window, 2 allowed failures and no protection period, so the test does not wait 15 minutes or edit custody rows
 * (which would break the hash chain).
 */
@SpringBootTest(properties = {
        "app.evidence.delete-protection=0s",
        "app.evidence.delete-max-failures=2",
        "app.evidence.delete-failure-window=2s"
})
@AutoConfigureMockMvc
class EvidenceDeletionLockExpiryTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private SafetyContactRepository contactRepository;
    @Autowired private EmergencyEventRepository emergencyRepository;
    @Autowired private EvidenceItemRepository evidenceRepository;
    @Autowired private CustodyEventRepository custodyRepository;

    @Test
    void theLockEndsAfterTheWindow() throws Exception {
        custodyRepository.deleteAll();
        evidenceRepository.deleteAll();
        contactRepository.deleteAll();
        emergencyRepository.deleteAll();
        userRepository.deleteAll();
        String token = registerAndGetToken(mockMvc, objectMapper, "lock.expiry@example.com");

        long emergency = objectMapper.readTree(mockMvc.perform(post("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
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
        long id = objectMapper.readTree(mockMvc.perform(post("/api/emergencies/" + emergency + "/evidence")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        mockMvc.perform(put("/api/emergencies/" + emergency + "/resolve").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());

        delete(token, id, "wrong-1").andExpect(status().isConflict());
        delete(token, id, "wrong-2").andExpect(status().isConflict());
        delete(token, id, PASSWORD).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many wrong passwords. Deleting evidence is locked; try again in 2 seconds"));

        Thread.sleep(2_500);
        delete(token, id, PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELETED"));
    }

    private ResultActions delete(String token, long id, String password) throws Exception {
        return mockMvc.perform(post("/api/evidence/" + id + "/delete").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("password", password))));
    }
}
