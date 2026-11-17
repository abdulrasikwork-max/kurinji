package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.support.MomentClient;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.support.TestImages;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
 * Phase N5-B4 through the real API: Replies (four sections, names never numbers, the dot against the person's own "last
 * opened" time, Same never lighting it, blocks taking lines away) and This happened (one Say per plan, by a member,
 * after the plan time, with the plan's audience, a line in the plan, the link in answers).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class RepliesApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;

    private SocialClient social;
    private MomentClient moments;
    private Person asha;
    private Person bala;
    private Person chitra;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        social = new SocialClient(mockMvc, objectMapper, users);
        moments = new MomentClient(mockMvc, objectMapper);
        asha = named("Asha");
        bala = named("Bala");
        chitra = named("Chitra");
        social.connect(asha, bala);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    // ---- Replies (D19) ----

    @Test
    void replies_listsWhatNeedsYou_withNamesAndNoNumber() throws Exception {
        String say = moments.say(asha, "COMMUNITY", "Anyone up for the hackathon?");
        String plan = whosIn(asha, null);
        social.perform(bala, put("/api/moments/" + say + "/say-more")).andExpect(status().isOk());
        social.perform(chitra, put("/api/moments/" + plan + "/in")).andExpect(status().isOk());
        social.perform(chitra, put("/api/moments/" + say + "/same")).andExpect(status().isOk());
        reply(bala, say, "Count me in").andExpect(status().isCreated());

        JsonNode page = replies(asha);
        assertThat(types(page.get("needsYou"))).containsExactlyInAnyOrder("ASKS", "SAY_MORE", "YOUR_TURN");
        JsonNode asks = ofType(page.get("needsYou"), "ASKS");
        assertThat(asks.get("people").get(0).get("name").asText()).isEqualTo("Chitra Test");
        assertThat(asks.get("moment").get("id").asText()).isEqualTo(plan);
        assertThat(asks.get("fresh").asBoolean()).isTrue();
        assertThat(asks.get("at").isNull()).isTrue();                          // no exact time outside a conversation
        JsonNode sameLine = page.get("same").get(0);
        assertThat(sameLine.get("people").get(0).get("name").asText()).isEqualTo("Chitra Test");
        assertThat(sameLine.get("fresh").asBoolean()).isFalse();               // Same never lights the dot
        assertThat(page.get("fresh").asBoolean()).isTrue();
        assertThat(page.toString()).doesNotContainPattern("(?i)\"[a-z]*(count|total|seen|read)\"");

        // Bala's side: his reply waits for Asha.
        JsonNode his = replies(bala);
        assertThat(types(his.get("conversations"))).containsExactly("REPLY");
        assertThat(his.get("conversations").get(0).get("turn").asText()).isEqualTo("WAITING");
        assertThat(his.get("conversations").get(0).get("people").get(0).get("name").asText()).isEqualTo("Asha Test");
        // Chitra's side: her ask waits.
        assertThat(types(replies(chitra).get("plans"))).containsExactly("WAITING");
    }

    @Test
    void theDot_comparesWithTheOwnLastOpenedTime_andSameNeverLightsIt() throws Exception {
        String say = moments.say(asha, "COMMUNITY", "Rain dance tonight");
        assertThat(marker(asha)).isFalse();
        social.perform(chitra, put("/api/moments/" + say + "/same")).andExpect(status().isOk());
        assertThat(marker(asha)).isFalse();                                   // Same: no dot
        social.perform(chitra, put("/api/moments/" + say + "/say-more")).andExpect(status().isOk());
        assertThat(marker(asha)).isTrue();
        clock.advance(Duration.ofSeconds(5));
        social.perform(asha, put("/api/replies/opened")).andExpect(status().isNoContent());
        assertThat(marker(asha)).isFalse();
        assertThat(ofType(replies(asha).get("needsYou"), "SAY_MORE").get("fresh").asBoolean()).isFalse();

        // The follow-up reaches the person who asked, as news.
        clock.advance(Duration.ofSeconds(5));
        social.perform(asha, post("/api/moments/" + say + "/more").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Bring an umbrella\"}")).andExpect(status().isCreated());
        JsonNode more = ofType(replies(chitra).get("needsYou"), "MORE");
        assertThat(more.get("moment").get("authorName").asText()).isEqualTo("Asha Test");
        assertThat(more.get("fresh").asBoolean()).isTrue();
        assertThat(types(replies(asha).get("needsYou"))).doesNotContain("SAY_MORE");   // answered
    }

    @Test
    void plans_tellTheAskerWhereTheyStand_neverDeclined() throws Exception {
        String plan = whosIn(asha, Instant.now(clock).plus(Duration.ofHours(2)));
        Person dev = named("Dev");
        social.perform(chitra, put("/api/moments/" + plan + "/in")).andExpect(status().isOk());
        social.perform(dev, put("/api/moments/" + plan + "/in")).andExpect(status().isOk());
        add(asha, plan, "Chitra Test");
        social.perform(asha, post("/api/moments/" + plan + "/people/" + ref(asha, plan, "asking", "Dev Test")
                + "/set-aside")).andExpect(status().isOk());

        JsonNode chitraPlan = replies(chitra).get("plans").get(0);
        assertThat(chitraPlan.get("type").asText()).isEqualTo("PLAN");
        assertThat(chitraPlan.get("conversationId").asText()).isNotBlank();
        assertThat(chitraPlan.get("fresh").asBoolean()).isTrue();             // being added is news
        assertThat(types(replies(dev).get("plans"))).containsExactly("WAITING"); // set aside is silent
        assertThat(types(replies(asha).get("plans"))).containsExactly("PLAN");

        clock.advance(Duration.ofHours(3));                                    // asks closed at the plan time
        assertThat(types(replies(dev).get("plans"))).containsExactly("CLOSED");
        assertThat(replies(dev).get("plans").get(0).get("fresh").asBoolean()).isFalse();

        String other = whosIn(asha, null);
        social.perform(dev, put("/api/moments/" + other + "/in")).andExpect(status().isOk());
        social.perform(asha, post("/api/moments/" + other + "/call-off")).andExpect(status().isOk());
        assertThat(types(replies(dev).get("plans"))).contains("CALLED_OFF");
        assertThat(replies(dev).toString()).doesNotContainPattern("(?i)declin|reject");
    }

    @Test
    void aBlock_takesTheLinesAway() throws Exception {
        String say = moments.say(asha, "COMMUNITY", "New cafe near gate 2");
        reply(bala, say, "Is it open late?").andExpect(status().isCreated());
        social.perform(chitra, put("/api/moments/" + say + "/say-more")).andExpect(status().isOk());
        social.block(asha, chitra).andExpect(status().isNoContent());
        social.block(bala, asha).andExpect(status().isNoContent());
        JsonNode page = replies(asha);
        assertThat(page.get("needsYou")).isEmpty();
        assertThat(page.get("conversations")).isEmpty();
        assertThat(replies(bala).get("conversations")).isEmpty();
    }

    // ---- This happened (D18) ----

    @Test
    void thisHappened_isOneSayPerPlan_byAMember_afterThePlanTime() throws Exception {
        established(asha);
        String plan = whosIn(asha, Instant.now(clock).plus(Duration.ofHours(1)));
        social.perform(chitra, put("/api/moments/" + plan + "/in")).andExpect(status().isOk());
        add(asha, plan, "Chitra Test");

        happened(asha, plan, "COMMUNITY", "We did it").andExpect(status().isConflict());   // before its time
        clock.advance(Duration.ofMinutes(61));
        happened(bala, plan, null, "Not mine").andExpect(status().isNotFound());           // not in the plan
        social.perform(asha, post("/api/moments").contentType(MediaType.APPLICATION_JSON).content(
                objectMapper.writeValueAsString(Map.of("kind", "SAY", "audience", "COMMUNITY", "text", "No photo",
                        "happenedOf", plan)))).andExpect(status().isBadRequest());
        happened(asha, plan, "MY_PEOPLE", "Wrong audience").andExpect(status().isBadRequest());

        JsonNode say = moments.json(happened(asha, plan, null, "Best chai ever").andExpect(status().isCreated()));
        assertThat(say.get("audience").asText()).isEqualTo("COMMUNITY");
        assertThat(say.get("happenedOf").get("id").asText()).isEqualTo(plan);
        assertThat(say.get("happenedOf").get("text").asText()).isEqualTo("Chai at the canteen?");
        JsonNode seen = moments.json(moments.get_(bala, say.get("id").asText()).andExpect(status().isOk()));
        assertThat(seen.get("happenedOf").get("id").asText()).isEqualTo(plan);
        assertThat(seen.toString()).doesNotContain("Chitra");                // names nobody else

        happened(chitra, plan, null, "Me too").andExpect(status().isConflict());         // one per plan
        String planId = moments.json(moments.get_(chitra, plan)).get("responses").get("plan").get("planId").asText();
        JsonNode lines = social.json(social.perform(chitra, get("/api/conversations/" + planId)));
        JsonNode last = lines.get("messages").get(lines.get("messages").size() - 1);
        assertThat(last.get("event").asText()).isEqualTo("HAPPENED");

        // Deleting the plan keeps the Say and drops the link.
        moments.delete_(asha, plan).andExpect(status().isNoContent());
        assertThat(moments.json(moments.get_(bala, say.get("id").asText())).get("happenedOf").isNull()).isTrue();
    }

    /** Found in the N5-C emulator walk: a new member's This happened waits for a moderator; the plan says so on release. */
    @Test
    void thisHappened_thatWaitsForAModerator_isAnnouncedWhenReleased_neverBefore() throws Exception {
        String plan = whosIn(asha, Instant.now(clock).plus(Duration.ofHours(1)));
        social.perform(chitra, put("/api/moments/" + plan + "/in")).andExpect(status().isOk());
        add(asha, plan, "Chitra Test");
        clock.advance(Duration.ofMinutes(61));

        JsonNode say = moments.json(happened(chitra, plan, null, "We played").andExpect(status().isCreated()));
        assertThat(say.get("status").asText()).isEqualTo("WAITING");           // a new member's photo for all of MIET
        String planId = moments.json(moments.get_(asha, plan)).get("responses").get("plan").get("planId").asText();
        assertThat(events(asha, planId)).doesNotContain("HAPPENED");              // a held Say is announced by nobody
        happened(asha, plan, null, "Me too").andExpect(status().isConflict());    // still one per plan

        Person mod = named("Mod");
        social.makeModerator(mod);
        String caseId = jdbc.queryForObject("SELECT c.public_id FROM content_cases c JOIN moments m ON m.id ="
                + " c.moment_id WHERE m.public_id = ?", String.class, say.get("id").asText());
        social.act(mod, caseId, "CONTENT_KEEP", "NO_VIOLATION").andExpect(status().isOk());

        List<String> after = events(asha, planId);
        assertThat(after).containsOnlyOnce("HAPPENED");
        JsonNode lines = social.json(social.perform(chitra, get("/api/conversations/" + planId)));
        JsonNode last = lines.get("messages").get(lines.get("messages").size() - 1);
        assertThat(last.get("event").asText()).isEqualTo("HAPPENED");
        assertThat(last.get("mine").asBoolean()).isTrue();                       // by the person who shared it
    }

    @Test
    void thisHappened_forAMyPeoplePlan_isItsAuthorsAlone() throws Exception {
        Map<String, Object> body = MomentClient.body("WHOS_IN", "MY_PEOPLE", "Study group?");
        String plan = moments.json(moments.create(asha, body).andExpect(status().isCreated())).get("id").asText();
        social.perform(bala, put("/api/moments/" + plan + "/in")).andExpect(status().isOk());
        add(asha, plan, "Bala Test");
        happened(bala, plan, null, "Done").andExpect(status().isConflict());
        JsonNode say = moments.json(happened(asha, plan, null, "Done").andExpect(status().isCreated()));
        assertThat(say.get("audience").asText()).isEqualTo("MY_PEOPLE");
    }

    // ---- helpers ----

    private Person named(String name) throws Exception {
        Person p = social.person(name);
        jdbc.update("UPDATE users SET name = ? WHERE id = ?", name + " Test", p.id());
        return p;
    }

    /** Past the 7-day new-member period (a new member's photo for all of MIET waits for a person, N-57). */
    private void established(Person who) {
        LocalDateTime then = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(8);
        jdbc.update("UPDATE memberships SET verified_at = ?, updated_at = ? WHERE user_id = ?", then, then, who.id());
    }

    private ResultActions happened(Person who, String plan, String audience, String text) throws Exception {
        String mediaId = moments.uploadPhoto(who, TestImages.jpeg(64, 64));
        Map<String, Object> body = new java.util.HashMap<>(MomentClient.withPhoto("SAY", audience, text, mediaId));
        if (audience == null) body.remove("audience");
        body.put("happenedOf", plan);
        return moments.create(who, body);
    }

    private JsonNode replies(Person who) throws Exception {
        return social.json(social.perform(who, get("/api/replies")).andExpect(status().isOk()));
    }

    private boolean marker(Person who) throws Exception {
        return social.json(social.perform(who, get("/api/replies/marker")).andExpect(status().isOk()))
                .get("fresh").asBoolean();
    }

    private ResultActions reply(Person who, String momentId, String text) throws Exception {
        return social.perform(who, post("/api/moments/" + momentId + "/private-replies")
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("text", text))));
    }

    private String whosIn(Person who, Instant at) throws Exception {
        Map<String, Object> body = MomentClient.body("WHOS_IN", "COMMUNITY", "Chai at the canteen?");
        if (at != null) body.put("planAt", at.truncatedTo(ChronoUnit.MINUTES).toString());
        return moments.json(moments.create(who, body).andExpect(status().isCreated())).get("id").asText();
    }

    private void add(Person author, String plan, String name) throws Exception {
        social.perform(author, post("/api/moments/" + plan + "/people/" + ref(author, plan, "asking", name) + "/add"))
                .andExpect(status().isOk());
    }

    private String ref(Person author, String plan, String list, String name) throws Exception {
        JsonNode people = social.json(social.perform(author, get("/api/moments/" + plan + "/people"))
                .andExpect(status().isOk()));
        for (JsonNode n : people.get(list)) if (n.get("name").asText().equals(name)) return n.get("ref").asText();
        throw new AssertionError("no " + name);
    }

    private List<String> events(Person who, String conversationId) throws Exception {
        JsonNode view = social.json(social.perform(who, get("/api/conversations/" + conversationId))
                .andExpect(status().isOk()));
        List<String> out = new ArrayList<>();
        view.get("messages").forEach(m -> { if (m.hasNonNull("event")) out.add(m.get("event").asText()); });
        return out;
    }

    private static List<String> types(JsonNode section) {
        List<String> out = new ArrayList<>();
        section.forEach(i -> out.add(i.get("type").asText()));
        return out;
    }

    private static JsonNode ofType(JsonNode section, String type) {
        for (JsonNode i : section) if (i.get("type").asText().equals(type)) return i;
        throw new AssertionError("no " + type + " in " + section);
    }
}
