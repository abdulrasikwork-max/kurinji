package com.safesphere.backend.support;

import static com.safesphere.backend.support.TestAccounts.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.user.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Test helper (Phase 13A): people with profiles, and the social endpoints, through the real API. */
public final class SocialClient {

    /** A registered, age-confirmed account with a profile. [id] is for direct repository checks only. */
    public record Person(String token, long id, String publicId, String handle) {
    }

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final UserRepository users;

    public SocialClient(MockMvc mockMvc, ObjectMapper objectMapper, UserRepository users) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.users = users;
    }

    public Person person(String name) throws Exception {
        String email = "social." + UUID.randomUUID() + "@example.com";
        String token = TestAccounts.registerAndGetToken(mockMvc, objectMapper, email);
        long id = users.findByEmail(email).orElseThrow().getId();
        String handle = name.toLowerCase() + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        JsonNode profile = json(perform(token, put("/api/profiles/me"),
                Map.of("handle", handle, "displayName", name)).andExpect(status().isOk()));
        return new Person(token, id, profile.get("publicId").asText(), handle);
    }

    /** Registered and age-confirmed, but without a profile. */
    public String accountWithoutProfile() throws Exception {
        return TestAccounts.registerAndGetToken(mockMvc, objectMapper, "social." + UUID.randomUUID() + "@example.com");
    }

    public ResultActions request(Person from, Person to) throws Exception {
        return perform(from.token(), post("/api/connections/requests"), Map.of("profileId", to.publicId()));
    }

    public ResultActions requests(Person who, String direction) throws Exception {
        return perform(who.token(), get("/api/connections/requests").param("direction", direction), null);
    }

    public ResultActions accept(Person who, String requestId) throws Exception {
        return perform(who.token(), post("/api/connections/requests/{id}/accept", requestId), null);
    }

    public ResultActions decline(Person who, String requestId) throws Exception {
        return perform(who.token(), post("/api/connections/requests/{id}/decline", requestId), null);
    }

    public ResultActions cancel(Person who, String requestId) throws Exception {
        return perform(who.token(), delete("/api/connections/requests/{id}", requestId), null);
    }

    public ResultActions connections(Person who) throws Exception {
        return perform(who.token(), get("/api/connections"), null);
    }

    public ResultActions removeConnection(Person who, Person other) throws Exception {
        return perform(who.token(), delete("/api/connections/{id}", other.publicId()), null);
    }

    public ResultActions createInvite(Person who) throws Exception {
        return perform(who.token(), post("/api/connections/invites"), null);
    }

    public ResultActions invites(Person who) throws Exception {
        return perform(who.token(), get("/api/connections/invites"), null);
    }

    public ResultActions revokeInvite(Person who, String inviteId) throws Exception {
        return perform(who.token(), delete("/api/connections/invites/{id}", inviteId), null);
    }

    public ResultActions redeem(Person who, String code) throws Exception {
        return perform(who.token(), post("/api/connections/invites/redeem"), Map.of("code", code));
    }

    public ResultActions profile(Person viewer, Person owner) throws Exception {
        return perform(viewer.token(), get("/api/profiles/by-handle/{h}", owner.handle()), null);
    }

    public ResultActions block(Person who, Person other) throws Exception {
        return perform(who.token(), post("/api/blocks"), Map.of("profileId", other.publicId()));
    }

    public ResultActions unblock(Person who, Person other) throws Exception {
        return perform(who.token(), delete("/api/blocks/{id}", other.publicId()), null);
    }

    public ResultActions blocks(Person who) throws Exception {
        return perform(who.token(), get("/api/blocks"), null);
    }

    public ResultActions mute(Person who, Person other) throws Exception {
        return perform(who.token(), post("/api/mutes"), Map.of("profileId", other.publicId()));
    }

    public ResultActions unmute(Person who, Person other) throws Exception {
        return perform(who.token(), delete("/api/mutes/{id}", other.publicId()), null);
    }

    public ResultActions mutes(Person who) throws Exception {
        return perform(who.token(), get("/api/mutes"), null);
    }

    /** The request id of the first incoming request [who] has from [from], or null. */
    public String incomingFrom(Person who, Person from) throws Exception {
        for (JsonNode item : json(requests(who, "incoming").andExpect(status().isOk()))) {
            if (item.get("person").get("publicId").asText().equals(from.publicId())) return item.get("requestId").asText();
        }
        return null;
    }

    /** The public ids of the people in a list answer (connections, blocks, mutes) or in request items. */
    public List<String> publicIds(ResultActions list) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonNode item : json(list.andExpect(status().isOk()))) {
            JsonNode person = item.has("person") ? item.get("person") : item;
            ids.add(person.get("publicId").asText());
        }
        return ids;
    }

    public void connect(Person a, Person b) throws Exception {
        request(a, b).andExpect(status().isAccepted());
        accept(b, incomingFrom(b, a)).andExpect(status().isOk());
    }

    // ---- Phase 13B: places, posts, photos, perspectives ----

    public ResultActions createPlace(Person who, String name, String category, String cell) throws Exception {
        return perform(who.token(), post("/api/places"), Map.of("name", name, "category", category, "cell", cell));
    }

    /** A new place's public id. */
    public String place(Person who, String name, String cell) throws Exception {
        return json(createPlace(who, name, "FOOD_CAFES", cell).andExpect(status().isOk())).get("publicId").asText();
    }

    public ResultActions nearbyPlaces(Person who, String area) throws Exception {
        return perform(who.token(), get("/api/places/nearby").param("area", area), null);
    }

    public ResultActions createPost(Person who, Map<String, ?> body) throws Exception {
        return perform(who.token(), post("/api/posts"), body);
    }

    /** A simple Experience with no place; returns its public id. */
    public String experience(Person who, String audience) throws Exception {
        return json(createPost(who, experienceBody(audience, "NONE")).andExpect(status().isCreated()))
                .get("publicId").asText();
    }

    /** A valid Experience request (precision NONE unless changed); callers copy and adjust it. */
    public static Map<String, Object> experienceBody(String audience, String precision) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("type", "EXPERIENCE");
        body.put("body", "Went early in the morning; the light on the water was lovely.");
        body.put("tip", "Go before 7, it gets crowded.");
        body.put("category", "NATURE_OUTDOORS");
        body.put("precision", precision);
        body.put("experiencedMonth", java.time.YearMonth.now(java.time.ZoneOffset.UTC).toString());
        body.put("daypart", "MORNING");
        body.put("audience", audience);
        body.put("commentPolicy", "ONLY_ME".equals(audience) ? "NOBODY" : "EVERYONE");
        return body;
    }

    public ResultActions getPost(Person who, String postId) throws Exception {
        return perform(who.token(), get("/api/posts/{id}", postId), null);
    }

    public ResultActions editPost(Person who, String postId, Map<String, ?> body) throws Exception {
        return perform(who.token(), put("/api/posts/{id}", postId), body);
    }

    public ResultActions deletePost(Person who, String postId) throws Exception {
        return perform(who.token(), delete("/api/posts/{id}", postId), null);
    }

    public ResultActions minePosts(Person who, String cursor) throws Exception {
        var builder = get("/api/posts/mine");
        if (cursor != null) builder.param("cursor", cursor);
        return perform(who.token(), builder, null);
    }

    public ResultActions profilePosts(Person viewer, Person owner, String cursor) throws Exception {
        var builder = get("/api/profiles/{id}/posts", owner.publicId());
        if (cursor != null) builder.param("cursor", cursor);
        return perform(viewer.token(), builder, null);
    }

    /** The post ids of a list page ({items, nextCursor}). */
    public List<String> postIds(ResultActions page) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonNode item : json(page.andExpect(status().isOk())).get("items")) ids.add(item.get("publicId").asText());
        return ids;
    }

    public ResultActions comments(Person who, String postId) throws Exception {
        return perform(who.token(), get("/api/posts/{id}/comments", postId), null);
    }

    public ResultActions comment(Person who, String postId, String kind, String body, String parentId) throws Exception {
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("kind", kind);
        request.put("body", body);
        if (parentId != null) request.put("parentId", parentId);
        return perform(who.token(), post("/api/posts/{id}/comments", postId), request);
    }

    public String commentId(Person who, String postId, String kind, String body, String parentId) throws Exception {
        return json(comment(who, postId, kind, body, parentId).andExpect(status().isCreated())).get("publicId").asText();
    }

    public ResultActions commentAction(Person who, String commentId, String action) throws Exception {
        return perform(who.token(), post("/api/comments/{id}/" + action, commentId), null);
    }

    public ResultActions deleteComment(Person who, String commentId) throws Exception {
        return perform(who.token(), delete("/api/comments/{id}", commentId), null);
    }

    public ResultActions unmarkHelped(Person who, String commentId) throws Exception {
        return perform(who.token(), delete("/api/comments/{id}/helped", commentId), null);
    }

    public ResultActions upload(Person who, byte[] bytes, String contentType) throws Exception {
        return mockMvc.perform(post("/api/media").header(HttpHeaders.AUTHORIZATION, bearer(who.token()))
                .contentType(contentType).content(bytes));
    }

    public String uploadId(Person who, byte[] bytes) throws Exception {
        return json(upload(who, bytes, "image/jpeg").andExpect(status().isCreated())).get("mediaId").asText();
    }

    public ResultActions media(Person who, String mediaId) throws Exception {
        return perform(who.token(), get("/api/media/{id}", mediaId), null);
    }

    /** The public ids in a JSON array answer. */
    public List<String> ids(ResultActions list) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonNode item : json(list.andExpect(status().isOk()))) ids.add(item.get("publicId").asText());
        return ids;
    }

    public JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private ResultActions perform(String token,
                                  org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                                  Object body) throws Exception {
        builder.header(HttpHeaders.AUTHORIZATION, bearer(token));
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(builder);
    }
}
