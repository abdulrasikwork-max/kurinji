package com.safesphere.backend.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.social.service.SocialMediaCleanupJob;
import com.safesphere.backend.support.MomentClient;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Phase N5-B3 (N-96, N-97): reports about messages in plans and private replies, what a moderator sees (the reported
 * message with up to 10 earlier ones, pseudonyms only), decisions and appeals on messages, and the purge waiting for an
 * open case. The automatic check is off here (MessageAssessmentIntegrationTest covers it).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class MessageModerationApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SocialMediaCleanupJob cleanup;

    private SocialClient social;
    private MomentClient moments;
    private Person asha;
    private Person chitra;
    private Person dev;
    private Person mod;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        social = new SocialClient(mockMvc, objectMapper, users);
        moments = new MomentClient(mockMvc, objectMapper);
        asha = social.person("Asha");
        chitra = social.person("Chitra");
        dev = social.person("Dev");
        mod = social.person("Moder");
        social.makeModerator(mod);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void aReportedMessage_reachesAModerator_withTenEarlierMessagesAndPseudonymsOnly() throws Exception {
        String planId = planWith(asha, chitra, dev);
        List<String> earlier = new ArrayList<>();
        for (int i = 1; i <= 12; i++) earlier.add(send(chitra, planId, "Line " + i));
        String bad = send(dev, planId, "Something nasty");

        social.report(chitra, "MESSAGE", bad, "HARASSMENT", null).andExpect(status().isAccepted());
        social.report(chitra, "MESSAGE", bad, "HARASSMENT", null).andExpect(status().isAccepted()); // same answer
        social.report(dev, "MESSAGE", bad, "HARASSMENT", null).andExpect(status().isBadRequest());   // one's own
        social.report(mod, "MESSAGE", bad, "HARASSMENT", null).andExpect(status().isNotFound());     // not a member
        social.report(chitra, "MESSAGE", "no-such-message", "HARASSMENT", null).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_reports r JOIN messages g ON g.id = r.message_id"
                + " WHERE g.public_id = ?", Integer.class, bad)).isEqualTo(1);

        JsonNode detail = json(social.contentCase(mod, caseOf(bad)).andExpect(status().isOk()));
        assertThat(detail.get("summary").get("targetKind").asText()).isEqualTo("MESSAGE");
        JsonNode content = detail.get("content");
        assertThat(content.get("kind").asText()).isEqualTo("MESSAGE");
        assertThat(content.get("body").asText()).isEqualTo("Something nasty");
        JsonNode context = content.get("conversation");
        assertThat(context.get("kind").asText()).isEqualTo("PLAN");
        assertThat(context.get("connected").isNull()).isTrue();
        JsonNode lines = context.get("messages");
        assertThat(lines).hasSize(11);
        assertThat(lines.get(0).get("text").asText()).isEqualTo("Line 3");
        assertThat(lines.get(10).get("text").asText()).isEqualTo("Something nasty");
        assertThat(lines.get(10).get("reported").asBoolean()).isTrue();
        assertThat(lines.get(0).get("ref").asText()).isNotEqualTo(lines.get(10).get("ref").asText());
        assertThat(detail.toString()).doesNotContain(chitra.handle()).doesNotContain(dev.handle())
                .doesNotContain("Chitra").doesNotContain(planId);
        // The writer and the reporter take no part in the decision.
        social.contentCase(dev, caseOf(bad)).andExpect(status().isForbidden());
    }

    @Test
    void aRemovedMessage_goesFromEveryoneButItsWriter_whoIsToldWhyAndMayAppeal() throws Exception {
        String planId = planWith(asha, chitra, dev);
        String bad = send(dev, planId, "Rude words");
        social.report(chitra, "MESSAGE", bad, "HARASSMENT", null).andExpect(status().isAccepted());
        social.act(mod, caseOf(bad), "CONTENT_REMOVE", "HARASSMENT").andExpect(status().isOk());

        assertThat(texts(conversation(chitra, planId))).doesNotContain("Rude words");
        JsonNode own = message(conversation(dev, planId), bad);
        assertThat(own.get("notice").get("state").asText()).isEqualTo("REMOVED");
        assertThat(own.get("notice").get("by").asText()).isEqualTo("MODERATOR");
        String decision = own.get("notice").get("decisionId").asText();

        JsonNode standing = json(social.standing(dev).andExpect(status().isOk()));
        JsonNode d = standing.get("decisions").get(0);
        assertThat(d.get("targetKind").asText()).isEqualTo("MESSAGE");
        assertThat(d.get("targetId").asText()).isEqualTo(planId);   // where it was written
        assertThat(d.get("canAppeal").asBoolean()).isTrue();
        social.appealDecision(dev, decision).andExpect(status().isAccepted());

        Person other = social.person("Second");
        social.makeModerator(other);
        String appeal = json(social.contentAppeals(other).andExpect(status().isOk())).get(0).get("appealId").asText();
        social.decideContentAppeal(other, appeal, "OVERTURNED").andExpect(status().isOk());
        assertThat(texts(conversation(chitra, planId))).contains("Rude words");
    }

    @Test
    void aMessageWithAnOpenCase_waitsForTheDecision_beforeThePurge() throws Exception {
        String planId = planWith(asha, chitra, dev);
        String bad = send(dev, planId, "Keep this for the moderator");
        social.report(chitra, "MESSAGE", bad, "SPAM", null).andExpect(status().isAccepted());
        String caseId = caseOf(bad);
        clock.advance(Duration.ofDays(9));
        cleanup.run();
        assertThat(count("SELECT COUNT(*) FROM messages WHERE public_id = ?", bad)).isEqualTo(1);
        social.act(mod, caseOf(bad), "CONTENT_KEEP", "NO_VIOLATION").andExpect(status().isOk());
        cleanup.run();
        assertThat(count("SELECT COUNT(*) FROM messages WHERE public_id = ?", bad)).isZero();
        assertThat(count("SELECT COUNT(*) FROM content_cases WHERE public_id = ?", caseId)).isZero();
    }

    @Test
    void deletingTheMoment_takesItsMessagesCasesAndReportsWithIt() throws Exception {
        String plan = moments.json(moments.create(asha, MomentClient.body("WHOS_IN", "COMMUNITY", "Movie?"))
                .andExpect(status().isCreated())).get("id").asText();
        String planId = addAll(plan, asha, chitra, dev);
        String bad = send(dev, planId, "Report me");
        social.report(chitra, "MESSAGE", bad, "SPAM", null).andExpect(status().isAccepted());
        String caseId = caseOf(bad);
        moments.delete_(asha, plan).andExpect(status().isNoContent());
        assertThat(count("SELECT COUNT(*) FROM content_cases WHERE public_id = ?", caseId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM content_reports WHERE reporter_id = ? AND message_id IS NOT NULL",
                chitra.id())).isZero();
    }

    // ---- helpers ----

    /** A Who's in? by [author] with [others] added; returns the plan's conversation id. */
    private String planWith(Person author, Person... others) throws Exception {
        String plan = moments.json(moments.create(author, MomentClient.body("WHOS_IN", "COMMUNITY", "Chai?"))
                .andExpect(status().isCreated())).get("id").asText();
        return addAll(plan, author, others);
    }

    private String addAll(String plan, Person author, Person... others) throws Exception {
        for (Person p : others) {
            if (p == author) continue;
            social.perform(p, put("/api/moments/" + plan + "/in")).andExpect(status().isOk());
        }
        JsonNode people = json(social.perform(author, get("/api/moments/" + plan + "/people")).andExpect(status().isOk()));
        for (JsonNode ask : people.get("asking")) {
            social.perform(author, post("/api/moments/" + plan + "/people/" + ask.get("ref").asText() + "/add"))
                    .andExpect(status().isOk());
        }
        return moments.json(moments.get_(author, plan).andExpect(status().isOk())).get("responses").get("plan")
                .get("planId").asText();
    }

    private String send(Person who, String conversationId, String text) throws Exception {
        return json(social.perform(who, post("/api/conversations/" + conversationId + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("text", text))))
                .andExpect(status().isCreated())).get("id").asText();
    }

    private JsonNode conversation(Person who, String id) throws Exception {
        return json(social.perform(who, get("/api/conversations/" + id)).andExpect(status().isOk()));
    }

    private static JsonNode message(JsonNode conversation, String id) {
        for (JsonNode m : conversation.get("messages")) if (m.get("id").asText().equals(id)) return m;
        throw new AssertionError("no message " + id);
    }

    private static List<String> texts(JsonNode conversation) {
        List<String> out = new ArrayList<>();
        conversation.get("messages").forEach(m -> {
            if (!m.get("text").isNull()) out.add(m.get("text").asText());
        });
        return out;
    }

    private String caseOf(String messageId) {
        return jdbc.queryForObject("SELECT c.public_id FROM content_cases c JOIN messages g ON g.id = c.message_id"
                + " WHERE g.public_id = ?", String.class, messageId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
