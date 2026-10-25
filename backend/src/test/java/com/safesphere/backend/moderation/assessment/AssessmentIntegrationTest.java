package com.safesphere.backend.moderation.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.support.StubModerationService;
import com.safesphere.backend.user.repository.UserRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Phase 13E end to end, against a stub service: publish / collapse / hold, what the author, others and moderators see,
 * edits, never overriding reports, outages, the review request, self-harm never stored, only-me never sent, retention.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AssessmentIntegrationTest {

    static final StubModerationService STUB = new StubModerationService();

    @DynamicPropertySource
    static void ai(DynamicPropertyRegistry registry) {
        // Its own in-memory database (13D-D10): a second context must not re-create the shared tables.
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:safesphere-ai-test;MODE=MySQL;DB_CLOSE_DELAY=-1");
        registry.add("app.moderation.ai.enabled", () -> "true");
        registry.add("app.moderation.ai.key", () -> StubModerationService.KEY);
        registry.add("app.moderation.ai.url", STUB::url);
        registry.add("app.moderation.ai.timeout", () -> "400ms");
    }

    @AfterAll
    static void stop() {
        STUB.stop();
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AssessmentService assessments;

    private SocialClient social;

    @BeforeEach
    void setUp() {
        social = new SocialClient(mockMvc, objectMapper, users);
        STUB.down = false;
    }

    private JsonNode create(Person who, String text, String audience) throws Exception {
        Map<String, Object> body = SocialClient.experienceBody(audience, "NONE");
        body.put("body", text);
        body.remove("tip");
        return social.json(social.createPost(who, body).andExpect(status().isCreated()));
    }

    private String row(String postId, String column) {
        return jdbc.queryForObject("SELECT a." + column + " FROM content_assessments a JOIN posts p ON p.id = a.post_id"
                + " WHERE p.public_id = ?", String.class, postId);
    }

    @Test
    void aCleanPost_isPublished_andTheAssessmentKeepsNoText() throws Exception {
        Person asha = social.person("Asha");
        JsonNode post = create(asha, "Sunrise at the beach, go before seven.", "EVERYONE");
        assertThat(post.get("moderation").isNull()).isTrue();
        assertThat(post.get("supportSuggested").isNull()).isTrue();
        String id = post.get("publicId").asText();
        assertThat(row(id, "status")).isEqualTo("DONE");
        assertThat(row(id, "rule")).isEqualTo("CLEAN");
        assertThat(row(id, "lexicon_version")).isEqualTo("lex-1");
        assertThat(row(id, "policy_version")).isEqualTo("policy-1");
        Map<String, Object> all = jdbc.queryForMap("SELECT a.* FROM content_assessments a JOIN posts p ON p.id ="
                + " a.post_id WHERE p.public_id = ?", id);
        assertThat(all.values().toString()).doesNotContain("Sunrise");
        StubModerationService.Seen seen = STUB.seen.get(STUB.seen.size() - 1);
        assertThat(seen.fields()).containsExactlyInAnyOrder("kind", "text");
        assertThat(seen.text()).doesNotContain(asha.handle()).doesNotContain(id);
    }

    @Test
    void aTargetedInsult_isCollapsed_theAuthorSeesWhy_othersOnlyThatItIsCollapsed() throws Exception {
        Person asha = social.person("Asha");
        Person bala = social.person("Bala");
        JsonNode post = create(asha, "You are a [INSULT] honestly.", "EVERYONE");
        String id = post.get("publicId").asText();
        JsonNode moderation = post.get("moderation");
        assertThat(moderation.get("state").asText()).isEqualTo("COLLAPSED");
        assertThat(moderation.get("source").asText()).isEqualTo("SYSTEM");
        assertThat(moderation.get("reason").asText()).isEqualTo("HARASSMENT");
        assertThat(moderation.get("decisionId").isNull()).isTrue();
        assertThat(moderation.get("flagged").get(0).get("start").asInt()).isEqualTo(10);
        assertThat(moderation.get("flagged").get(0).get("end").asInt()).isEqualTo(18);
        JsonNode seen = social.json(social.getPost(bala, id).andExpect(status().isOk()));
        assertThat(seen.get("collapsed").asText()).isEqualTo("BY_SYSTEM");
        assertThat(seen.get("moderation").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_cases c JOIN posts p ON p.id = c.post_id"
                + " WHERE p.public_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void aSevereThreat_isHeld_aModeratorLooks_andKeepRestoresIt() throws Exception {
        Person asha = social.person("Asha");
        Person bala = social.person("Bala");
        Person mod = social.person("Mod");
        social.makeModerator(mod);
        JsonNode post = create(asha, "Delete this or [THREAT].", "EVERYONE");
        String id = post.get("publicId").asText();
        assertThat(post.get("moderation").get("state").asText()).isEqualTo("HELD");
        assertThat(post.get("moderation").get("reason").asText()).isEqualTo("THREAT");
        social.getPost(bala, id).andExpect(status().isNotFound());

        JsonNode queue = social.json(social.contentCases(mod, "OPEN"));
        JsonNode summary = null;
        for (JsonNode c : queue) if ("SYSTEM".equals(c.get("openedBy").asText())) summary = c;
        assertThat(summary).isNotNull();
        assertThat(summary.get("contentSource").asText()).isEqualTo("SYSTEM");
        // A case without reports names the content's own reason (emulator finding: it read "A guideline").
        assertThat(summary.get("topReason").asText()).isEqualTo("THREAT");
        String caseId = summary.get("caseId").asText();
        JsonNode detail = social.json(social.contentCase(mod, caseId));
        JsonNode assessment = detail.get("assessment");
        assertThat(assessment.get("rule").asText()).isEqualTo("SEVERE");
        assertThat(assessment.get("evidence").get(0).get("category").asText()).isEqualTo("THREAT");
        assertThat(assessment.get("lexiconVersion").asText()).isEqualTo("lex-1");

        social.act(mod, caseId, "CONTENT_KEEP", "NO_VIOLATION").andExpect(status().isOk());
        assertThat(social.json(social.getPost(bala, id).andExpect(status().isOk())).get("collapsed").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM social_strikes", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evaluation_counters WHERE metric = 'AI_REVIEWED'"
                + " AND dimension = 'HELD.CONTENT_KEEP'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evaluation_counters WHERE metric = 'AI_CHECKED'"
                + " AND dimension = 'HOLD_FOR_REVIEW.SEVERE'", Integer.class)).isEqualTo(1);
    }

    @Test
    void quotedSpeech_isPublished_strongLanguageIsMarked() throws Exception {
        Person asha = social.person("Asha");
        Person bala = social.person("Bala");
        JsonNode quoted = create(asha, "A man shouted [QUOTED] at the stop, be careful.", "EVERYONE");
        assertThat(quoted.get("moderation").isNull()).isTrue();
        assertThat(row(quoted.get("publicId").asText(), "rule")).isEqualTo("QUOTED");
        JsonNode strong = create(asha, "This [STRONG] traffic.", "EVERYONE");
        assertThat(strong.get("moderation").isNull()).isTrue();
        assertThat(social.json(social.getPost(bala, strong.get("publicId").asText())).get("strongLanguage").asBoolean())
                .isTrue();
    }

    @Test
    void selfHarm_showsTheAuthorASupportLineOnce_andIsNeverStored() throws Exception {
        Person asha = social.person("Asha");
        JsonNode post = create(asha, "Some days are too much [SELF].", "CONNECTIONS");
        assertThat(post.get("supportSuggested").asBoolean()).isTrue();
        assertThat(post.get("moderation").isNull()).isTrue();
        String id = post.get("publicId").asText();
        assertThat(social.json(social.getPost(asha, id)).get("supportSuggested").isNull()).isTrue();
        Map<String, Object> all = jdbc.queryForMap("SELECT a.* FROM content_assessments a JOIN posts p ON p.id ="
                + " a.post_id WHERE p.public_id = ?", id);
        assertThat(all.values().toString().toUpperCase()).doesNotContain("SELF");
    }

    @Test
    void anOnlyMePost_isNeverSent() throws Exception {
        Person asha = social.person("Asha");
        int before = STUB.seen.size();
        JsonNode post = create(asha, "Private note [THREAT]", "ONLY_ME");
        assertThat(STUB.seen).hasSize(before);
        assertThat(post.get("moderation").isNull()).isTrue();
    }

    @Test
    void edits_areCheckedAgain_bothWays() throws Exception {
        Person asha = social.person("Asha");
        JsonNode post = create(asha, "A calm evening.", "EVERYONE");
        String id = post.get("publicId").asText();
        Map<String, Object> edit = SocialClient.experienceBody("EVERYONE", "NONE");
        edit.put("body", "Now you [INSULT].");
        edit.remove("tip");
        JsonNode edited = social.json(social.editPost(asha, id, edit).andExpect(status().isOk()));
        assertThat(edited.get("moderation").get("state").asText()).isEqualTo("COLLAPSED");
        edit.put("body", "Sorry, a calm evening.");
        edited = social.json(social.editPost(asha, id, edit).andExpect(status().isOk()));
        assertThat(edited.get("moderation").isNull()).isTrue();
    }

    @Test
    void aCollapseByReports_isNeverLiftedByTheCheck() throws Exception {
        Person asha = social.person("Asha");
        String id = create(asha, "A calm evening.", "EVERYONE").get("publicId").asText();
        for (Person r : List.of(social.person("R1"), social.person("R2"), social.person("R3"))) {
            social.report(r, "POST", id, "HARASSMENT", null).andExpect(status().isAccepted());
        }
        assertThat(jdbc.queryForObject("SELECT moderation_source FROM posts WHERE public_id = ?", String.class, id))
                .isEqualTo("REPORTS");
        Map<String, Object> edit = SocialClient.experienceBody("EVERYONE", "NONE");
        edit.put("body", "An even calmer evening.");
        edit.remove("tip");
        JsonNode edited = social.json(social.editPost(asha, id, edit).andExpect(status().isOk()));
        assertThat(edited.get("moderation").get("state").asText()).isEqualTo("COLLAPSED");
        // The author never learns that reports caused it (13D M4): no source, no phrase.
        assertThat(edited.get("moderation").get("source").isNull()).isTrue();
        assertThat(edited.get("moderation").get("flagged")).isEmpty();
        assertThat(edited.toString().toLowerCase()).doesNotContain("report");
    }

    @Test
    void aPerspective_isCheckedToo() throws Exception {
        Person asha = social.person("Asha");
        Person bala = social.person("Bala");
        String id = social.experience(asha, "EVERYONE");
        JsonNode comment = social.json(social.comment(bala, id, "QUESTION", "Why [THREAT]?", null)
                .andExpect(status().isCreated()));
        assertThat(comment.get("moderation").get("state").asText()).isEqualTo("HELD");
        assertThat(jdbc.queryForObject("SELECT origin FROM content_cases c JOIN comments m ON m.id = c.comment_id"
                + " WHERE m.public_id = ?", String.class, comment.get("publicId").asText())).isEqualTo("SYSTEM");
    }

    @Test
    void anOutage_publishes_andTheJobChecksLater() throws Exception {
        Person asha = social.person("Asha");
        Person bala = social.person("Bala");
        STUB.down = true;
        JsonNode post = create(asha, "Later this turns out [THREAT].", "EVERYONE");
        String id = post.get("publicId").asText();
        assertThat(post.get("moderation").isNull()).isTrue();
        social.getPost(bala, id).andExpect(status().isOk());
        assertThat(row(id, "status")).isEqualTo("PENDING");
        STUB.down = false;
        jdbc.update("UPDATE content_assessments SET next_attempt_at = created_at WHERE post_id ="
                + " (SELECT id FROM posts WHERE public_id = ?)", id);
        assessments.maintain();
        assertThat(row(id, "status")).isEqualTo("DONE");
        social.getPost(bala, id).andExpect(status().isNotFound());
    }

    @Test
    void timeoutsAndGarbage_neverStopAWrite() throws Exception {
        Person asha = social.person("Asha");
        long started = System.nanoTime();
        JsonNode slow = create(asha, "A slow one [SLOW]", "EVERYONE");
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1400);
        assertThat(row(slow.get("publicId").asText(), "status")).isEqualTo("PENDING");
        JsonNode garbage = create(asha, "A broken one [GARBAGE]", "EVERYONE");
        assertThat(garbage.get("moderation").isNull()).isTrue();
    }

    @Test
    void theAuthor_canAskForAReview_alwaysTheSameAnswer() throws Exception {
        Person asha = social.person("Asha");
        Person bala = social.person("Bala");
        String collapsed = create(asha, "You [INSULT].", "EVERYONE").get("publicId").asText();
        String clean = create(asha, "A calm evening.", "EVERYONE").get("publicId").asText();
        review(asha, "POST", collapsed).andExpect(status().isAccepted());
        review(asha, "POST", collapsed).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("SELECT origin FROM content_cases c JOIN posts p ON p.id = c.post_id"
                + " WHERE p.public_id = ?", String.class, collapsed)).isEqualTo("AUTHOR_REQUEST");
        review(bala, "POST", collapsed).andExpect(status().isNotFound());
        review(asha, "POST", clean).andExpect(status().isNotFound());
        review(asha, "PERSON", clean).andExpect(status().isBadRequest());
    }

    @Test
    void retention_deletesOldChecks_exceptWhileACaseIsOpen() throws Exception {
        Person asha = social.person("Asha");
        String held = create(asha, "Old [THREAT].", "EVERYONE").get("publicId").asText();
        String clean = create(asha, "Old and calm.", "EVERYONE").get("publicId").asText();
        jdbc.update("UPDATE content_assessments SET created_at = DATEADD('DAY', -100, created_at),"
                + " checked_at = DATEADD('DAY', -100, checked_at)");
        assessments.maintain();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_assessments a JOIN posts p ON p.id = a.post_id"
                + " WHERE p.public_id = ?", Integer.class, clean)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_assessments a JOIN posts p ON p.id = a.post_id"
                + " WHERE p.public_id = ?", Integer.class, held)).isEqualTo(1);
    }

    private ResultActions review(Person who, String kind, String id) throws Exception {
        return mockMvc.perform(post("/api/content-checks/review").header("Authorization", "Bearer " + who.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("targetKind", kind, "targetId", id))));
    }
}
