package com.safesphere.backend.emergency;

import static com.safesphere.backend.support.TestAccounts.bearer;
import static com.safesphere.backend.support.TestAccounts.registerAndGetToken;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.emergency.repository.EmergencyEventRepository;
import com.safesphere.backend.safetycontact.repository.SafetyContactRepository;
import com.safesphere.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** End-to-end tests of /api/emergencies: state changes, history and ownership between two users. */
@SpringBootTest
@AutoConfigureMockMvc
class EmergencyApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private SafetyContactRepository contactRepository;
    @Autowired private EmergencyEventRepository emergencyRepository;

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() throws Exception {
        contactRepository.deleteAll();
        emergencyRepository.deleteAll();
        userRepository.deleteAll();
        tokenA = registerAndGetToken(mockMvc, objectMapper, "user.a@example.com");
        tokenB = registerAndGetToken(mockMvc, objectMapper, "user.b@example.com");
    }

    @Test
    void start_createsAnActiveManualEmergency() throws Exception {
        mockMvc.perform(post("/api/emergencies")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"triggerType\":\"MANUAL\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.triggerType").value("MANUAL"))
                .andExpect(jsonPath("$.startedAt").exists())
                .andExpect(jsonPath("$.endedAt").doesNotExist());
    }

    @Test
    void start_withoutABodyDefaultsToManual() throws Exception {
        mockMvc.perform(post("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.triggerType").value("MANUAL"));
    }

    @Test
    void start_withATriggerThatDoesNotExistYet_returns400() throws Exception {
        mockMvc.perform(post("/api/emergencies")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"triggerType\":\"SHAKE\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/emergencies")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"triggerType\":\"TELEPATHY\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void secondStartWhileOneIsActive_returns409() throws Exception {
        start(tokenA);

        mockMvc.perform(post("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("An emergency is already active"));
    }

    @Test
    void cancel_endsTheEmergencyAsCancelled_andOnlyOnce() throws Exception {
        long id = start(tokenA);

        mockMvc.perform(put("/api/emergencies/" + id + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.endedAt").exists());

        mockMvc.perform(put("/api/emergencies/" + id + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/api/emergencies/" + id + "/resolve").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isConflict());
    }

    @Test
    void resolve_endsTheEmergencyAsResolved_andANewOneCanThenStart() throws Exception {
        long id = start(tokenA);

        mockMvc.perform(put("/api/emergencies/" + id + "/resolve").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.endedAt").exists());

        mockMvc.perform(post("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isCreated());
    }

    @Test
    void history_isNewestFirst_andContainsEveryStatus() throws Exception {
        long first = start(tokenA);
        mockMvc.perform(put("/api/emergencies/" + first + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)));
        long second = start(tokenA);
        mockMvc.perform(put("/api/emergencies/" + second + "/resolve").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)));
        start(tokenA);

        mockMvc.perform(get("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[1].status").value("RESOLVED"))
                .andExpect(jsonPath("$[2].status").value("CANCELLED"));
    }

    @Test
    void get_byId_returnsTheEvent() throws Exception {
        long id = start(tokenA);

        mockMvc.perform(get("/api/emergencies/" + id).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void userB_cannotSeeOrEndUserAsEmergencies() throws Exception {
        long idOfA = start(tokenA);

        mockMvc.perform(get("/api/emergencies/" + idOfA).header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/emergencies/" + idOfA + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/emergencies/" + idOfA + "/resolve").header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(jsonPath("$", hasSize(0)));

        // A's emergency is still ACTIVE, and B can have an emergency of their own at the same time.
        mockMvc.perform(get("/api/emergencies/" + idOfA).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(post("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void everyEndpointRequiresAToken() throws Exception {
        mockMvc.perform(post("/api/emergencies")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/emergencies")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/emergencies/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/emergencies/1/cancel")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/emergencies/1/resolve")).andExpect(status().isUnauthorized());
    }

    private long start(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }
}
