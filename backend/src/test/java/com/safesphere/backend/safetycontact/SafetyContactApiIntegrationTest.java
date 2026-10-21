package com.safesphere.backend.safetycontact;

import static com.safesphere.backend.support.TestAccounts.bearer;
import static com.safesphere.backend.support.TestAccounts.registerAndGetToken;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.emergency.repository.EmergencyEventRepository;
import com.safesphere.backend.safetycontact.repository.SafetyContactRepository;
import com.safesphere.backend.user.repository.UserRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end tests of /api/safety-contacts with real security, real JWTs and an in-memory database.
 * Two users (A and B) are used throughout to prove that the backend itself enforces ownership.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SafetyContactApiIntegrationTest {

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

    // ------------------------------------------------------------------ create / read

    @Test
    void create_storesContactForCallerWithNormalizedPhone() throws Exception {
        mockMvc.perform(post("/api/safety-contacts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Asha Devi", "+91 98765-43210", "asha@example.com", "FAMILY", 1, true, true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Asha Devi"))
                .andExpect(jsonPath("$.phoneNumber").value("+919876543210"))
                .andExpect(jsonPath("$.relationship").value("FAMILY"))
                .andExpect(jsonPath("$.priority").value(1))
                .andExpect(jsonPath("$.primary").value(true))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.userId").doesNotExist());
    }

    @Test
    void create_blankEmailIsStoredAsNoEmail_andOptionalFlagsDefault() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Ravi");
        body.put("phoneNumber", "9876500001");
        body.put("email", "");
        body.put("relationship", "FRIEND");
        body.put("priority", 3);

        mockMvc.perform(post("/api/safety-contacts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.primary").value(false))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void list_returnsOnlyCallersContacts_primaryFirstThenByPriority() throws Exception {
        create(tokenA, "Low priority", "9000000001", "FRIEND", 4, false, true);
        create(tokenA, "Primary", "9000000002", "FAMILY", 2, true, true);
        create(tokenA, "High priority", "9000000003", "PARTNER", 1, false, true);
        create(tokenB, "Belongs to B", "9000000004", "FRIEND", 1, true, true);

        mockMvc.perform(get("/api/safety-contacts").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].name").value("Primary"))
                .andExpect(jsonPath("$[1].name").value("High priority"))
                .andExpect(jsonPath("$[2].name").value("Low priority"));
    }

    @Test
    void get_byId_returnsOwnContact() throws Exception {
        long id = create(tokenA, "Asha", "9000000001", "FAMILY", 1, false, true);

        mockMvc.perform(get("/api/safety-contacts/" + id).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Asha"));
    }

    // ------------------------------------------------------------------ update / delete

    @Test
    void update_changesFieldsAndPersists() throws Exception {
        long id = create(tokenA, "Asha", "9000000001", "FAMILY", 1, false, true);

        mockMvc.perform(put("/api/safety-contacts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Asha Devi", "9000000009", "asha@example.com", "PARTNER", 2, false, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Asha Devi"))
                .andExpect(jsonPath("$.phoneNumber").value("9000000009"))
                .andExpect(jsonPath("$.relationship").value("PARTNER"))
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(get("/api/safety-contacts/" + id).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.name").value("Asha Devi"))
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void delete_removesContact() throws Exception {
        long id = create(tokenA, "Asha", "9000000001", "FAMILY", 1, false, true);

        mockMvc.perform(delete("/api/safety-contacts/" + id).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/safety-contacts/" + id).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/safety-contacts").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ------------------------------------------------------------------ authentication

    @Test
    void everyEndpointRequiresAToken() throws Exception {
        String body = json("Asha", "9000000001", null, "FAMILY", 1, false, true);
        mockMvc.perform(get("/api/safety-contacts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/safety-contacts/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/safety-contacts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/safety-contacts/1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/safety-contacts/1")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ ownership

    @Test
    void userB_cannotSeeChangeOrDeleteUserAsContact() throws Exception {
        long idOfA = create(tokenA, "A's contact", "9000000001", "FAMILY", 1, false, true);

        mockMvc.perform(get("/api/safety-contacts/" + idOfA).header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/safety-contacts/" + idOfA)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Hacked", "9111111111", null, "OTHER", 5, false, true)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/safety-contacts/" + idOfA).header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/safety-contacts").header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(jsonPath("$", hasSize(0)));

        // A's contact is untouched.
        mockMvc.perform(get("/api/safety-contacts/" + idOfA).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("A's contact"));
    }

    @Test
    void ownerComesFromTheToken_aUserIdInTheBodyIsIgnored() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", 987654);
        body.put("name", "Asha");
        body.put("phoneNumber", "9000000001");
        body.put("relationship", "FAMILY");
        body.put("priority", 1);

        mockMvc.perform(post("/api/safety-contacts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/safety-contacts").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$", hasSize(1)));
        assertEquals(1, contactRepository.count());
        assertEquals(0, contactRepository.countByUserId(987654L));
    }

    // ------------------------------------------------------------------ validation

    @Test
    void invalidPhone_returns400WithFieldError() throws Exception {
        mockMvc.perform(post("/api/safety-contacts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Asha", "not-a-phone", null, "FAMILY", 1, false, true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.phoneNumber").exists());
    }

    @Test
    void phoneWithTooFewDigitsIsRejectedByTheService() throws Exception {
        // "(--) ..." passes the character pattern but has only 3 digits after cleaning.
        mockMvc.perform(post("/api/safety-contacts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Asha", "( 12 ) - 3 ---", null, "FAMILY", 1, false, true)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidEmailAndPriorityAndMissingFields_return400() throws Exception {
        mockMvc.perform(post("/api/safety-contacts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Asha", "9000000001", "not-an-email", "FAMILY", 9, false, true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.priority").exists());

        mockMvc.perform(post("/api/safety-contacts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.phoneNumber").exists())
                .andExpect(jsonPath("$.fieldErrors.relationship").exists())
                .andExpect(jsonPath("$.fieldErrors.priority").exists());
    }

    // ------------------------------------------------------------------ duplicates and limits

    @Test
    void sameNumberTwice_returns409_evenWhenFormattedDifferently() throws Exception {
        create(tokenA, "Asha", "+919876543210", "FAMILY", 1, false, true);

        mockMvc.perform(post("/api/safety-contacts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Asha again", "+91 98765 43210", null, "FRIEND", 2, false, true)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This phone number is already in your Safety Circle"));
    }

    @Test
    void anotherUserMayUseTheSameNumber() throws Exception {
        create(tokenA, "Asha", "+919876543210", "FAMILY", 1, false, true);

        mockMvc.perform(post("/api/safety-contacts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Asha", "+919876543210", null, "FAMILY", 1, false, true)))
                .andExpect(status().isCreated());
    }

    @Test
    void updatingToANumberUsedByAnotherOfMyContacts_returns409_butKeepingMyOwnNumberIsFine() throws Exception {
        long first = create(tokenA, "First", "9000000001", "FAMILY", 1, false, true);
        create(tokenA, "Second", "9000000002", "FAMILY", 2, false, true);

        mockMvc.perform(put("/api/safety-contacts/" + first)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("First", "9000000002", null, "FAMILY", 1, false, true)))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/api/safety-contacts/" + first)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("First (renamed)", "9000000001", null, "FAMILY", 1, false, true)))
                .andExpect(status().isOk());
    }

    @Test
    void circleIsLimitedToTenPeople() throws Exception {
        for (int i = 0; i < 10; i++) {
            create(tokenA, "Person " + i, "900000010" + i, "FRIEND", 3, false, true);
        }

        mockMvc.perform(post("/api/safety-contacts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("One too many", "9000009999", null, "FRIEND", 3, false, true)))
                .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ primary contact

    @Test
    void markingANewPrimaryUnsetsThePreviousOne() throws Exception {
        long first = create(tokenA, "First", "9000000001", "FAMILY", 1, true, true);
        long second = create(tokenA, "Second", "9000000002", "FAMILY", 2, true, true);

        mockMvc.perform(get("/api/safety-contacts/" + first).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.primary").value(false));
        mockMvc.perform(get("/api/safety-contacts/" + second).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.primary").value(true));
        assertEquals(1, contactRepository.findAll().stream().filter(c -> c.isPrimaryContact()).count());
    }

    @Test
    void makingAnExistingContactPrimaryMovesThePrimaryFlag() throws Exception {
        long first = create(tokenA, "First", "9000000001", "FAMILY", 1, true, true);
        long second = create(tokenA, "Second", "9000000002", "FAMILY", 2, false, true);

        mockMvc.perform(put("/api/safety-contacts/" + second)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Second", "9000000002", null, "FAMILY", 2, true, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primary").value(true));

        mockMvc.perform(get("/api/safety-contacts/" + first).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.primary").value(false));
    }

    @Test
    void eachUserHasTheirOwnPrimary() throws Exception {
        create(tokenA, "A's primary", "9000000001", "FAMILY", 1, true, true);
        create(tokenB, "B's primary", "9000000002", "FAMILY", 1, true, true);

        assertEquals(2, contactRepository.findAll().stream().filter(c -> c.isPrimaryContact()).count());
    }

    @Test
    void aDisabledContactCanNeverBePrimary() throws Exception {
        long id = create(tokenA, "Asha", "9000000001", "FAMILY", 1, true, true);

        mockMvc.perform(put("/api/safety-contacts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Asha", "9000000001", null, "FAMILY", 1, true, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.primary").value(false));
    }

    // ------------------------------------------------------------------ helpers

    private long create(String token, String name, String phone, String relationship, int priority,
                        boolean primary, boolean enabled) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/safety-contacts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(name, phone, null, relationship, priority, primary, enabled)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private String json(String name, String phone, String email, String relationship, Integer priority,
                        boolean primary, boolean enabled) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("phoneNumber", phone);
        body.put("email", email);
        body.put("relationship", relationship);
        body.put("priority", priority);
        body.put("primary", primary);
        body.put("enabled", enabled);
        return objectMapper.writeValueAsString(body);
    }
}
