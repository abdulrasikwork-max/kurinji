package com.safesphere.backend.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.moderation.service.ContentModerationService;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.support.TestAccounts;
import com.safesphere.backend.support.TestImages;
import com.safesphere.backend.user.repository.UserRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 13D: moderators deciding about posts, perspectives and people through the real API (plan M7-M12, M14, M17,
 * M18): every decision has its effect and nothing more; held and removed content is the author's alone; strikes once
 * per case; pauses stop exactly their writes and never safety; appeals go to another moderator and an overturn undoes
 * the decision; moderators never decide a case they are part of; edits during a case are kept for the moderator only.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContentModerationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ContentModerationService service;

    private SocialClient social;
    private Person asha;   // the author
    private Person bala;   // connected to Asha, reports
    private Person chitra; // stranger
    private Person mod;    // moderator
    private Person mod2;   // a second moderator

    @BeforeEach
    void setUp() throws Exception {
        social = new SocialClient(mockMvc, objectMapper, users);
        asha = social.person("Asha");
        bala = social.person("Bala");
        chitra = social.person("Chitra");
        mod = social.person("Mod");
        mod2 = social.person("Modtwo");
        social.connect(asha, bala);
        social.makeModerator(mod);
        social.makeModerator(mod2);
    }

    @Test
    void onlyModerators_seeTheQueue() throws Exception {
        social.contentCases(chitra, "OPEN").andExpect(status().isForbidden());
        social.contentCases(mod, "OPEN").andExpect(status().isOk());
        social.contentAppeals(chitra).andExpect(status().isForbidden());
    }

    @Test
    void keep_closesTheCase_andTheAuthorIsNeverTold() throws Exception {
        String post = social.experience(asha, "CONNECTIONS");
        social.report(bala, "POST", post, "HARASSMENT", null).andExpect(status().isAccepted());
        String caseId = postCase(post);
        JsonNode detail = social.json(social.contentCase(mod, caseId).andExpect(status().isOk()));
        assertThat(detail.get("content").get("body").asText()).startsWith("Went early");
        assertThat(detail.get("content").get("audience").asText()).isEqualTo("CONNECTIONS");
        assertThat(detail.get("participants")).hasSize(2);
        assertThat(detail.get("participants").get(0).get("role").asText()).isEqualTo("AUTHOR");
        assertThat(detail.get("participants").get(1).get("reason").asText()).isEqualTo("HARASSMENT");
        String raw = detail.toString();
        assertThat(raw).doesNotContain(asha.handle()).doesNotContain(bala.handle()).doesNotContain("@example.com");
        assertThat(detail.get("possibleActions").toString()).contains("CONTENT_KEEP", "CONTENT_REMOVE", "PERSON_WARN");

        social.act(mod, caseId, "CONTENT_KEEP", "HARASSMENT").andExpect(status().isBadRequest());
        social.act(mod, caseId, "CONTENT_KEEP", "NO_VIOLATION").andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("CLOSED"))
                .andExpect(jsonPath("$.summary.outcome").value("NO_VIOLATION"))
                // Emulator finding: a decided case still says what it was reported for.
                .andExpect(jsonPath("$.summary.topReason").value("HARASSMENT"));
        social.act(mod, caseId, "CONTENT_REMOVE", "SPAM").andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT in_review FROM posts WHERE public_id = ?", Boolean.class, post)).isFalse();
        social.getPost(asha, post).andExpect(jsonPath("$.moderation").isEmpty());
        assertThat(social.json(social.standing(asha)).get("decisions")).isEmpty();
        JsonNode mine = social.json(social.myReports(bala)).get(0);
        assertThat(mine.get("status").asText()).isEqualTo("REVIEWED");
        assertThat(mine.get("outcome").asText()).isEqualTo("NO_VIOLATION");
    }

    @Test
    void hold_remove_strike_appeal_overturn() throws Exception {
        String post = social.experience(asha, "EVERYONE");
        social.butterfly(chitra, post, null).andExpect(status().isOk());
        social.report(chitra, "POST", post, "THREAT", null).andExpect(status().isAccepted());
        String caseId = postCase(post);

        social.act(mod, caseId, "CONTENT_HOLD", "NEEDS_REVIEW").andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("OPEN"))
                .andExpect(jsonPath("$.summary.contentState").value("HELD"));
        social.getPost(chitra, post).andExpect(status().isNotFound());
        social.getPost(bala, post).andExpect(status().isNotFound());
        assertThat(social.cocoonIds(social.cocoon(chitra, "WANT", null))).isEmpty();
        assertThat(social.postIds(social.profilePosts(bala, asha, null))).doesNotContain(post);
        social.getPost(asha, post).andExpect(status().isOk()).andExpect(jsonPath("$.moderation.state").value("HELD"))
                .andExpect(jsonPath("$.moderation.reason").value("NEEDS_REVIEW"));
        social.comment(asha, post, "QUESTION", "Anyone else been?", null).andExpect(status().isNotFound());

        JsonNode removed = social.json(social.act(mod, caseId, "CONTENT_REMOVE", "THREAT").andExpect(status().isOk()));
        assertThat(removed.get("summary").get("outcome").asText()).isEqualTo("ACTION_TAKEN");
        assertThat(strikes(asha)).isEqualTo(1);
        social.getPost(chitra, post).andExpect(status().isNotFound());
        JsonNode own = social.json(social.getPost(asha, post).andExpect(status().isOk()));
        assertThat(own.get("moderation").get("state").asText()).isEqualTo("REMOVED");
        assertThat(own.get("moderation").get("reason").asText()).isEqualTo("THREAT");
        String decisionId = own.get("moderation").get("decisionId").asText();

        // A person action on the same (closed, confirmed) case: no second strike for one case.
        social.act(mod, caseId, "PERSON_WARN", "THREAT").andExpect(status().isOk());
        assertThat(strikes(asha)).isEqualTo(1);

        JsonNode standing = social.json(social.standing(asha).andExpect(status().isOk()));
        JsonNode removal = decision(standing, "CONTENT_REMOVE");
        assertThat(removal.get("decisionId").asText()).isEqualTo(decisionId);
        assertThat(removal.get("targetId").asText()).isEqualTo(post);
        assertThat(removal.get("canAppeal").asBoolean()).isTrue();
        assertThat(decision(standing, "PERSON_WARN").get("canAppeal").asBoolean()).isFalse();
        assertThat(standing.toString()).doesNotContain(chitra.handle()).doesNotContain(chitra.publicId());

        social.appealDecision(chitra, decisionId).andExpect(status().isNotFound());
        social.appealDecision(asha, decisionId).andExpect(status().isAccepted());
        social.appealDecision(asha, decisionId).andExpect(status().isConflict());
        String appealId = appealIdFor(mod2, decisionId);
        // The Phase 7 queue does not hold it.
        assertThat(social.json(mockMvc.perform(get("/api/moderation/appeals")
                .header(HttpHeaders.AUTHORIZATION, TestAccounts.bearer(mod2.token())))).toString()).doesNotContain(appealId);
        social.decideContentAppeal(mod, appealId, "OVERTURNED").andExpect(status().isForbidden());
        social.decideContentAppeal(mod2, appealId, "OVERTURNED").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OVERTURNED"));
        social.decideContentAppeal(mod2, appealId, "UPHELD").andExpect(status().isConflict());
        social.getPost(chitra, post).andExpect(status().isOk()).andExpect(jsonPath("$.collapsed").isEmpty());
        assertThat(strikes(asha)).isZero();
        assertThat(decision(social.json(social.standing(asha)), "CONTENT_REMOVE").get("appeal").asText())
                .isEqualTo("OVERTURNED");
    }

    @Test
    void collapse_isAFinalDecision_withoutAStrike() throws Exception {
        String post = social.experience(asha, "EVERYONE");
        social.report(bala, "POST", post, "HARASSMENT", null).andExpect(status().isAccepted());
        social.act(mod, postCase(post), "CONTENT_COLLAPSE", "HARASSMENT").andExpect(status().isOk());
        social.getPost(chitra, post).andExpect(jsonPath("$.collapsed").value("BY_MODERATOR"));
        social.getPost(asha, post).andExpect(jsonPath("$.moderation.state").value("COLLAPSED"))
                .andExpect(jsonPath("$.moderation.reason").value("HARASSMENT"));
        assertThat(strikes(asha)).isZero();
    }

    @Test
    void moderators_neverDecide_aCaseTheyArePartOf() throws Exception {
        String post = social.experience(mod, "EVERYONE");
        social.report(mod2, "POST", post, "SPAM", null).andExpect(status().isAccepted());
        String caseId = postCase(post);
        social.contentCase(mod, caseId).andExpect(status().isForbidden());
        social.contentCase(mod2, caseId).andExpect(status().isForbidden());
        social.act(mod2, caseId, "CONTENT_REMOVE", "SPAM").andExpect(status().isForbidden());
    }

    @Test
    void aModeratorWhoReportedIt_neverDecidesItsAppeal() throws Exception {
        String post = social.experience(asha, "EVERYONE");
        social.report(mod2, "POST", post, "HARASSMENT", null).andExpect(status().isAccepted());
        social.act(mod, postCase(post), "CONTENT_REMOVE", "HARASSMENT").andExpect(status().isOk());
        String decisionId = social.json(social.getPost(asha, post)).get("moderation").get("decisionId").asText();
        social.appealDecision(asha, decisionId).andExpect(status().isAccepted());
        Person mod3 = social.person("Modthree");
        social.makeModerator(mod3);
        String appealId = appealIdFor(mod3, decisionId);
        assertThat(social.json(social.contentAppeals(mod2)).toString()).doesNotContain(appealId);
        social.decideContentAppeal(mod2, appealId, "OVERTURNED").andExpect(status().isForbidden());
        social.decideContentAppeal(mod3, appealId, "UPHELD").andExpect(status().isOk());
    }

    @Test
    void pauses_stopExactlyTheirWrites_andNothingElse() throws Exception {
        social.report(bala, "PERSON", asha.publicId(), "HARASSMENT", null).andExpect(status().isAccepted());
        String caseId = personCase(asha);
        JsonNode detail = social.json(social.contentCase(mod, caseId).andExpect(status().isOk()));
        assertThat(detail.get("content").get("person").get("handle").asText()).isEqualTo(asha.handle());
        assertThat(detail.get("author").get("suggestedStep").asText()).isEqualTo("WARN");
        String own = social.experience(asha, "EVERYONE");

        social.act(mod, caseId, "PERSON_PAUSE_COMMENTS", "HARASSMENT").andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("CLOSED"));
        social.comment(asha, own, "QUESTION", "Anyone been at night?", null).andExpect(status().isForbidden());
        social.experience(asha, "EVERYONE"); // posting still works

        // A later, stronger pause (another case).
        social.report(chitra, "PERSON", asha.publicId(), "THREAT", null).andExpect(status().isAccepted());
        social.act(mod, personCase(asha), "PERSON_SUSPEND", "THREAT").andExpect(status().isOk());
        social.createPost(asha, SocialClient.experienceBody("EVERYONE", "NONE")).andExpect(status().isForbidden());
        social.createPlace(asha, "Elliot's Beach", "NATURE_OUTDOORS", "tf3v2xb").andExpect(status().isForbidden());
        social.request(asha, chitra).andExpect(status().isForbidden());
        social.createInvite(asha).andExpect(status().isForbidden());
        // Never refused: reading, deleting, narrowing, blocking, muting, reporting, the Cocoon, safety.
        social.getPost(asha, own).andExpect(status().isOk());
        social.mute(asha, chitra).andExpect(status().isNoContent());
        social.block(asha, chitra).andExpect(status().isNoContent());
        social.report(asha, "POST", social.experience(bala, "EVERYONE"), "SPAM", null).andExpect(status().isAccepted());
        social.deletePost(asha, own).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/emergencies").header(HttpHeaders.AUTHORIZATION, TestAccounts.bearer(asha.token())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/safety-contacts").header(HttpHeaders.AUTHORIZATION, TestAccounts.bearer(asha.token())))
                .andExpect(status().isOk());

        JsonNode standing = social.json(social.standing(asha).andExpect(status().isOk()));
        assertThat(standing.get("restriction").get("level").asText()).isEqualTo("SUSPENDED");
        assertThat(standing.get("restriction").get("canAppeal").asBoolean()).isTrue();
        assertThat(strikes(asha)).isEqualTo(2);
        String suspension = standing.get("restriction").get("decisionId").asText();
        social.appealDecision(asha, suspension).andExpect(status().isAccepted());
        social.decideContentAppeal(mod2, appealIdFor(mod2, suspension), "OVERTURNED").andExpect(status().isOk());
        assertThat(social.json(social.standing(asha)).get("restriction").isNull()).isTrue();
        social.experience(asha, "EVERYONE");
        assertThat(strikes(asha)).isEqualTo(1);
    }

    @Test
    void editsDuringACase_areKeptForTheModerator_only_untilItCloses() throws Exception {
        String post = social.experience(asha, "EVERYONE");
        Map<String, Object> edit = SocialClient.experienceBody("EVERYONE", "NONE");
        edit.remove("type");
        edit.put("body", "Before any report.");
        social.editPost(asha, post, edit).andExpect(status().isOk());
        assertThat(revisions(post)).isZero();

        social.report(bala, "POST", post, "HARASSMENT", null).andExpect(status().isAccepted());
        edit.put("body", "Something nicer after the report.");
        social.editPost(asha, post, edit).andExpect(status().isOk());
        assertThat(revisions(post)).isEqualTo(1);
        String caseId = postCase(post);
        JsonNode detail = social.json(social.contentCase(mod, caseId));
        assertThat(detail.get("revisions").get(0).get("body").asText()).isEqualTo("Before any report.");
        assertThat(detail.get("content").get("body").asText()).isEqualTo("Something nicer after the report.");
        assertThat(social.json(social.getPost(chitra, post)).toString()).doesNotContain("Before any report.");
        social.act(mod, caseId, "CONTENT_KEEP", "NO_VIOLATION").andExpect(status().isOk());
        assertThat(revisions(post)).isZero();
    }

    @Test
    void perspectives_andPhotos_underReview() throws Exception {
        String media = social.uploadId(asha, TestImages.jpeg(40, 30));
        Map<String, Object> body = SocialClient.experienceBody("EVERYONE", "NONE");
        body.put("mediaIds", List.of(media));
        String post = social.json(social.createPost(asha, body).andExpect(status().isCreated())).get("publicId").asText();
        String other = social.uploadId(bala, TestImages.jpeg(40, 30));
        social.report(chitra, "POST", post, "NUDITY_OR_VIOLENCE", null).andExpect(status().isAccepted());
        String caseId = postCase(post);
        social.caseMedia(mod, caseId, media).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        social.caseMedia(mod, caseId, other).andExpect(status().isNotFound());

        String comment = social.commentId(chitra, post, "BEEN_HERE", "Rude words here.", null);
        social.report(asha, "COMMENT", comment, "HARASSMENT", null).andExpect(status().isAccepted());
        String commentCase = jdbc.queryForObject("SELECT c.public_id FROM content_cases c JOIN comments m"
                + " ON m.id = c.comment_id WHERE m.public_id = ? AND c.status = 'OPEN'", String.class, comment);
        JsonNode detail = social.json(social.contentCase(mod, commentCase).andExpect(status().isOk()));
        assertThat(detail.get("content").get("body").asText()).isEqualTo("Rude words here.");
        assertThat(detail.get("content").get("contextBody").asText()).startsWith("Went early");
        social.act(mod, commentCase, "CONTENT_REMOVE", "HARASSMENT").andExpect(status().isOk());
        assertThat(social.json(social.comments(bala, post)).toString()).doesNotContain(comment);
        assertThat(social.json(social.comments(chitra, post)).toString()).contains("REMOVED");
    }

    @Test
    void deletingReportedContent_isAllowed_andTheCaseGoes() throws Exception {
        String post = social.experience(asha, "EVERYONE");
        social.report(bala, "POST", post, "SPAM", null).andExpect(status().isAccepted());
        String caseId = postCase(post);
        social.deletePost(asha, post).andExpect(status().isNoContent());
        service.maintain();
        social.contentCase(mod, caseId).andExpect(status().isNotFound());
        assertThat(social.json(social.myReports(bala))).isEmpty();
    }

    @Test
    void retention_deletesOldClosedCases_andEndedPauses() throws Exception {
        String post = social.experience(asha, "EVERYONE");
        social.report(bala, "POST", post, "SPAM", null).andExpect(status().isAccepted());
        String caseId = postCase(post);
        social.act(mod, caseId, "CONTENT_KEEP", "NO_VIOLATION").andExpect(status().isOk());
        jdbc.update("UPDATE content_cases SET opened_at = DATEADD('DAY', -200, opened_at),"
                + " closed_at = DATEADD('DAY', -100, closed_at) WHERE public_id = ?", caseId);
        jdbc.update("INSERT INTO social_standing (user_id, restriction, restricted_until, reason, decision, decided_at)"
                + " VALUES (?, 'COMMENTS_PAUSED', DATEADD('DAY', -1, CURRENT_TIMESTAMP), 'SPAM',"
                + " '22222222-2222-4222-8222-222222222222', DATEADD('DAY', -4, CURRENT_TIMESTAMP))", chitra.id());
        service.maintain();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_cases WHERE public_id = ?", Integer.class, caseId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_reports WHERE reporter_id = ?", Integer.class,
                bala.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM social_standing WHERE user_id = ?", Integer.class,
                chitra.id())).isZero();
    }

    @Test
    void approveFirst_holdsStrangersPerspectives_forTheAuthor() throws Exception {
        Map<String, Object> body = SocialClient.experienceBody("EVERYONE", "NONE");
        body.put("approveFirst", true);
        String post = social.json(social.createPost(asha, body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.approveFirst").value(true))).get("publicId").asText();
        Map<String, Object> wrong = new HashMap<>(body);
        wrong.put("commentPolicy", "CONNECTIONS");
        social.createPost(asha, wrong).andExpect(status().isBadRequest());

        String stranger = social.commentId(chitra, post, "BEEN_HERE", "Went last week.", null);
        String friend = social.commentId(bala, post, "BEEN_HERE", "Me too.", null);
        Person dev = social.person("Dev");
        String devView = social.json(social.comments(dev, post)).toString();
        assertThat(devView).contains(friend).doesNotContain(stranger);
        JsonNode writer = find(social.json(social.comments(chitra, post)), stranger);
        assertThat(writer.get("awaitingApproval").asBoolean()).isTrue();
        assertThat(writer.get("canApprove").asBoolean()).isFalse();
        JsonNode author = find(social.json(social.comments(asha, post)), stranger);
        assertThat(author.get("canApprove").asBoolean()).isTrue();

        social.commentAction(chitra, stranger, "approve").andExpect(status().isNotFound());
        social.commentAction(asha, stranger, "approve").andExpect(status().isNoContent());
        assertThat(social.json(social.comments(dev, post)).toString()).contains(stranger);
    }

    @Test
    void duplicatePlaces_merge_withinOneArea() throws Exception {
        String keep = social.place(asha, "Marina Lighthouse", "tf3v2xb");
        String dup = social.place(asha, "Lighthouse Marina", "tf3v2xc");
        String far = social.place(asha, "Far Away Cafe", "tf3v3zz");
        Map<String, Object> body = SocialClient.experienceBody("EVERYONE", "EXACT");
        body.put("placeId", dup);
        String post = social.json(social.createPost(asha, body).andExpect(status().isCreated())).get("publicId").asText();

        social.mergePlace(chitra, dup, keep).andExpect(status().isForbidden());
        social.mergePlace(mod, dup, dup).andExpect(status().isBadRequest());
        social.mergePlace(mod, dup, far).andExpect(status().isBadRequest());
        social.mergePlace(mod, dup, keep).andExpect(status().isNoContent());
        social.mergePlace(mod, dup, keep).andExpect(status().isConflict());
        JsonNode list = social.json(social.moderatorPlaces(mod, "tf3v2x").andExpect(status().isOk()));
        assertThat(list.toString()).contains(dup).contains(keep);
        assertThat(social.ids(social.nearbyPlaces(chitra, "tf3v2x"))).contains(keep).doesNotContain(dup);
        social.getPost(asha, post).andExpect(jsonPath("$.place.publicId").value(keep));
    }

    // ---- helpers ----

    private String postCase(String postId) {
        return jdbc.queryForObject("SELECT c.public_id FROM content_cases c JOIN posts p ON p.id = c.post_id"
                + " WHERE p.public_id = ? AND c.status = 'OPEN'", String.class, postId);
    }

    private String personCase(Person person) {
        return jdbc.queryForObject("SELECT public_id FROM content_cases WHERE person_id = ? AND status = 'OPEN'",
                String.class, person.id());
    }

    private int strikes(Person who) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM social_strikes WHERE user_id = ?", Integer.class, who.id());
    }

    private int revisions(String postId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM post_revisions r JOIN posts p ON p.id = r.post_id"
                + " WHERE p.public_id = ?", Integer.class, postId);
    }

    private String appealIdFor(Person moderator, String decisionId) throws Exception {
        String actionId = jdbc.queryForObject("SELECT CAST(id AS VARCHAR) FROM moderation_actions WHERE public_id = ?",
                String.class, decisionId);
        String appealId = jdbc.queryForObject("SELECT public_id FROM moderation_appeals WHERE action_id = ?",
                String.class, Long.valueOf(actionId));
        assertThat(social.json(social.contentAppeals(moderator).andExpect(status().isOk())).toString()).contains(appealId);
        return appealId;
    }

    private static JsonNode decision(JsonNode standing, String action) {
        for (JsonNode d : standing.get("decisions")) if (d.get("action").asText().equals(action)) return d;
        throw new AssertionError("no decision " + action + " in " + standing);
    }

    private static JsonNode find(JsonNode list, String publicId) {
        for (JsonNode item : list) if (item.get("publicId").asText().equals(publicId)) return item;
        throw new AssertionError("not in the list: " + publicId);
    }
}
