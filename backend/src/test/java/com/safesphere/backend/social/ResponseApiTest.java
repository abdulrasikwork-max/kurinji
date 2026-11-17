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
import com.safesphere.backend.support.MomentClient;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
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
 * Phase N5-B1 through the real API: Same (names for the author, a band for others, D14), Say more and its one follow-up
 * (D15), I'm in with Add / Not this time / Remove and no explicit decline (D16), Call it off, the one visibility rule
 * (author, connected, stranger, blocked, My people), responses only while the moment is on Today, and the responses
 * pause (N-95).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class ResponseApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;

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

    // ---- Same (D14) ----

    @Test
    void same_givesTheAuthorNames_andOthersOnlyABandOfTheOtherPeople() throws Exception {
        String id = moments.say(asha, "COMMUNITY", "Exams week again");
        JsonNode none = responses(moments.get_(chitra, id));
        assertThat(none.get("same").get("mine").asBoolean()).isFalse();
        assertThat(none.get("same").get("band").isNull()).isTrue();

        same(chitra, id).andExpect(status().isOk()).andExpect(jsonPath("$.responses.same.mine").value(true))
                .andExpect(jsonPath("$.responses.same.band").doesNotExist());
        // Bala sees one other person: "Someone feels the same".
        assertThat(responses(moments.get_(bala, id)).get("same").get("band").asText()).isEqualTo("SOMEONE");
        same(bala, id).andExpect(status().isOk());
        assertThat(responses(moments.get_(bala, id)).get("same").get("band").asText()).isEqualTo("SOMEONE");
        assertThat(responses(moments.get_(dev, id)).get("same").get("band").asText()).isEqualTo("A_FEW");

        // The author gets names (newest first), never a band; nobody else gets the author's lines.
        JsonNode mine = responses(moments.get_(asha, id));
        assertThat(mine.get("same").isNull()).isTrue();
        assertThat(names(mine.get("author").get("same"))).containsExactly("Bala Test", "Chitra Test");
        assertThat(responses(moments.get_(dev, id)).get("author").isNull()).isTrue();

        // Taking it back.
        social.perform(chitra, delete("/api/moments/" + id + "/same")).andExpect(status().isOk())
                .andExpect(jsonPath("$.responses.same.mine").value(false));
        same(chitra, id).andExpect(status().isOk());
        same(chitra, id).andExpect(status().isOk()); // twice = the same answer, one row
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM moment_signals s JOIN moments m ON m.id = s.moment_id"
                + " WHERE m.public_id = ? AND s.kind = 'SAME'", Integer.class, id)).isEqualTo(2);
    }

    @Test
    void manyFeelTheSame_fromSixOtherPeople() throws Exception {
        String id = moments.say(asha, "COMMUNITY", "Canteen closed early");
        List<Person> others = new ArrayList<>();
        for (int i = 0; i < 6; i++) others.add(named("Same" + (char) ('a' + i)));
        for (int i = 0; i < 5; i++) same(others.get(i), id).andExpect(status().isOk());
        assertThat(responses(moments.get_(dev, id)).get("same").get("band").asText()).isEqualTo("A_FEW");
        same(others.get(5), id).andExpect(status().isOk());
        assertThat(responses(moments.get_(dev, id)).get("same").get("band").asText()).isEqualTo("MANY");
        // The answer never carries a number anywhere.
        assertThat(moments.json(moments.get_(dev, id)).toString()).doesNotContainPattern("\"[a-zA-Z]*([Cc]ount|[Tt]otal)\"");
        // The author's line has three names and says there are more.
        JsonNode author = responses(moments.get_(asha, id)).get("author");
        assertThat(author.get("same")).hasSize(3);
        assertThat(author.get("otherSame").asBoolean()).isTrue();
    }

    @Test
    void responses_followTheOneRule_andOnlyWhileTheMomentIsOnToday() throws Exception {
        String forPeople = moments.say(asha, "MY_PEOPLE", "Just for my people");
        same(chitra, forPeople).andExpect(status().isNotFound());           // cannot see it
        same(bala, forPeople).andExpect(status().isOk());                    // connected
        String id = moments.say(asha, "COMMUNITY", "For everyone");
        same(asha, id).andExpect(status().isBadRequest());                   // one's own
        social.block(asha, dev).andExpect(status().isNoContent());
        same(dev, id).andExpect(status().isNotFound());                      // blocked
        String plan = whosIn(asha, null);
        same(chitra, plan).andExpect(status().isBadRequest());               // Same is on a Say only
        social.perform(chitra, put("/api/moments/" + id + "/in")).andExpect(status().isBadRequest());

        clock.advance(Duration.ofHours(25));                                  // it left Today
        same(chitra, id).andExpect(status().isNotFound());
    }

    @Test
    void aKeptMomentAfterItsDay_takesNoNewResponses() throws Exception {
        String id = moments.say(asha, "COMMUNITY", "Worth keeping");
        moments.keep(asha, id).andExpect(status().isOk());
        clock.advance(Duration.ofHours(25));
        moments.get_(chitra, id).andExpect(status().isOk());                 // still readable on the profile
        same(chitra, id).andExpect(status().isConflict());
    }

    @Test
    void aResponsesPause_stopsTapsAndAsks_butNeverLeaving() throws Exception {
        String id = moments.say(asha, "COMMUNITY", "Paused people can read this");
        String plan = whosIn(asha, null);
        social.perform(chitra, put("/api/moments/" + plan + "/in")).andExpect(status().isOk());
        pause(chitra, "COMMENTS_PAUSED");
        same(chitra, id).andExpect(status().isForbidden());
        social.perform(chitra, put("/api/moments/" + plan + "/in")).andExpect(status().isForbidden());
        moments.get_(chitra, id).andExpect(status().isOk());
        social.perform(chitra, delete("/api/moments/" + plan + "/in")).andExpect(status().isNoContent());
    }

    // ---- Say more and the follow-up (D15) ----

    @Test
    void sayMore_letsTheAuthorAddOneFollowUp_onlyAfterSomeoneAsked() throws Exception {
        String id = moments.say(asha, "COMMUNITY", "I finally fixed my project");
        more(asha, id, "Too early").andExpect(status().isConflict());        // nobody asked yet
        social.perform(chitra, put("/api/moments/" + id + "/say-more")).andExpect(status().isOk())
                .andExpect(jsonPath("$.responses.sayMore.mine").value(true));
        assertThat(names(responses(moments.get_(asha, id)).get("author").get("sayMore"))).containsExactly("Chitra Test");
        more(chitra, id, "Not mine").andExpect(status().isNotFound());       // only the author

        more(asha, id, "  It was a missing semicolon.  ").andExpect(status().isCreated())
                .andExpect(jsonPath("$.responses.more.text").value("It was a missing semicolon."))
                .andExpect(jsonPath("$.text").value("I finally fixed my project")); // the original stays
        JsonNode seen = responses(moments.get_(dev, id)).get("more");
        assertThat(seen.get("text").asText()).isEqualTo("It was a missing semicolon.");
        assertThat(seen.get("when").get("day").asText()).isEqualTo("TODAY");
        assertThat(seen.get("at").isNull()).isTrue();                       // exact time: the author only
        more(asha, id, "Again").andExpect(status().isConflict());            // once
        // An answered ask cannot be taken back any more.
        social.perform(chitra, delete("/api/moments/" + id + "/say-more")).andExpect(status().isOk())
                .andExpect(jsonPath("$.responses.sayMore.mine").value(true));
    }

    @Test
    void theFollowUp_followsTheMomentRules() throws Exception {
        String id = moments.say(asha, "COMMUNITY", "Long story");
        social.perform(chitra, put("/api/moments/" + id + "/say-more")).andExpect(status().isOk());
        more(asha, id, "x".repeat(501)).andExpect(status().isBadRequest());
        more(asha, id, "   ").andExpect(status().isBadRequest());
        more(asha, id, "see www.example.com").andExpect(status().isBadRequest()); // a new member: no links
        String plan = whosIn(asha, null);
        more(asha, plan, "plans have no follow-up").andExpect(status().isBadRequest());
        clock.advance(Duration.ofHours(25));
        more(asha, id, "too late").andExpect(status().isConflict());
    }

    // ---- I'm in (D16) ----

    @Test
    void imIn_theAuthorAddsEachPerson_withoutAnExplicitDecline() throws Exception {
        String plan = whosIn(asha, Instant.now(clock).plus(Duration.ofHours(3)));
        social.perform(chitra, put("/api/moments/" + plan + "/in")).andExpect(status().isOk())
                .andExpect(jsonPath("$.responses.plan.you").value("ASKED"))
                .andExpect(jsonPath("$.responses.plan.open").value(true));
        social.perform(dev, put("/api/moments/" + plan + "/in")).andExpect(status().isOk());
        social.perform(bala, put("/api/moments/" + plan + "/in")).andExpect(status().isOk());

        JsonNode people = people(asha, plan);
        assertThat(names(people.get("asking"))).containsExactly("Chitra Test", "Dev Test", "Bala Test");
        assertThat(people.get("asking").get(2).get("myPeople").asBoolean()).isTrue();
        assertThat(people.get("asking").get(0).get("ref").asText()).matches("[0-9a-f-]{36}");
        assertThat(people.toString()).doesNotContain("\"userId\"").doesNotContain("\"id\":");  // never a user id
        String chitraAsk = refOf(people.get("asking"), "Chitra Test");
        String devAsk = refOf(people.get("asking"), "Dev Test");

        // Add Chitra; set Dev aside (silent: nothing changes for Dev).
        social.perform(asha, post("/api/moments/" + plan + "/people/" + chitraAsk + "/add")).andExpect(status().isOk())
                .andExpect(jsonPath("$.in[0].name").value("Chitra Test"));
        social.perform(asha, post("/api/moments/" + plan + "/people/" + devAsk + "/set-aside")).andExpect(status().isOk())
                .andExpect(jsonPath("$.setAside[0].name").value("Dev Test"));
        assertThat(responses(moments.get_(dev, plan)).get("plan").get("you").asText()).isEqualTo("ASKED");
        assertThat(responses(moments.get_(chitra, plan)).get("plan").get("you").asText()).isEqualTo("IN");
        assertThat(names(responses(moments.get_(asha, plan)).get("author").get("asking"))).containsExactly("Bala Test");

        // Someone in sees who is in (with the author); someone who only asked, or a stranger, sees nobody (N-11).
        assertThat(names(people(chitra, plan).get("in"))).containsExactly("Asha Test", "Chitra Test");
        social.perform(dev, get("/api/moments/" + plan + "/people")).andExpect(status().isNotFound());
        social.perform(named("Eve"), get("/api/moments/" + plan + "/people")).andExpect(status().isNotFound());

        // Remove Chitra: she is told she is not in; she cannot ask again.
        social.perform(asha, delete("/api/moments/" + plan + "/people/" + chitraAsk)).andExpect(status().isOk());
        assertThat(responses(moments.get_(chitra, plan)).get("plan").get("you").asText()).isEqualTo("REMOVED");
        social.perform(chitra, put("/api/moments/" + plan + "/in")).andExpect(status().isConflict());
        social.perform(chitra, delete("/api/moments/" + plan + "/in")).andExpect(status().isNotFound());

        // Dev withdraws; asking again works while the plan takes people.
        social.perform(dev, delete("/api/moments/" + plan + "/in")).andExpect(status().isNoContent());
        assertThat(responses(moments.get_(dev, plan)).get("plan").get("you").asText()).isEqualTo("NONE");
        social.perform(dev, put("/api/moments/" + plan + "/in")).andExpect(status().isOk());

        // Only the author answers asks.
        social.perform(bala, post("/api/moments/" + plan + "/people/" + devAsk + "/add")).andExpect(status().isNotFound());
    }

    @Test
    void asks_closeAtThePlanTime_andOnCallOff() throws Exception {
        String plan = whosIn(asha, Instant.now(clock).plus(Duration.ofHours(1)));
        social.perform(chitra, put("/api/moments/" + plan + "/in")).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(61));
        social.perform(dev, put("/api/moments/" + plan + "/in")).andExpect(status().isConflict());
        assertThat(responses(moments.get_(chitra, plan)).get("plan").get("open").asBoolean()).isFalse();
        String ref = refOf(people(asha, plan).get("asking"), "Chitra Test");
        social.perform(asha, post("/api/moments/" + plan + "/people/" + ref + "/add")).andExpect(status().isConflict());

        String other = whosIn(asha, null);
        social.perform(chitra, put("/api/moments/" + other + "/in")).andExpect(status().isOk());
        social.perform(asha, post("/api/moments/" + other + "/call-off")).andExpect(status().isOk())
                .andExpect(jsonPath("$.responses.plan.calledOff").value(true));
        JsonNode seen = responses(moments.get_(dev, other)).get("plan");
        assertThat(seen.get("calledOff").asBoolean()).isTrue();
        assertThat(seen.get("open").asBoolean()).isFalse();
        social.perform(dev, put("/api/moments/" + other + "/in")).andExpect(status().isConflict());
        social.perform(chitra, put("/api/moments/" + other + "/call-off")).andExpect(status().isMethodNotAllowed());
        social.perform(chitra, post("/api/moments/" + other + "/call-off")).andExpect(status().isNotFound());
    }

    @Test
    void blockingTheAuthor_endsTheAsk_andDeletingTheMomentDeletesEverythingUnderIt() throws Exception {
        String plan = whosIn(asha, null);
        social.perform(chitra, put("/api/moments/" + plan + "/in")).andExpect(status().isOk());
        String ref = refOf(people(asha, plan).get("asking"), "Chitra Test");
        social.block(chitra, asha).andExpect(status().isNoContent());
        assertThat(people(asha, plan).get("asking")).isEmpty();             // the author no longer sees her
        social.perform(asha, post("/api/moments/" + plan + "/people/" + ref + "/add")).andExpect(status().isNotFound());

        String id = moments.say(asha, "COMMUNITY", "Delete me");
        same(dev, id).andExpect(status().isOk());
        moments.delete_(asha, id).andExpect(status().isNoContent());
        moments.delete_(asha, plan).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM moment_signals WHERE user_id = ?", Integer.class,
                dev.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plan_people pp WHERE pp.user_id = ?", Integer.class,
                chitra.id())).isZero();
    }

    // ---- helpers ----

    /** A member whose real name is "<name> Test" (every test account starts as "Test User"). */
    private Person named(String name) throws Exception {
        Person p = social.person(name);
        jdbc.update("UPDATE users SET name = ? WHERE id = ?", name + " Test", p.id());
        return p;
    }

    private ResultActions same(Person who, String id) throws Exception {
        return social.perform(who, put("/api/moments/" + id + "/same"));
    }

    private ResultActions more(Person who, String id, String text) throws Exception {
        return social.perform(who, post("/api/moments/" + id + "/more").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("text", text))));
    }

    private JsonNode people(Person who, String id) throws Exception {
        return social.json(social.perform(who, get("/api/moments/" + id + "/people")).andExpect(status().isOk()));
    }

    private JsonNode responses(ResultActions actions) throws Exception {
        return moments.json(actions.andExpect(status().isOk())).get("responses");
    }

    private String whosIn(Person who, Instant at) throws Exception {
        Map<String, Object> body = MomentClient.body("WHOS_IN", "COMMUNITY", "Chai at the canteen?");
        if (at != null) body.put("planAt", at.truncatedTo(ChronoUnit.MINUTES).toString());
        return moments.json(moments.create(who, body).andExpect(status().isCreated())).get("id").asText();
    }

    private void pause(Person who, String level) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        jdbc.update("INSERT INTO social_standing (user_id, restriction, restricted_until, reason, decision, decided_at)"
                + " VALUES (?, ?, ?, 'HARASSMENT', '11111111-2222-3333-4444-555555555555', ?)", who.id(), level,
                now.plusDays(3), now);
    }

    private static List<String> names(JsonNode list) {
        List<String> out = new ArrayList<>();
        list.forEach(n -> out.add(n.isTextual() ? n.asText() : n.get("name").asText()));
        return out;
    }

    private static String refOf(JsonNode list, String name) {
        for (JsonNode n : list) if (n.get("name").asText().equals(name)) return n.get("ref").asText();
        throw new AssertionError("no " + name);
    }
}
