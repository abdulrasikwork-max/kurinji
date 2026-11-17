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
        long id = users.findByPhoneE164AndPhoneVerifiedAtIsNotNull(TestAccounts.e164For(email)).orElseThrow().getId();
        // The "x" keeps a letter in the suffix: with 8 random hex characters that all happened to be digits (~2 % of
        // runs), HandlePolicy saw only the name's letters, and a name like "Mod" is a reserved word (409). This was
        // the intermittent 13C failure (docs/PHASE13C.md C-D7), found in 13D.
        String handle = name.toLowerCase() + "_x" + UUID.randomUUID().toString().replace("-", "").substring(0, 7);
        var response = perform(token, put("/api/profiles/me"), Map.of("handle", handle))
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

    // ---- Phase 17E: profile photo ----

    public ResultActions ownProfile(Person who) throws Exception {
        return perform(who.token(), get("/api/profiles/me"), null);
    }

    public ResultActions uploadProfilePhoto(Person who, byte[] bytes) throws Exception {
        return mockMvc.perform(put("/api/profiles/me/photo").header(HttpHeaders.AUTHORIZATION, bearer(who.token()))
                .contentType("image/jpeg").content(bytes));
    }

    public ResultActions removeProfilePhoto(Person who) throws Exception {
        return perform(who.token(), delete("/api/profiles/me/photo"), null);
    }

    public ResultActions profilePhoto(Person viewer, Person owner, String key) throws Exception {
        return perform(viewer.token(), get("/api/profiles/{id}/photo/{key}", owner.publicId(), key), null);
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

    public ResultActions caseMedia(Person moderator, String caseId) throws Exception {
        return perform(moderator.token(), get("/api/moderation/content-cases/{id}/media", caseId), null);
    }

    public ResultActions contentAppeals(Person moderator) throws Exception {
        return perform(moderator.token(), get("/api/moderation/content-appeals"), null);
    }

    public ResultActions decideContentAppeal(Person moderator, String appealId, String decision) throws Exception {
        return perform(moderator.token(), post("/api/moderation/content-appeals/{id}/decision", appealId),
                Map.of("decision", decision));
    }

    public ResultActions aboutMe(Person who, String momentId) throws Exception {
        return perform(who.token(), post("/api/moments/{id}/about-me", momentId), null);
    }

    public ResultActions askReview(Person who, String momentId) throws Exception {
        return perform(who.token(), post("/api/content-checks/review"),
                Map.of("targetKind", "MOMENT", "targetId", momentId));
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

    /** Phase 17E: any request as [who] (for routes the client has no helper for). */
    public ResultActions perform(Person who,
                                 org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        return perform(who.token(), builder, null);
    }

    private ResultActions perform(String token,
                                  org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                                  Object body) throws Exception {
        builder.header(HttpHeaders.AUTHORIZATION, bearer(token));
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(builder);
    }
}
