package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.social.policy.MomentTimes;
import com.safesphere.backend.support.MomentClient;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.support.TestAccounts;
import com.safesphere.backend.user.entity.UserRole;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase N4-B1 (N-49): the daily question: written by admins only, one per India calendar day, pinned on Today that
 * day; answers are ordinary moments that carry it; nothing is counted.
 *
 * The test database is shared and MIET has one question per day, so each test clears the questions first.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class DailyQuestionApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;

    private SocialClient social;
    private MomentClient moments;
    private Person admin;
    private Person asha;
    private Person bala;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        jdbc.update("UPDATE moments SET question_id = NULL");
        jdbc.update("DELETE FROM daily_questions");
        social = new SocialClient(mockMvc, objectMapper, users);
        moments = new MomentClient(mockMvc, objectMapper);
        admin = social.person("Admin");
        var user = users.findById(admin.id()).orElseThrow();
        user.setRole(UserRole.ADMIN);
        users.saveAndFlush(user);
        asha = social.person("Asha");
        bala = social.person("Bala");
    }

    @AfterEach
    void cleanUp() {
        clock.reset();
        jdbc.update("UPDATE moments SET question_id = NULL");
        jdbc.update("DELETE FROM daily_questions");
    }

    private LocalDate today() {
        return MomentTimes.indiaDay(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    private String add(LocalDate day, String text) throws Exception {
        return moments.json(moments.addQuestion(admin, day.toString(), text).andExpect(status().isCreated()))
                .get("id").asText();
    }

    @Test
    void onlyAnAdmin_writesAndSeesTheSchedule() throws Exception {
        moments.addQuestion(asha, today().toString(), "Not mine to ask").andExpect(status().isForbidden());
        moments.questions(asha).andExpect(status().isForbidden());
        moments.deleteQuestion(asha, UUID.randomUUID().toString()).andExpect(status().isForbidden());

        Person moderator = social.person("Moderator");
        social.makeModerator(moderator);
        moments.addQuestion(moderator, today().toString(), "Moderators do not either").andExpect(status().isForbidden());

        // An admin who is not a member of a community has no wall to pin a question on.
        String outsider = TestAccounts.registerUnverified(mockMvc, objectMapper, "Root Admin",
                "question." + UUID.randomUUID() + "@example.com").get("accessToken").asText();
        long outsiderId = objectMapper.readTree(new String(java.util.Base64.getUrlDecoder()
                .decode(outsider.split("[.]")[1]))).get("sub").asLong();
        var user = users.findById(outsiderId).orElseThrow();
        user.setRole(UserRole.ADMIN);
        users.saveAndFlush(user);
        moments.addQuestion(new Person(outsider, outsiderId, "-", "-"), today().toString(), "No community yet")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MEMBERSHIP_NEEDED"));
    }

    @Test
    void oneQuestionPerIndiaDay_todayOrLater_noEditing() throws Exception {
        JsonNode created = moments.json(moments.addQuestion(admin, today().toString(),
                "  What made you  smile today? ").andExpect(status().isCreated()));
        assertThat(created.get("text").asText()).isEqualTo("What made you smile today?");
        assertThat(created.get("day").asText()).isEqualTo(today().toString());
        List<String> names = new ArrayList<>();
        created.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("id", "day", "text", "createdAt");   // no answers, no counts

        moments.addQuestion(admin, today().toString(), "A second one").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("That day already has a question. Delete it first to write another."));
        add(today().plusDays(1), "Tomorrow's question?");
        add(today().plusDays(60), "The furthest day?");

        moments.addQuestion(admin, today().minusDays(1).toString(), "Yesterday?").andExpect(status().isBadRequest());
        moments.addQuestion(admin, today().plusDays(61).toString(), "Too far?").andExpect(status().isBadRequest());
        moments.addQuestion(admin, "16-11-2026", "Wrong format?").andExpect(status().isBadRequest());
        moments.addQuestion(admin, null, "No day?").andExpect(status().isBadRequest());
        moments.addQuestion(admin, today().plusDays(2).toString(), "  ").andExpect(status().isBadRequest());
        moments.addQuestion(admin, today().plusDays(2).toString(), "q".repeat(141)).andExpect(status().isBadRequest());
        add(today().plusDays(2), "q".repeat(140));

        JsonNode list = moments.json(moments.questions(admin).andExpect(status().isOk()));
        assertThat(list).hasSize(4);
        assertThat(list.get(0).get("day").asText()).isEqualTo(today().toString());          // oldest first
        assertThat(list.get(3).get("day").asText()).isEqualTo(today().plusDays(60).toString());

        // No editing: delete and write again.
        moments.deleteQuestion(admin, created.get("id").asText()).andExpect(status().isNoContent());
        moments.deleteQuestion(admin, created.get("id").asText()).andExpect(status().isNotFound());
        add(today(), "What are you looking forward to?");
    }

    @Test
    void todaysQuestion_isPinnedOnToday_andAnswersAreOrdinaryMomentsThatCarryIt() throws Exception {
        assertThat(moments.json(moments.today(asha, null, null).andExpect(status().isOk())).get("question").isNull())
                .isTrue();
        String tomorrow = add(today().plusDays(1), "What will you do tomorrow?");
        assertThat(moments.json(moments.today(asha, null, null)).get("question").isNull()).isTrue();

        String question = add(today(), "What made you smile today?");
        JsonNode pinned = moments.json(moments.today(asha, null, null).andExpect(status().isOk())).get("question");
        assertThat(pinned.get("id").asText()).isEqualTo(question);
        assertThat(pinned.get("text").asText()).isEqualTo("What made you smile today?");
        List<String> names = new ArrayList<>();
        pinned.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("id", "text");                         // nothing is counted
        // Pinned on both views of the wall.
        assertThat(moments.json(moments.today(asha, "MINE", null)).get("question").get("id").asText())
                .isEqualTo(question);

        Map<String, Object> answer = MomentClient.body("SAY", "COMMUNITY", "The canteen dosa");
        answer.put("questionId", question);
        JsonNode own = moments.json(moments.create(asha, answer).andExpect(status().isCreated()));
        assertThat(own.get("question").get("id").asText()).isEqualTo(question);
        String id = own.get("id").asText();
        // An answer is an ordinary moment: on Today like any other, with the question it answers.
        JsonNode seen = moments.json(moments.get_(bala, id).andExpect(status().isOk()));
        assertThat(seen.get("question").get("text").asText()).isEqualTo("What made you smile today?");
        assertThat(moments.wholeToday(bala, "ALL")).contains(id);
        // Any number of answers; none is ranked or counted.
        moments.create(asha, answer).andExpect(status().isCreated());

        // Only today's question can be answered.
        Map<String, Object> early = MomentClient.body("SAY", "COMMUNITY", "Too early");
        early.put("questionId", tomorrow);
        moments.create(asha, early).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("That question is no longer today's question"));
        Map<String, Object> unknown = MomentClient.body("SAY", "COMMUNITY", "Unknown");
        unknown.put("questionId", UUID.randomUUID().toString());
        moments.create(asha, unknown).andExpect(status().isBadRequest());

        // The next India day: yesterday's question is no longer pinned or answerable; its answers still carry it
        // for the rest of their 24 hours.
        clock.advance(Duration.ofHours(24));
        JsonNode nextDay = moments.json(moments.today(asha, null, null).andExpect(status().isOk())).get("question");
        assertThat(nextDay.get("id").asText()).isEqualTo(tomorrow);
        moments.create(asha, answer).andExpect(status().isBadRequest());
        clock.reset();

        // The admin deletes the question: the answers stay, as plain moments.
        moments.deleteQuestion(admin, question).andExpect(status().isNoContent());
        assertThat(moments.json(moments.get_(bala, id).andExpect(status().isOk())).get("question").isNull()).isTrue();
        assertThat(moments.json(moments.today(asha, null, null)).get("question").isNull()).isTrue();
    }
}
