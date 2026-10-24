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
        // The "x" keeps a letter in the suffix: with 8 random hex characters that all happened to be digits (~2 % of
        // runs), HandlePolicy saw only the name's letters, and a name like "Mod" is a reserved word (409). This was
        // the intermittent 13C failure (docs/PHASE13C.md C-D7), found in 13D.
        String handle = name.toLowerCase() + "_x" + UUID.randomUUID().toString().replace("-", "").substring(0, 7);
        var response = perform(token, put("/api/profiles/me"), Map.of("handle", handle, "displayName", name))
                .andReturn().getResponse();
        if (response.getStatus() != 200) {
            throw new AssertionError("Profile not created (" + response.getStatus() + "): " + response.getContentAsString());
        }
        JsonNode profile = objectMapper.readTree(response.getContentAsString());
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

    // ---- Phase 13C: butterflies, Cocoon, Discover ----

    public ResultActions butterfly(Person who, String postId, String state) throws Exception {
        return perform(who.token(), put("/api/posts/{id}/butterfly", postId),
                state == null ? Map.of() : Map.of("state", state));
    }

    public ResultActions removeButterfly(Person who, String postId) throws Exception {
        return perform(who.token(), delete("/api/posts/{id}/butterfly", postId), null);
    }

    public ResultActions cocoon(Person who, String state, String cursor) throws Exception {
        var builder = get("/api/cocoon");
        if (state != null) builder.param("state", state);
        if (cursor != null) builder.param("cursor", cursor);
        return perform(who.token(), builder, null);
    }

    /** The post ids of a Cocoon page ({items[{post, state, savedPeriod}], nextCursor}). */
    public List<String> cocoonIds(ResultActions page) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonNode item : json(page.andExpect(status().isOk())).get("items")) {
            ids.add(item.get("post").get("publicId").asText());
        }
        return ids;
    }

    public ResultActions edition(Person who, String city) throws Exception {
        var builder = get("/api/discover/edition");
        if (city != null) builder.param("city", city);
        return perform(who.token(), builder, null);
    }

    /** Every post id in an Edition, all sections together, in order. */
    public List<String> editionIds(ResultActions edition) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonNode section : json(edition.andExpect(status().isOk())).get("sections")) {
            for (JsonNode item : section.get("items")) ids.add(item.get("post").get("publicId").asText());
        }
        return ids;
    }

    public ResultActions fromConnections(Person who, String cursor) throws Exception {
        var builder = get("/api/discover/connections");
        if (cursor != null) builder.param("cursor", cursor);
        return perform(who.token(), builder, null);
    }

    /** The post ids of a Discover page ({items[{post, reasons}], nextCursor}). */
    public List<String> discoverIds(ResultActions page) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonNode item : json(page.andExpect(status().isOk())).get("items")) {
            ids.add(item.get("post").get("publicId").asText());
        }
        return ids;
    }

    public ResultActions categoryPage(Person who, String category, String city, String cursor) throws Exception {
        var builder = get("/api/discover/categories/{c}", category);
        if (city != null) builder.param("city", city);
        if (cursor != null) builder.param("cursor", cursor);
        return perform(who.token(), builder, null);
    }

    public ResultActions placePage(Person who, String placeId, String cursor) throws Exception {
        var builder = get("/api/places/{id}/posts", placeId);
        if (cursor != null) builder.param("cursor", cursor);
        return perform(who.token(), builder, null);
    }

    public ResultActions searchPlaces(Person who, String q, String city) throws Exception {
        var builder = get("/api/places/search");
        if (q != null) builder.param("q", q);
        if (city != null) builder.param("city", city);
        return perform(who.token(), builder, null);
    }

    // ---- Phase 13D: reports, standing, moderators ----

    /** Makes [who] a moderator (roles are read from the database on every request). */
    public void makeModerator(Person who) {
        var user = users.findById(who.id()).orElseThrow();
        user.setRole(com.safesphere.backend.user.entity.UserRole.MODERATOR);
        users.saveAndFlush(user);
    }

    public ResultActions report(Person who, String kind, String targetId, String reason, String note) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("targetKind", kind);
        body.put("targetId", targetId);
        body.put("reason", reason);
        if (note != null) body.put("note", note);
        return perform(who.token(), post("/api/reports"), body);
    }

    public ResultActions myReports(Person who) throws Exception {
        return perform(who.token(), get("/api/reports/mine"), null);
    }

    public ResultActions standing(Person who) throws Exception {
        return perform(who.token(), get("/api/social-standing"), null);
    }

    public ResultActions appealDecision(Person who, String decisionId) throws Exception {
        return perform(who.token(), post("/api/social-standing/decisions/{id}/appeal", decisionId), null);
    }

    public ResultActions contentCases(Person moderator, String status) throws Exception {
        var builder = get("/api/moderation/content-cases");
        if (status != null) builder.param("status", status);
        return perform(moderator.token(), builder, null);
    }

    public ResultActions contentCase(Person moderator, String caseId) throws Exception {
        return perform(moderator.token(), get("/api/moderation/content-cases/{id}", caseId), null);
    }

    public ResultActions act(Person moderator, String caseId, String action, String reason) throws Exception {
        return perform(moderator.token(), post("/api/moderation/content-cases/{id}/actions", caseId),
                Map.of("action", action, "reason", reason));
    }

    public ResultActions caseMedia(Person moderator, String caseId, String mediaId) throws Exception {
        return perform(moderator.token(), get("/api/moderation/content-cases/{id}/media/{m}", caseId, mediaId), null);
    }

    public ResultActions contentAppeals(Person moderator) throws Exception {
        return perform(moderator.token(), get("/api/moderation/content-appeals"), null);
    }

    public ResultActions decideContentAppeal(Person moderator, String appealId, String decision) throws Exception {
        return perform(moderator.token(), post("/api/moderation/content-appeals/{id}/decision", appealId),
                Map.of("decision", decision));
    }

    public ResultActions moderatorPlaces(Person moderator, String area) throws Exception {
        return perform(moderator.token(), get("/api/moderation/places").param("area", area), null);
    }

    public ResultActions mergePlace(Person moderator, String placeId, String into) throws Exception {
        return perform(moderator.token(), post("/api/moderation/places/{id}/merge", placeId), Map.of("into", into));
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
