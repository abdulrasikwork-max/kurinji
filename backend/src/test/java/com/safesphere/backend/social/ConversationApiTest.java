package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.social.entity.ResponseEnums.ConversationKind;
import com.safesphere.backend.social.entity.ResponseEnums.PrivateReplies;
import com.safesphere.backend.social.policy.ConversationPolicy;
import com.safesphere.backend.social.policy.ConversationPolicy.Turn;
import com.safesphere.backend.social.service.SocialMediaCleanupJob;
import com.safesphere.backend.support.MomentClient;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * Phase N5-B2 through the real API: private replies (the author's setting, strict turns, one per moment, End, the close
 * a day after the moment leaves Today, a block ends it for both), plans (opened by the first Add, quiet lines, leaving,
 * removal, Call it off), the responses pause, and the purge of response data (N-98).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class ConversationApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SocialMediaCleanupJob cleanup;

    private SocialClient social;
    private MomentClient moments;
    private Person asha;     // the author
    private Person bala;     // connected to Asha
    private Person chitra;   // a member, not connected
    private Person dev;      // a member, not connected

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        social = new SocialClient(mockMvc, objectMapper, users);
        moments = new MomentClient(mockMvc, objectMapper);
        asha = named("Asha");
        bala = named("Bala");
        chitra = named("Chitra");
        dev = named("Dev");
        social.connect(asha, bala);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void thePolicy_takesTurnsInAReply_andFollowsTheSetting() {
        assertThat(ConversationPolicy.allows(PrivateReplies.MY_PEOPLE, true)).isTrue();
        assertThat(ConversationPolicy.allows(PrivateReplies.MY_PEOPLE, false)).isFalse();
        assertThat(ConversationPolicy.allows(null, false)).isFalse();             // no setting = My people
        assertThat(ConversationPolicy.allows(PrivateReplies.COMMUNITY, false)).isTrue();
        assertThat(ConversationPolicy.allows(PrivateReplies.NOBODY, true)).isFalse();
        assertThat(ConversationPolicy.turn(ConversationKind.REPLY, true, false, 7L, 7)).isEqualTo(Turn.WAITING);
        assertThat(ConversationPolicy.turn(ConversationKind.REPLY, true, false, 8L, 7)).isEqualTo(Turn.YOUR_TURN);
        assertThat(ConversationPolicy.turn(ConversationKind.PLAN, true, false, 7L, 7)).isEqualTo(Turn.OPEN);
        assertThat(ConversationPolicy.turn(ConversationKind.REPLY, false, false, 8L, 7)).isEqualTo(Turn.CLOSED);
        assertThat(ConversationPolicy.turn(ConversationKind.REPLY, true, true, 8L, 7)).isEqualTo(Turn.ENDED);
    }

    // ---- private replies (D17) ----

    @Test
    void aPrivateReply_followsTheAuthorsSetting_myPeopleByDefault() throws Exception {
        String id = moments.say(asha, "COMMUNITY", "Anyone else stuck on DBMS?");
        assertThat(responses(chitra, id).get("canReply").asBoolean()).isFalse();
        reply(chitra, id, "Me!").andExpect(status().isForbidden());
        assertThat(responses(bala, id).get("canReply").asBoolean()).isTrue();
        reply(bala, id, "Yes, chapter 4").andExpect(status().isCreated());

        setting(asha, "COMMUNITY").andExpect(status().isOk()).andExpect(jsonPath("$.privateReplies").value("COMMUNITY"));
        reply(chitra, id, "Me too").andExpect(status().isCreated());
        setting(asha, "NOBODY").andExpect(status().isOk());
        reply(dev, id, "And me").andExpect(status().isForbidden());
        setting(asha, "EVERYONE").andExpect(status().isBadRequest());
        social.perform(asha, get("/api/social-settings")).andExpect(jsonPath("$.privateReplies").value("NOBODY"));
        reply(asha, id, "to myself").andExpect(status().isBadRequest());
    }

    @Test
    void aPrivateReply_takesStrictTurns_onePerMomentAndPerson() throws Exception {
        String id = moments.say(asha, "COMMUNITY", "Lost my blue water bottle");
        JsonNode started = json(reply(bala, id, "I saw one in the library").andExpect(status().isCreated()));
        String conv = started.get("id").asText();
        assertThat(started.get("kind").asText()).isEqualTo("REPLY");
        assertThat(started.get("you").get("turn").asText()).isEqualTo("WAITING");
        assertThat(started.get("people").get(0).get("name").asText()).isEqualTo("Asha Test");
        assertThat(started.get("messages").get(0).get("at").asText()).isNotBlank();   // exact time for members

        send(bala, conv, "Second floor").andExpect(status().isConflict());          // nobody sends twice in a row
        reply(bala, id, "Again").andExpect(status().isConflict());                     // one per moment and person
        JsonNode r = responses(bala, id);
        assertThat(r.get("replyId").asText()).isEqualTo(conv);
        assertThat(r.get("canReply").asBoolean()).isFalse();

        JsonNode forAsha = json(open(asha, conv).andExpect(status().isOk()));
        assertThat(forAsha.get("you").get("turn").asText()).isEqualTo("YOUR_TURN");
        assertThat(forAsha.get("moment").get("mine").asBoolean()).isTrue();
        send(asha, conv, "Thank you! Going now").andExpect(status().isCreated()).andExpect(jsonPath("$.mine").value(true));
        send(asha, conv, "Got it").andExpect(status().isConflict());
        send(bala, conv, "Glad it helped").andExpect(status().isCreated());

        open(chitra, conv).andExpect(status().isNotFound());                           // not a member
        send(chitra, conv, "hi").andExpect(status().isNotFound());
        // No message ever says whether anyone read it.
        assertThat(forAsha.toString()).doesNotContainPattern("(?i)\"(seen|read|delivered|readAt|seenAt)\"");
    }

    @Test
    void endingAReply_makesItReadOnlyForBoth_andAPauseNeverTrapsAnyone() throws Exception {
        String id = moments.say(asha, "COMMUNITY", "Rain again");
        String conv = json(reply(bala, id, "Bring an umbrella").andExpect(status().isCreated())).get("id").asText();
        pause(bala, "COMMENTS_PAUSED");
        send(bala, conv, "x").andExpect(status().isForbidden());
        JsonNode paused = json(open(bala, conv).andExpect(status().isOk()));
        assertThat(paused.get("you").get("paused").asBoolean()).isTrue();
        assertThat(paused.get("you").get("canSend").asBoolean()).isFalse();
        social.perform(bala, post("/api/conversations/" + conv + "/end")).andExpect(status().isNoContent());
        JsonNode ended = json(open(asha, conv).andExpect(status().isOk()));
        assertThat(ended.get("open").asBoolean()).isFalse();
        assertThat(ended.get("you").get("turn").asText()).isEqualTo("ENDED");
        assertThat(lastEvent(ended)).isEqualTo("ENDED");
        send(asha, conv, "wait").andExpect(status().isConflict());
    }

    @Test
    void aReply_closesADayAfterTheMomentLeavesToday_andStaysReadable() throws Exception {
        String id = moments.say(asha, "COMMUNITY", "Late night canteen?");
        String conv = json(reply(bala, id, "Open till 11").andExpect(status().isCreated())).get("id").asText();
        clock.advance(Duration.ofHours(24 + 23));
        send(asha, conv, "Thanks").andExpect(status().isCreated());                  // still open
        clock.advance(Duration.ofHours(2));
        send(bala, conv, "Anytime").andExpect(status().isConflict());
        JsonNode closed = json(open(bala, conv).andExpect(status().isOk()));
        assertThat(closed.get("open").asBoolean()).isFalse();
        assertThat(closed.get("you").get("turn").asText()).isEqualTo("CLOSED");
    }

    @Test
    void aBlock_endsAReplyForBoth_atOnceAndSilently() throws Exception {
        String id = moments.say(asha, "COMMUNITY", "Who took my seat");
        String conv = json(reply(bala, id, "Not me").andExpect(status().isCreated())).get("id").asText();
        social.block(bala, asha).andExpect(status().isNoContent());
        open(asha, conv).andExpect(status().isNotFound());
        open(bala, conv).andExpect(status().isNotFound());
        send(asha, conv, "?").andExpect(status().isNotFound());
    }

    // ---- plans (D18) ----

    @Test
    void aPlan_opensAtTheFirstAdd_forTheAuthorAndThePeopleIn() throws Exception {
        String plan = whosIn(asha);
        assertThat(responses(asha, plan).get("plan").get("planId").isNull()).isTrue();
        social.perform(chitra, put("/api/moments/" + plan + "/in")).andExpect(status().isOk());
        social.perform(dev, put("/api/moments/" + plan + "/in")).andExpect(status().isOk());
        assertThat(responses(chitra, plan).get("plan").get("planId").isNull()).isTrue();   // asked, not in
        add(asha, plan, "Chitra Test");
        String planId = responses(asha, plan).get("plan").get("planId").asText();
        assertThat(responses(chitra, plan).get("plan").get("planId").asText()).isEqualTo(planId);
        assertThat(responses(dev, plan).get("plan").get("planId").isNull()).isTrue();

        JsonNode view = json(open(chitra, planId).andExpect(status().isOk()));
        assertThat(view.get("kind").asText()).isEqualTo("PLAN");
        assertThat(names(view.get("people"))).containsExactly("Asha Test", "Chitra Test");
        assertThat(lastEvent(view)).isEqualTo("IN");
        assertThat(view.get("you").get("turn").asText()).isEqualTo("OPEN");
        send(chitra, planId, "I'll bring cards").andExpect(status().isCreated());
        send(chitra, planId, "And snacks").andExpect(status().isCreated());           // a plan has no turns
        open(dev, planId).andExpect(status().isNotFound());                             // only asked

        add(asha, plan, "Dev Test");
        JsonNode forDev = json(open(dev, planId).andExpect(status().isOk()));
        assertThat(texts(forDev)).containsExactly("I'll bring cards", "And snacks");

        // Chitra leaves: "can't make it"; she loses the plan.
        social.perform(chitra, delete("/api/moments/" + plan + "/in")).andExpect(status().isNoContent());
        assertThat(lastEvent(json(open(dev, planId).andExpect(status().isOk())))).isEqualTo("LEFT");
        open(chitra, planId).andExpect(status().isNotFound());

        // Removing Dev writes no line for anyone; he loses the plan too.
        String devRef = refOf(people(asha, plan).get("in"), "Dev Test");
        social.perform(asha, delete("/api/moments/" + plan + "/people/" + devRef)).andExpect(status().isOk());
        assertThat(lastEvent(json(open(asha, planId).andExpect(status().isOk())))).isEqualTo("LEFT");
        open(dev, planId).andExpect(status().isNotFound());
    }

    @Test
    void callingItOff_saysSoAndClosesThePlan() throws Exception {
        String plan = whosIn(asha);
        social.perform(chitra, put("/api/moments/" + plan + "/in")).andExpect(status().isOk());
        add(asha, plan, "Chitra Test");
        String planId = responses(asha, plan).get("plan").get("planId").asText();
        social.perform(asha, post("/api/moments/" + plan + "/call-off")).andExpect(status().isOk());
        JsonNode view = json(open(chitra, planId).andExpect(status().isOk()));
        assertThat(view.get("open").asBoolean()).isFalse();
        assertThat(lastEvent(view)).isEqualTo("CALLED_OFF");
        assertThat(view.get("moment").get("calledOff").asBoolean()).isTrue();
        send(chitra, planId, "Oh no").andExpect(status().isConflict());
        // Leaving is still possible after the plan closed.
        social.perform(chitra, delete("/api/moments/" + plan + "/in")).andExpect(status().isNoContent());
    }

    // ---- lifecycle (N-98) ----

    @Test
    void responseData_goesSevenDaysAfterTheEnd_alsoUnderAKeptMoment_andWithADeletedMoment() throws Exception {
        String kept = moments.say(asha, "COMMUNITY", "Keep this one");
        social.perform(bala, put("/api/moments/" + kept + "/same")).andExpect(status().isOk());
        reply(bala, kept, "Nice").andExpect(status().isCreated());
        moments.keep(asha, kept).andExpect(status().isOk());
        String gone = moments.say(asha, "COMMUNITY", "Delete this one");
        reply(bala, gone, "ok").andExpect(status().isCreated());
        moments.delete_(asha, gone).andExpect(status().isNoContent());
        assertThat(count("SELECT COUNT(*) FROM conversations WHERE replier_id = ?", bala.id())).isEqualTo(1);

        clock.advance(Duration.ofDays(9));
        cleanup.run();
        moments.get_(bala, kept).andExpect(status().isOk());                          // the kept moment stays
        assertThat(count("SELECT COUNT(*) FROM conversations WHERE replier_id = ?", bala.id())).isZero();
        assertThat(count("SELECT COUNT(*) FROM messages WHERE author_id = ?", bala.id())).isZero();
        assertThat(count("SELECT COUNT(*) FROM moment_signals WHERE user_id = ?", bala.id())).isZero();
    }

    // ---- helpers ----

    private Person named(String name) throws Exception {
        Person p = social.person(name);
        jdbc.update("UPDATE users SET name = ? WHERE id = ?", name + " Test", p.id());
        return p;
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
        return social.perform(who, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/conversations/" + conversationId));
    }

    private ResultActions setting(Person who, String value) throws Exception {
        return social.perform(who, put("/api/social-settings").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("privateReplies", value))));
    }

    private void add(Person author, String plan, String name) throws Exception {
        String ref = refOf(people(author, plan).get("asking"), name);
        social.perform(author, post("/api/moments/" + plan + "/people/" + ref + "/add")).andExpect(status().isOk());
    }

    private JsonNode people(Person who, String id) throws Exception {
        return social.json(social.perform(who, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/moments/" + id + "/people")).andExpect(status().isOk()));
    }

    private JsonNode responses(Person who, String id) throws Exception {
        return moments.json(moments.get_(who, id).andExpect(status().isOk())).get("responses");
    }

    private String whosIn(Person who) throws Exception {
        Map<String, Object> body = MomentClient.body("WHOS_IN", "COMMUNITY", "Board games tonight?");
        return moments.json(moments.create(who, body).andExpect(status().isCreated())).get("id").asText();
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private void pause(Person who, String level) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        jdbc.update("INSERT INTO social_standing (user_id, restriction, restricted_until, reason, decision, decided_at)"
                + " VALUES (?, ?, ?, 'HARASSMENT', '11111111-2222-3333-4444-555555555555', ?)", who.id(), level,
                now.plusDays(3), now);
    }

    private int count(String sql, long id) {
        return jdbc.queryForObject(sql, Integer.class, id);
    }

    private static String lastEvent(JsonNode view) {
        JsonNode list = view.get("messages");
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).get("kind").asText().equals("SYSTEM")) return list.get(i).get("event").asText();
        }
        return null;
    }

    private static List<String> texts(JsonNode view) {
        List<String> out = new ArrayList<>();
        view.get("messages").forEach(m -> {
            if (m.get("kind").asText().equals("TEXT")) out.add(m.get("text").asText());
        });
        return out;
    }

    private static List<String> names(JsonNode list) {
        List<String> out = new ArrayList<>();
        list.forEach(n -> out.add(n.get("name").asText()));
        return out;
    }

    private static String refOf(JsonNode list, String name) {
        for (JsonNode n : list) if (n.get("name").asText().equals(name)) return n.get("ref").asText();
        throw new AssertionError("no " + name);
    }
}
