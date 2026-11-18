package com.safesphere.backend.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.common.event.Tell;
import com.safesphere.backend.push.entity.PushType;
import com.safesphere.backend.push.service.StaffWaitingJob;
import com.safesphere.backend.support.CapturingOtpSenderConfig;
import com.safesphere.backend.support.MomentClient;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.support.StubPushSenderConfig;
import com.safesphere.backend.support.TestAccounts;
import com.safesphere.backend.user.entity.UserRole;
import com.safesphere.backend.user.repository.UserRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * N7-B2 (D29, D30, D38) through the real API: what each committed change queues, for whom, about which opaque subject;
 * "one push per plan until you open Replies"; switches; a moderator's Keep never reveals a report; the staff summary.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({StubPushSenderConfig.class, MutableClockConfig.class, CapturingOtpSenderConfig.class})
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:push-producers;MODE=MySQL;DB_CLOSE_DELAY=-1")
class PushProducersTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private StaffWaitingJob staffJob;

    private SocialClient social;
    private MomentClient moments;

    @BeforeEach
    void setUp() {
        clock.reset();
        social = new SocialClient(mockMvc, objectMapper, users);
        moments = new MomentClient(mockMvc, objectMapper);
    }

    private Person withPhone(String name) throws Exception {
        Person p = social.person(name);
        social.perform(p, put("/api/push/device").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("token", "tok:" + UUID.randomUUID() + "x".repeat(60)))))
                .andExpect(status().isNoContent());
        return p;
    }

    /** The kinds queued for a person, with their subjects, oldest first (one event = one row per phone; one phone here). */
    private List<String> queued(Person p) {
        return jdbc.queryForList("SELECT type || ' ' || subject FROM push_messages WHERE user_id = ? ORDER BY id",
                String.class, p.id());
    }

    private List<String> kinds(Person p) {
        return queued(p).stream().map(s -> s.split(" ")[0]).toList();
    }

    private String whosIn(Person author) throws Exception {
        Map<String, Object> body = MomentClient.body("WHOS_IN", "COMMUNITY", "Badminton after class?");
        return moments.json(moments.create(author, body).andExpect(status().isCreated())).get("id").asText();
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private String askRef(Person author, String plan) throws Exception {
        JsonNode asking = json(social.perform(author, get("/api/moments/" + plan + "/people")).andExpect(status().isOk()))
                .get("asking");
        return asking.get(asking.size() - 1).get("ref").asText();
    }

    private ResultActions send(Person who, String conversation, String text) throws Exception {
        return social.perform(who, post("/api/conversations/" + conversation + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("text", text))));
    }

    @Test
    void tellKindsAreThePushKinds() {
        assertThat(Arrays.stream(Tell.Kind.values()).map(Enum::name))
                .containsExactly(Arrays.stream(PushType.values()).map(Enum::name).toArray(String[]::new));
    }

    @Test
    void aPlan_askAddMessagesAndCallOff_tellTheRightPeople_oncePerPlanUntilRepliesIsOpened() throws Exception {
        Person asha = withPhone("Asha");
        Person bala = withPhone("Bala");
        Person chitra = withPhone("Chitra");
        String plan = whosIn(asha);

        social.perform(bala, put("/api/moments/" + plan + "/in")).andExpect(status().isOk());
        assertThat(queued(asha)).containsExactly("PLAN_ASK " + plan);
        social.perform(chitra, put("/api/moments/" + plan + "/in")).andExpect(status().isOk());
        assertThat(kinds(asha)).as("batched into the waiting push").containsExactly("PLAN_ASK");

        social.perform(asha, post("/api/moments/" + plan + "/people/" + askRef(asha, plan) + "/add")).andExpect(status().isOk());
        assertThat(kinds(chitra)).containsExactly("PLAN_ADDED");
        String conversation = queued(chitra).get(0).split(" ")[1];
        assertThat(conversation).isNotEqualTo(plan);
        social.perform(asha, post("/api/moments/" + plan + "/people/" + askRef(asha, plan) + "/add")).andExpect(status().isOk());
        assertThat(kinds(bala)).containsExactly("PLAN_ADDED");

        send(bala, conversation, "I'll bring a racket").andExpect(status().isCreated());
        assertThat(kinds(asha)).containsExactly("PLAN_ASK", "PLAN_MESSAGE");
        assertThat(kinds(chitra)).containsExactly("PLAN_ADDED", "PLAN_MESSAGE");
        assertThat(kinds(bala)).as("never told about one's own words").containsExactly("PLAN_ADDED");

        send(chitra, conversation, "Court 2?").andExpect(status().isCreated());
        assertThat(kinds(asha)).as("once until Asha opens Replies").containsExactly("PLAN_ASK", "PLAN_MESSAGE");
        social.perform(asha, put("/api/replies/opened")).andExpect(status().isNoContent());
        send(chitra, conversation, "See you at 5").andExpect(status().isCreated());
        assertThat(kinds(asha)).containsExactly("PLAN_ASK", "PLAN_MESSAGE", "PLAN_MESSAGE");

        social.perform(asha, post("/api/moments/" + plan + "/call-off")).andExpect(status().isOk());
        assertThat(kinds(bala)).contains("PLAN_CALLED_OFF");
        assertThat(kinds(chitra)).contains("PLAN_CALLED_OFF");
        assertThat(kinds(asha)).doesNotContain("PLAN_CALLED_OFF");
    }

    @Test
    void privateRepliesAndSayMore_tellTheOtherPerson_andSwitchesAreHonoured() throws Exception {
        Person asha = withPhone("Asha");
        Person bala = withPhone("Bala");
        social.connect(asha, bala);
        jdbc.update("DELETE FROM push_messages WHERE user_id IN (?, ?)", asha.id(), bala.id()); // the request and its acceptance
        String say = moments.say(asha, "COMMUNITY", "Anyone else stuck on DBMS?");

        JsonNode started = json(social.perform(bala, post("/api/moments/" + say + "/private-replies")
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("text", "Me!"))))
                .andExpect(status().isCreated()));
        String conversation = started.get("id").asText();
        assertThat(queued(asha)).containsExactly("REPLY_TURN " + conversation);
        send(asha, conversation, "Chapter 4?").andExpect(status().isCreated());
        assertThat(queued(bala)).containsExactly("REPLY_TURN " + conversation);

        social.perform(bala, put("/api/moments/" + say + "/say-more")).andExpect(status().is2xxSuccessful());
        social.perform(asha, post("/api/moments/" + say + "/more").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("text", "Normal forms, mostly")))).andExpect(status().is2xxSuccessful());
        assertThat(queued(bala)).contains("MORE_ADDED " + say);
        assertThat(kinds(asha)).as("Same / Say more never push").containsExactly("REPLY_TURN");

        social.perform(bala, put("/api/push/settings").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("replies", false, "plans", true, "planMessages", true,
                        "people", true, "staff", false)))).andExpect(status().isOk());
        send(asha, conversation, "Wait, I answered twice?").andExpect(status().isConflict()); // turns: Bala's turn
        send(bala, conversation, "Yes, chapter 4").andExpect(status().isCreated());
        send(asha, conversation, "Thanks").andExpect(status().isCreated());
        assertThat(kinds(bala)).as("replies switched off: not queued").containsExactly("REPLY_TURN", "MORE_ADDED");
    }

    @Test
    void aConnectionRequestAndANewSignIn_tellOnlyThePersonConcerned() throws Exception {
        Person asha = withPhone("Asha");
        Person bala = withPhone("Bala");
        social.request(asha, bala).andExpect(status().isAccepted());
        assertThat(queued(bala)).containsExactly("PEOPLE_REQUEST people");
        assertThat(queued(asha)).isEmpty();

        String key = "pp-" + UUID.randomUUID();
        JsonNode first = TestAccounts.registerVerifiedAndLogin(mockMvc, objectMapper, "Dev Test", key);
        long dev = first.get("user").get("id").asLong();
        mockMvc.perform(put("/api/push/device").header("Authorization", "Bearer " + first.get("accessToken").asText())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("token", "dev:" + UUID.randomUUID() + "y".repeat(60)))))
                .andExpect(status().isNoContent());
        TestAccounts.signIn(mockMvc, objectMapper, key, null);
        assertThat(jdbc.queryForList("SELECT type || ' ' || subject FROM push_messages WHERE user_id = ?", String.class, dev))
                .containsExactly("ACCOUNT_SIGN_IN account");
    }

    @Test
    void aModeratorsDecision_tellsTheAuthor_butAKeepNeverRevealsAReport_andStaffHearOncePerHour() throws Exception {
        Person author = withPhone("Asha");
        Person reporter = withPhone("Bala");
        Person moderator = withPhone("Mod");
        users.findById(moderator.id()).ifPresent(u -> {
            u.setRole(UserRole.MODERATOR);
            users.saveAndFlush(u);
        });
        social.perform(moderator, put("/api/push/settings").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("replies", true, "plans", true, "planMessages", true,
                        "people", true, "staff", true)))).andExpect(status().isOk());

        String first = moments.say(author, "COMMUNITY", "Canteen food is great today");
        social.perform(reporter, post("/api/reports").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("targetKind", "MOMENT", "targetId", first, "reason", "SPAM"))))
                .andExpect(status().isAccepted());
        assertThat(staffJob.run()).isEqualTo(1);
        assertThat(staffJob.run()).as("nothing new").isZero();
        assertThat(kinds(moderator)).containsExactly("STAFF_WAITING");

        String caseId = jdbc.queryForObject("SELECT c.public_id FROM content_cases c JOIN moments m ON m.id = c.moment_id"
                + " WHERE m.public_id = ?", String.class, first);
        social.perform(moderator, post("/api/moderation/content-cases/" + caseId + "/actions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("action", "CONTENT_KEEP", "reason", "NO_VIOLATION"))))
                .andExpect(status().isOk());
        assertThat(kinds(author)).as("a Keep of a never-noticed moment says nothing").isEmpty();

        String second = moments.say(author, "COMMUNITY", "Buy cheap followers here");
        social.perform(reporter, post("/api/reports").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("targetKind", "MOMENT", "targetId", second, "reason", "SPAM"))))
                .andExpect(status().isAccepted());
        assertThat(staffJob.run()).as("new work, but within the hour").isZero();
        String secondCase = jdbc.queryForObject("SELECT c.public_id FROM content_cases c JOIN moments m ON m.id = c.moment_id"
                + " WHERE m.public_id = ?", String.class, second);
        social.perform(moderator, post("/api/moderation/content-cases/" + secondCase + "/actions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("action", "CONTENT_REMOVE", "reason", "SPAM"))))
                .andExpect(status().isOk());
        assertThat(queued(author)).containsExactly("CONTENT_DECIDED m-" + second);
        clock.advance(java.time.Duration.ofMinutes(61));
        assertThat(staffJob.run()).as("an hour later, and something new arrived since").isZero(); // the case was closed
    }
}
