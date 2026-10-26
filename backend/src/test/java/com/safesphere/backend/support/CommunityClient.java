package com.safesphere.backend.support;

import static com.safesphere.backend.support.TestAccounts.bearer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Test helper for Phase 6/7 community and moderation calls through the real API (JWT, validation, JSON). */
public final class CommunityClient {

    /** A registered account: its JWT, its id (for direct repository checks only) and its e-mail. */
    public record Account(String token, long id, String email) {
    }

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    public CommunityClient(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    public Account account() throws Exception {
        String email = "phase7." + UUID.randomUUID() + "@example.com";
        JsonNode response = TestAccounts.registerVerifiedAndLogin(mockMvc, objectMapper, "Phase Seven", email);
        return new Account(response.get("accessToken").asText(), response.get("user").get("id").asLong(), email);
    }

    public ResultActions report(Account who, String category, String cell) throws Exception {
        return send(post("/api/community/reports"), who, Map.of("category", category, "cell", cell));
    }

    public ResultActions contribute(Account who, String signalId, String kind, String near) throws Exception {
        return send(post("/api/community/signals/" + signalId + "/contributions"), who, Map.of("kind", kind, "near", near));
    }

    public ResultActions flag(Account who, String signalId, String reason, String near) throws Exception {
        return send(post("/api/community/signals/" + signalId + "/flags"), who, Map.of("reason", reason, "near", near));
    }

    public JsonNode area(Account who, String area) throws Exception {
        return json(getAs(who, "/api/community/area?cell=" + area).andExpect(status().isOk()));
    }

    public String onlySignalId(Account who, String area) throws Exception {
        JsonNode signals = area(who, area).get("signals");
        assertEquals(1, signals.size(), "expected exactly one visible signal");
        return signals.get(0).get("id").asText();
    }

    public ResultActions getAs(Account who, String url) throws Exception {
        return mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, bearer(who.token())));
    }

    public ResultActions postAs(Account who, String url, Object body) throws Exception {
        return send(post(url), who, body);
    }

    public ResultActions putAs(Account who, String url, Object body) throws Exception {
        return send(put(url), who, body);
    }

    public ResultActions deleteAs(Account who, String url) throws Exception {
        return mockMvc.perform(delete(url).header(HttpHeaders.AUTHORIZATION, bearer(who.token())));
    }

    public JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                               Account who, Object body) throws Exception {
        return mockMvc.perform(builder.header(HttpHeaders.AUTHORIZATION, bearer(who.token()))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }
}
