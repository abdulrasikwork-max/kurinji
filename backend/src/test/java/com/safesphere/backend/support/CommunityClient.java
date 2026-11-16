package com.safesphere.backend.support;

import static com.safesphere.backend.support.TestAccounts.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Phase N3 test helper: the community endpoints through MockMvc (applicant, verifier, admin). */
public final class CommunityClient {

    /** A signed-up account: its token, id, test key (picks its number) and name. */
    public record Account(String token, long id, String key, String name) {
    }

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    public CommunityClient(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    /** An account WITHOUT a membership (the N3 starting point). */
    public Account unverified(String name) throws Exception {
        String key = "community." + UUID.randomUUID() + "@example.com";
        JsonNode answer = TestAccounts.registerUnverified(mockMvc, objectMapper, name, key);
        return new Account(answer.get("accessToken").asText(), answer.get("user").get("id").asLong(), key, name);
    }

    /** An account WITH an active MIET membership (as TestAccounts.registerVerifiedAndLogin makes it). */
    public Account member(String name) throws Exception {
        String key = "community." + UUID.randomUUID() + "@example.com";
        JsonNode answer = TestAccounts.registerVerifiedAndLogin(mockMvc, objectMapper, name, key);
        return new Account(answer.get("accessToken").asText(), answer.get("user").get("id").asLong(), key, name);
    }

    public ResultActions me(Account who) throws Exception {
        return perform(who, get("/api/community/me"), null);
    }

    public ResultActions programmes(Account who) throws Exception {
        return perform(who, get("/api/community/programmes"), null);
    }

    public ResultActions start(Account who, String method, String roll, String programme, Integer year)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        if (method != null) body.put("method", method);
        if (roll != null) body.put("rollNumber", roll);
        if (programme != null) body.put("programme", programme);
        if (year != null) body.put("graduationYear", year);
        return perform(who, post("/api/community/verification"), body);
    }

    public ResultActions newCode(Account who) throws Exception {
        return perform(who, post("/api/community/verification/code"), null);
    }

    public ResultActions uploadPhoto(Account who, byte[] image, MediaType type) throws Exception {
        return mockMvc.perform(put("/api/community/verification/photo")
                .header("Authorization", bearer(who.token())).contentType(type).content(image));
    }

    public ResultActions withdraw(Account who) throws Exception {
        return perform(who, delete("/api/community/verification"), null);
    }

    // ---- verifier / admin ---------------------------------------------------------------------------------------

    public ResultActions lookup(Account who, String code) throws Exception {
        return perform(who, post("/api/verifier/lookup"), Map.of("code", code));
    }

    public ResultActions decideInPerson(Account who, String ref, Map<String, Object> body) throws Exception {
        return perform(who, post("/api/verifier/requests/{ref}/decision", ref), body);
    }

    public ResultActions photoQueue(Account who) throws Exception {
        return perform(who, get("/api/admin/community/photo-requests"), null);
    }

    public ResultActions photo(Account who, String ref) throws Exception {
        return perform(who, get("/api/admin/community/requests/{ref}/photo", ref), null);
    }

    public ResultActions decideAsAdmin(Account who, String ref, Map<String, Object> body) throws Exception {
        return perform(who, post("/api/admin/community/requests/{ref}/decision", ref), body);
    }

    public ResultActions perform(Account who, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", bearer(who.token()));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(request);
    }

    public JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
