package com.safesphere.backend.moderation.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.support.MomentClient;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.support.StubModerationService;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * Phase N5-B3 (N-96): the automatic text check on messages and on the follow-up, end to end against a stub service:
 * a hold is never delivered (the writer is told; a moderator sees the message alone and whether the two are connected),
 * a collapse is behind a tap and reviewable, self-harm only adds a support line, and while the check is down only a new
 * member's first private reply to someone who is not their connection waits (at most 2 hours).
 */
@SpringBootTest
@AutoConfigureMockMvc
class MessageAssessmentIntegrationTest {

    static final StubModerationService STUB = new StubModerationService();

    @DynamicPropertySource
    static void ai(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:safesphere-message-ai-test;MODE=MySQL;DB_CLOSE_DELAY=-1");
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
    private MomentClient moments;

    @BeforeEach
    void setUp() {
        social = new SocialClient(mockMvc, objectMapper, users);
        moments = new MomentClient(mockMvc, objectMapper);
        STUB.down = false;
    }

    @Test
    void aThreatInAPrivateReply_isNeverDelivered_andAModeratorSeesItAlone() throws Exception {
        Person asha = social.person("Asha");
        Person bala = social.person("Bala");
        Person mod = social.person("Moder");
        social.makeModerator(mod);
        social.connect(asha, bala);
        String id = moments.say(asha, "COMMUNITY", "Who borrowed my calculator?");

        JsonNode started = json(reply(bala, id, "Give it back or [THREAT]").andExpect(status().isCreated()));
        JsonNode own = started.get("messages").get(0);
        assertThat(own.get("notice").get("state").asText()).isEqualTo("HELD");
        assertThat(own.get("notice").get("by").asText()).isEqualTo("SYSTEM");
        String conv = started.get("id").asText();
        assertThat(json(open(asha, conv)).get("messages")).isEmpty();          // never delivered

        String caseId = caseOf(own.get("id").asText());
        JsonNode detail = json(social.contentCase(mod, caseId).andExpect(status().isOk()));
        assertThat(detail.get("summary").get("openedBy").asText()).isEqualTo("SYSTEM");
        JsonNode context = detail.get("content").get("conversation");
        assertThat(context.get("kind").asText()).isEqualTo("REPLY");
        assertThat(context.get("messages")).hasSize(1);
        assertThat(context.get("connected").asBoolean()).isTrue();
        assertThat(detail.get("assessment").get("rule").asText()).isEqualTo("SEVERE");

        social.act(mod, caseId, "CONTENT_KEEP", "NO_VIOLATION").andExpect(status().isOk());
        assertThat(texts(json(open(asha, conv)))).containsExactly("Give it back or [THREAT]");
    }

    @Test
    void anInsultInAPlan_isBehindATap_andTheWriterMayAskForAReview() throws Exception {
        Person asha = social.person("Asha");
        Person chitra = social.person("Chitra");
        String planId = planWith(asha, chitra);
        JsonNode sent = json(send(chitra, planId, "You are a [INSULT] honestly").andExpect(status().isCreated()));
        assertThat(sent.get("notice").get("state").asText()).isEqualTo("COLLAPSED");
        assertThat(sent.get("notice").get("reviewable").asBoolean()).isTrue();
        JsonNode seen = json(open(asha, planId)).get("messages");
        JsonNode last = seen.get(seen.size() - 1);
        assertThat(last.get("collapsed").asBoolean()).isTrue();
        assertThat(last.get("notice").isNull()).isTrue();

        String messageId = sent.get("id").asText();
        review(asha, messageId).andExpect(status().isNotFound());               // only the writer
        review(chitra, messageId).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("SELECT c.origin FROM content_cases c JOIN messages g ON g.id = c.message_id"
                + " WHERE g.public_id = ?", String.class, messageId)).isEqualTo("AUTHOR_REQUEST");
    }

    @Test
    void selfHarm_onlyAddsASupportLineForTheWriter() throws Exception {
        Person asha = social.person("Asha");
        Person chitra = social.person("Chitra");
        String planId = planWith(asha, chitra);
        JsonNode sent = json(send(chitra, planId, "Some days I feel [SELF]").andExpect(status().isCreated()));
        assertThat(sent.get("supportSuggested").asBoolean()).isTrue();
        assertThat(json(open(chitra, planId)).toString()).doesNotContain("supportSuggested\":true");
    }

