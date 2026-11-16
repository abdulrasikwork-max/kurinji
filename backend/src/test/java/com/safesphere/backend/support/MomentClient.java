package com.safesphere.backend.support;

import static com.safesphere.backend.support.TestAccounts.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.support.SocialClient.Person;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Test helper (Phase N4): the moment endpoints through the real API (JWT, validation, JSON). */
public final class MomentClient {

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    public MomentClient(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    public static Map<String, Object> body(String kind, String audience, String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", kind);
        body.put("audience", audience);
        body.put("text", text);
        return body;
    }

    public ResultActions create(String token, Map<String, ?> body) throws Exception {
        return send(post("/api/moments"), token, body);
    }

    public ResultActions create(Person who, Map<String, ?> body) throws Exception {
        return create(who.token(), body);
    }

    /** Shares a Say and returns its id. */
    public String say(Person who, String audience, String text) throws Exception {
        return json(create(who, body("SAY", audience, text)).andExpect(status().isCreated())).get("id").asText();
    }

    public ResultActions today(Person who, String people, String cursor) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/moments/today");
        if (people != null) request.param("people", people);
        if (cursor != null) request.param("cursor", cursor);
        return send(request, who.token(), null);
    }

    /** The ids on one page of [who]'s Today, in order. */
    public List<String> todayIds(Person who, String people) throws Exception {
        return ids(json(today(who, people, null).andExpect(status().isOk())).get("moments"));
    }

    /** Every id on [who]'s Today, following the cursor to the end of the wall. */
    public List<String> wholeToday(Person who, String people) throws Exception {
        List<String> all = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = json(today(who, people, cursor).andExpect(status().isOk()));
            all.addAll(ids(page.get("moments")));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
        } while (cursor != null);
        return all;
    }

    public ResultActions get_(Person who, String id) throws Exception {
        return send(get("/api/moments/{id}", id), who.token(), null);
    }

    public ResultActions mine(Person who) throws Exception {
        return send(get("/api/moments/mine"), who.token(), null);
    }

    public ResultActions delete_(Person who, String id) throws Exception {
        return send(delete("/api/moments/{id}", id), who.token(), null);
    }

    public ResultActions keep(Person who, String id) throws Exception {
        return send(put("/api/moments/{id}/kept", id), who.token(), null);
    }

    public ResultActions unkeep(Person who, String id) throws Exception {
        return send(delete("/api/moments/{id}/kept", id), who.token(), null);
    }

    /** N4-B2: uploads [bytes] as a moment photo. */
    public ResultActions upload(Person who, byte[] bytes, String contentType) throws Exception {
        return mockMvc.perform(post("/api/moments/media").header(HttpHeaders.AUTHORIZATION, bearer(who.token()))
                .contentType(contentType).content(bytes));
    }

    public String uploadPhoto(Person who, byte[] jpeg) throws Exception {
        return json(upload(who, jpeg, "image/jpeg").andExpect(status().isCreated())).get("mediaId").asText();
    }

    public ResultActions deleteUpload(Person who, String mediaId) throws Exception {
        return send(delete("/api/moments/media/{id}", mediaId), who.token(), null);
    }

    public ResultActions photo(Person who, String momentId) throws Exception {
        return send(get("/api/moments/{id}/media", momentId), who.token(), null);
    }

    /** A moment body with a photo. */
    public static Map<String, Object> withPhoto(String kind, String audience, String text, String mediaId) {
        Map<String, Object> body = body(kind, audience, text);
        body.put("mediaId", mediaId);
        return body;
    }

    public ResultActions questions(Person admin) throws Exception {
        return send(get("/api/admin/questions"), admin.token(), null);
    }

    public ResultActions addQuestion(Person admin, String day, String text) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("day", day);
        body.put("text", text);
        return send(post("/api/admin/questions"), admin.token(), body);
    }

    public ResultActions deleteQuestion(Person admin, String id) throws Exception {
        return send(delete("/api/admin/questions/{id}", id), admin.token(), null);
    }

    public static List<String> ids(JsonNode moments) {
        List<String> ids = new ArrayList<>();
        for (JsonNode moment : moments) ids.add(moment.get("id").asText());
        return ids;
    }

    public JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private ResultActions send(MockHttpServletRequestBuilder builder, String token, Object body) throws Exception {
        builder.header(HttpHeaders.AUTHORIZATION, bearer(token));
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(builder);
    }
}
