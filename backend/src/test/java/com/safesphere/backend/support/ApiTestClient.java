package com.safesphere.backend.support;

import static com.safesphere.backend.support.TestAccounts.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Test helper: signed-in calls through the real API (JWT, validation, JSON). Until N1 this was CommunityClient and
 * also carried the Nearby signal calls.
 */
public final class ApiTestClient {

    /** A registered account: its JWT, its id (for direct repository checks only) and its e-mail. */
    public record Account(String token, long id, String email) {
    }

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    public ApiTestClient(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    public Account account() throws Exception {
        String email = "phase7." + UUID.randomUUID() + "@example.com";
        JsonNode response = TestAccounts.registerVerifiedAndLogin(mockMvc, objectMapper, "Phase Seven", email);
        return new Account(response.get("accessToken").asText(), response.get("user").get("id").asLong(), email);
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