    @Test
    void duringAnOutage_onlyANewMembersFirstReplyToAStrangerWaits_atMostTwoHours() throws Exception {
        Person asha = established("Asha");
        Person bala = social.person("Bala");      // a new member, connected to Asha
        Person chitra = social.person("Chitra");  // a new member, not connected
        social.connect(asha, bala);
        social.perform(asha, put("/api/social-settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"privateReplies\":\"COMMUNITY\"}")).andExpect(status().isOk());
        String id = moments.say(asha, "COMMUNITY", "Lost: a black umbrella");
        STUB.down = true;

        JsonNode waiting = json(reply(chitra, id, "I think I have it").andExpect(status().isCreated()));
        JsonNode own = waiting.get("messages").get(0);
        assertThat(own.get("notice").get("state").asText()).isEqualTo("HELD");
        String strangerConv = waiting.get("id").asText();
        assertThat(json(open(asha, strangerConv)).get("messages")).isEmpty();

        String friendConv = json(reply(bala, id, "Check the library").andExpect(status().isCreated())).get("id").asText();
        assertThat(texts(json(open(asha, friendConv)))).containsExactly("Check the library");
        String planId = planWith(asha, chitra);
        send(chitra, planId, "On my way").andExpect(status().isCreated());
        assertThat(texts(json(open(asha, planId)))).contains("On my way");     // plans keep working

        // After two hours of outage the waiting reply is delivered.
        jdbc.update("UPDATE content_assessments SET created_at = DATEADD('MINUTE', -121, created_at),"
                + " next_attempt_at = DATEADD('MINUTE', -121, next_attempt_at) WHERE message_id ="
                + " (SELECT id FROM messages WHERE public_id = ?)", own.get("id").asText());
        assessments.maintain();
        assertThat(texts(json(open(asha, strangerConv)))).containsExactly("I think I have it");
    }

    @Test
    void aThreatInTheFollowUp_holdsTheWholeMoment() throws Exception {
        Person asha = social.person("Asha");
        Person chitra = social.person("Chitra");
        String id = moments.say(asha, "COMMUNITY", "Long day at the lab");
        social.perform(chitra, put("/api/moments/" + id + "/say-more")).andExpect(status().isOk());
        JsonNode answer = moments.json(social.perform(asha, post("/api/moments/" + id + "/more")
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Next time [THREAT]\"}"))
                .andExpect(status().isCreated()));
        assertThat(answer.get("notice").get("state").asText()).isEqualTo("HELD");
        moments.get_(chitra, id).andExpect(status().isNotFound());
        assertThat(STUB.seen.get(STUB.seen.size() - 1).text()).contains("Long day at the lab")
                .contains("Next time [THREAT]");
    }

    // ---- helpers ----

    private Person established(String name) throws Exception {
        Person who = social.person(name);
        LocalDateTime then = LocalDateTime.now(ZoneOffset.UTC).minusDays(8);
        jdbc.update("UPDATE memberships SET verified_at = ?, updated_at = ? WHERE user_id = ?", then, then, who.id());
        return who;
    }

    private String planWith(Person author, Person... others) throws Exception {
        String plan = moments.json(moments.create(author, MomentClient.body("WHOS_IN", "COMMUNITY", "Chai?"))
                .andExpect(status().isCreated())).get("id").asText();
        for (Person p : others) social.perform(p, put("/api/moments/" + plan + "/in")).andExpect(status().isOk());
        JsonNode people = json(social.perform(author, get("/api/moments/" + plan + "/people")).andExpect(status().isOk()));
        for (JsonNode ask : people.get("asking")) {
            social.perform(author, post("/api/moments/" + plan + "/people/" + ask.get("ref").asText() + "/add"))
                    .andExpect(status().isOk());
        }
        return moments.json(moments.get_(author, plan).andExpect(status().isOk())).get("responses").get("plan")
                .get("planId").asText();
    }

    private ResultActions reply(Person who, String momentId, String text) throws Exception {
        return social.perform(who, post("/api/moments/" + momentId + "/private-replies")
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("text", text))));
    }

    private ResultActions send(Person who, String conversationId, String text) throws Exception {
        return social.perform(who, post("/api/conversations/" + conversationId + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("text", text))));
    }

    private ResultActions open(Person who, String conversationId) throws Exception {
        return social.perform(who, get("/api/conversations/" + conversationId)).andExpect(status().isOk());
    }

    private ResultActions review(Person who, String messageId) throws Exception {
        return social.perform(who, post("/api/content-checks/review").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("targetKind", "MESSAGE", "targetId", messageId))));
    }

    private String caseOf(String messageId) {
        return jdbc.queryForObject("SELECT c.public_id FROM content_cases c JOIN messages g ON g.id = c.message_id"
                + " WHERE g.public_id = ?", String.class, messageId);
    }

    private static List<String> texts(JsonNode conversation) {
        List<String> out = new ArrayList<>();
        conversation.get("messages").forEach(m -> {
            if (!m.get("text").isNull()) out.add(m.get("text").asText());
        });
        return out;
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
