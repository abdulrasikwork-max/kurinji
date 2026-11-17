package com.safesphere.backend.moderation.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.social.service.SocialMediaCleanupJob;
import com.safesphere.backend.support.MomentClient;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.support.StubModerationService;
import com.safesphere.backend.support.TestImages;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase N4-B3: the automatic text check on moments, end to end against a stub service (rules only, N-55): publish /
 * collapse / hold, what the author, other members and moderators see, the "where" of a plan, the review request, an
 * outage for an established member and for a new member (at most 2 hours, the 24 hours start at the release), a new
 * member's held photo and "This is about me" never released by the check or its job (N-57, N-52), self-harm never stored.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MomentAssessmentIntegrationTest {

    static final StubModerationService STUB = new StubModerationService();

    @DynamicPropertySource
    static void ai(DynamicPropertyRegistry registry) {
        // Its own in-memory database: a second context must not re-create the shared tables.
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:safesphere-moment-ai-test;MODE=MySQL;DB_CLOSE_DELAY=-1");
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
    @Autowired private SocialMediaCleanupJob cleanup;

    private SocialClient social;
    private MomentClient moments;

    @BeforeEach
    void setUp() {
        social = new SocialClient(mockMvc, objectMapper, users);
        moments = new MomentClient(mockMvc, objectMapper);
        STUB.down = false;
    }

    /** A member past the 7-day new-member period (every fresh test account is a new member). */
    private Person established(String name) throws Exception {
        Person who = social.person(name);
        LocalDateTime then = LocalDateTime.now(ZoneOffset.UTC).minusDays(8);
        jdbc.update("UPDATE memberships SET verified_at = ?, updated_at = ? WHERE user_id = ?", then, then, who.id());
        return who;
    }

    private JsonNode share(Person who, Map<String, ?> body) throws Exception {
        return moments.json(moments.create(who, body).andExpect(status().isCreated()));
    }

    private JsonNode say(Person who, String text) throws Exception {
        return share(who, MomentClient.body("SAY", "COMMUNITY", text));
    }

    private JsonNode own(Person who, String id) throws Exception {
        return moments.json(moments.get_(who, id).andExpect(status().isOk()));
    }

    private static final String OF_MOMENT =
            " FROM content_assessments a JOIN moments m ON m.id = a.moment_id WHERE m.public_id = ?";

    private String row(String momentId, String column) {
        return jdbc.queryForObject("SELECT a." + column + OF_MOMENT, String.class, momentId);
    }

    private int cases(String momentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM content_cases c JOIN moments m ON m.id = c.moment_id"
                + " WHERE m.public_id = ?", Integer.class, momentId);
    }

    private String origin(String momentId) {
        return jdbc.queryForObject("SELECT c.origin FROM content_cases c JOIN moments m ON m.id = c.moment_id"
                + " WHERE m.public_id = ? AND c.status = 'OPEN'", String.class, momentId);
    }

    /** As if the check of this moment had first been tried [minutes] ago and were due again now. */
    private void age(String momentId, int minutes) {
        jdbc.update("UPDATE content_assessments SET created_at = DATEADD('MINUTE', ?, created_at),"
                + " next_attempt_at = DATEADD('MINUTE', ?, next_attempt_at) WHERE moment_id ="
                + " (SELECT id FROM moments WHERE public_id = ?)", -minutes, -minutes, momentId);
    }

    @Test
    void cleanWords_arePublished_andTheAssessmentKeepsNoText() throws Exception {
        Person asha = established("Asha");
        Person bala = established("Bala");
        JsonNode moment = say(asha, "Sunrise from the terrace, come before seven.");
        String id = moment.get("id").asText();
        assertThat(moment.get("status").asText()).isEqualTo("LIVE");
        assertThat(moment.get("notice").isNull()).isTrue();
        assertThat(moment.get("supportSuggested").isNull()).isTrue();
        moments.get_(bala, id).andExpect(status().isOk());

        assertThat(row(id, "status")).isEqualTo("DONE");
        assertThat(row(id, "rule")).isEqualTo("CLEAN");
        assertThat(row(id, "target_kind")).isEqualTo("MOMENT");
        assertThat(row(id, "policy_version")).isEqualTo("policy-1");
        assertThat(jdbc.queryForMap("SELECT a.*" + OF_MOMENT, id).values().toString()).doesNotContain("Sunrise");
        // The service is sent the words and a kind: nothing about the person or the moment.
        StubModerationService.Seen seen = STUB.seen.get(STUB.seen.size() - 1);
        assertThat(seen.fields()).containsExactlyInAnyOrder("kind", "text");
        assertThat(seen.text()).doesNotContain(asha.handle()).doesNotContain(id).doesNotContain(asha.publicId());
        assertThat(cases(id)).isZero();
    }

    @Test
    void aTargetedInsult_isCollapsed_theAuthorIsToldWhy_andMayAskForAReview() throws Exception {
        Person asha = established("Asha");
        Person bala = established("Bala");
        JsonNode moment = say(asha, "You are a [INSULT] honestly.");
        String id = moment.get("id").asText();
        JsonNode notice = moment.get("notice");
        assertThat(notice.get("state").asText()).isEqualTo("COLLAPSED");
        assertThat(notice.get("by").asText()).isEqualTo("SYSTEM");
        assertThat(notice.get("reason").asText()).isEqualTo("HARASSMENT");
        assertThat(notice.get("reviewable").asBoolean()).isTrue();
        assertThat(notice.get("decisionId").isNull()).isTrue();
        assertThat(moment.get("status").asText()).as("collapsed is still shared: its 24 hours run").isEqualTo("LIVE");

        JsonNode seen = moments.json(moments.get_(bala, id).andExpect(status().isOk()));
        assertThat(seen.get("collapsed").asBoolean()).isTrue();
        assertThat(seen.get("notice").isNull()).isTrue();
        assertThat(cases(id)).as("a collapse by the check is not a case until the author asks").isZero();

        // Only the author may ask, and asking twice is the same answer and one case.
        social.askReview(bala, id).andExpect(status().isNotFound());
        social.askReview(asha, id).andExpect(status().isAccepted());
        social.askReview(asha, id).andExpect(status().isAccepted());
        assertThat(cases(id)).isEqualTo(1);
        assertThat(origin(id)).isEqualTo("AUTHOR_REQUEST");
    }

    @Test
    void aSevereThreat_isHeld_aModeratorLooks_andKeepStartsItsDay() throws Exception {
        Person asha = established("Asha");
        Person bala = established("Bala");
        Person mod = established("Mod");
        social.makeModerator(mod);
        JsonNode moment = say(asha, "Delete this or [THREAT].");
        String id = moment.get("id").asText();
        assertThat(moment.get("status").asText()).isEqualTo("WAITING");
        assertThat(moment.get("notice").get("state").asText()).isEqualTo("HELD");
        assertThat(moment.get("notice").get("reason").asText()).isEqualTo("THREAT");
        assertThat(moment.get("visibleAt").isNull()).as("never visible: its 24 hours have not started").isTrue();
        moments.get_(bala, id).andExpect(status().isNotFound());
        assertThat(moments.todayIds(bala, "ALL")).doesNotContain(id);
        assertThat(origin(id)).isEqualTo("SYSTEM");

        String caseId = jdbc.queryForObject("SELECT c.public_id FROM content_cases c JOIN moments m ON m.id ="
                + " c.moment_id WHERE m.public_id = ?", String.class, id);
        JsonNode assessment = social.json(social.contentCase(mod, caseId).andExpect(status().isOk()))
                .get("assessment");
        assertThat(assessment.get("rule").asText()).isEqualTo("SEVERE");
        assertThat(assessment.get("evidence").get(0).get("category").asText()).isEqualTo("THREAT");

        social.act(mod, caseId, "CONTENT_KEEP", "NO_VIOLATION").andExpect(status().isOk());
        moments.get_(bala, id).andExpect(status().isOk());
        JsonNode after = own(asha, id);
        assertThat(after.get("status").asText()).isEqualTo("LIVE");
        assertThat(after.get("notice").isNull()).isTrue();
        Instant visible = Instant.parse(after.get("visibleAt").asText());
        assertThat(Duration.between(visible, Instant.parse(after.get("expiresAt").asText())))
                .isEqualTo(Duration.ofHours(24));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM social_strikes WHERE user_id = ?", Integer.class,
                asha.id())).isZero();
    }

    @Test
    void theWhereOfAPlan_isCheckedToo() throws Exception {
        Person asha = established("Asha");
        Person bala = established("Bala");
        Map<String, Object> body = MomentClient.body("WHOS_IN", "COMMUNITY", "Tea after the lab?");
        body.put("planWhere", "canteen or [THREAT]");
        JsonNode moment = share(asha, body);
        String id = moment.get("id").asText();
        assertThat(moment.get("notice").get("state").asText()).isEqualTo("HELD");
        moments.get_(bala, id).andExpect(status().isNotFound());
        assertThat(STUB.seen.get(STUB.seen.size() - 1).text()).isEqualTo("Tea after the lab?\ncanteen or [THREAT]");
    }

    @Test
    void duringAnOutage_anEstablishedMemberIsPublished_andCheckedLater() throws Exception {
        Person asha = established("Asha");
        Person bala = established("Bala");
        STUB.down = true;
        JsonNode moment = say(asha, "Stop posting or [THREAT].");
        String id = moment.get("id").asText();
        assertThat(moment.get("status").asText()).isEqualTo("LIVE");
        assertThat(moment.get("notice").isNull()).isTrue();
        moments.get_(bala, id).andExpect(status().isOk());
        assertThat(row(id, "status")).isEqualTo("PENDING");
        assertThat(row(id, "action")).isEqualTo("PUBLISH");
        String expires = moment.get("expiresAt").asText();

        STUB.down = false;
        age(id, 5);
        assessments.maintain();
        assertThat(row(id, "status")).isEqualTo("DONE");
        moments.get_(bala, id).andExpect(status().isNotFound());
        JsonNode after = own(asha, id);
        assertThat(after.get("notice").get("state").asText()).isEqualTo("HELD");
        assertThat(after.get("notice").get("reason").asText()).isEqualTo("THREAT");
        assertThat(after.get("expiresAt").asText()).as("a later hold does not move the 24 hours (N-60)")
                .isEqualTo(expires);
        assertThat(origin(id)).isEqualTo("SYSTEM");
    }

    @Test
    void duringAnOutage_aNewMembersWordsWait_atMostTwoHours_thenTheirDayStarts() throws Exception {
        Person fresh = social.person("Fresh");
        Person other = established("Other");
        STUB.down = true;
        JsonNode moment = say(fresh, "First words here.");
        String id = moment.get("id").asText();
        assertThat(moment.get("status").asText()).isEqualTo("WAITING");
        assertThat(moment.get("notice").get("state").asText()).isEqualTo("HELD");
        assertThat(moment.get("notice").get("reason").asText()).isEqualTo("AWAITING_CHECK");
        assertThat(moment.get("notice").get("by").asText()).isEqualTo("SYSTEM");
        moments.get_(other, id).andExpect(status().isNotFound());
        assertThat(cases(id)).as("waiting for a check is not a case").isZero();

        age(id, 119);
        assessments.maintain();   // not two hours yet, the service still down
        moments.get_(other, id).andExpect(status().isNotFound());

        age(id, 2);
        Instant before = Instant.now().minusSeconds(1);
        assessments.maintain();   // two hours are over: published although the service is still down
        moments.get_(other, id).andExpect(status().isOk());
        JsonNode after = own(fresh, id);
        assertThat(after.get("status").asText()).isEqualTo("LIVE");
        assertThat(after.get("notice").isNull()).isTrue();
        Instant visible = Instant.parse(after.get("visibleAt").asText());
        assertThat(visible).as("the 24 hours start at the release, not when it was written").isAfter(before);
        assertThat(Duration.between(visible, Instant.parse(after.get("expiresAt").asText())))
                .isEqualTo(Duration.ofHours(24));
        assertThat(jdbc.queryForMap("SELECT a.status, a.action" + OF_MOMENT, id))
                .isEqualTo(Map.of("STATUS", "PENDING", "ACTION", "PUBLISH"));
    }

    @Test
    void aNewMembersHeldPhoto_isNeverReleasedByTheCheckOrAnyJob() throws Exception {
        Person fresh = social.person("Photos");
        Person other = established("Other");

        // The service answers "clean": the photo still waits for a person.
        String clean = share(fresh, MomentClient.withPhoto("SAY", "COMMUNITY", "Our lab today",
                moments.uploadPhoto(fresh, TestImages.jpeg(40, 30)))).get("id").asText();
        // The service is down: the photo waits for a person, not for the check.
        STUB.down = true;
        JsonNode down = share(fresh, MomentClient.withPhoto("SAY", "COMMUNITY", "Our lab again",
                moments.uploadPhoto(fresh, TestImages.jpeg(40, 30))));
        String waiting = down.get("id").asText();
        assertThat(down.get("notice").get("reason").asText()).isEqualTo("NEW_MEMBER_PHOTO");

        for (String id : new String[] {clean, waiting}) {
            assertThat(origin(id)).isEqualTo("SYSTEM");
            moments.get_(other, id).andExpect(status().isNotFound());
        }
        age(waiting, 121);
        assessments.maintain();       // the 2-hour release is for words only
        age(waiting, 25 * 60);
        assessments.maintain();       // the check gives up after a day
        STUB.down = false;
        assessments.maintain();
        cleanup.run();
        for (String id : new String[] {clean, waiting}) {
            JsonNode mine = own(fresh, id);
            assertThat(mine.get("status").asText()).isEqualTo("WAITING");
            assertThat(mine.get("notice").get("state").asText()).isEqualTo("HELD");
            assertThat(mine.get("notice").get("reason").asText()).isEqualTo("NEW_MEMBER_PHOTO");
            moments.get_(other, id).andExpect(status().isNotFound());
            moments.photo(fresh, id).andExpect(status().isOk());
        }

        // The same photo for "My people" is not held.
        JsonNode mine = share(fresh, MomentClient.withPhoto("SAY", "MY_PEOPLE", "Just us",
                moments.uploadPhoto(fresh, TestImages.jpeg(40, 30))));
        assertThat(mine.get("status").asText()).isEqualTo("LIVE");
    }

    @Test
    void thisIsAboutMe_isNeverReleasedByALaterCleanCheck() throws Exception {
        Person asha = established("Asha");
        Person chitra = established("Chitra");
        Person dev = established("Dev");
        STUB.down = true;
        String id = say(asha, "Guess who failed the viva.").get("id").asText();
        social.aboutMe(chitra, id).andExpect(status().isAccepted());
        moments.get_(dev, id).andExpect(status().isNotFound());

        STUB.down = false;
        age(id, 5);
        assessments.maintain();   // the check finds nothing: the request still waits for a person
        assertThat(row(id, "status")).isEqualTo("DONE");
        moments.get_(dev, id).andExpect(status().isNotFound());
        JsonNode mine = own(asha, id);
        assertThat(mine.get("notice").get("state").asText()).isEqualTo("HELD");
        assertThat(mine.get("notice").get("reason").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(origin(id)).isEqualTo("ABOUT_ME");
    }

    @Test
    void selfHarm_isNeverStored_onlyTheAuthorsOwnAnswerSuggestsSupport() throws Exception {
        Person asha = established("Asha");
        Person bala = established("Bala");
        JsonNode moment = say(asha, "Some days [SELF] feels close.");
        String id = moment.get("id").asText();
        assertThat(moment.get("supportSuggested").asBoolean()).isTrue();
        assertThat(moment.get("notice").isNull()).isTrue();
        assertThat(moment.get("status").asText()).isEqualTo("LIVE");

        assertThat(own(asha, id).get("supportSuggested").isNull()).isTrue();
        assertThat(moments.json(moments.get_(bala, id).andExpect(status().isOk())).get("supportSuggested").isNull())
                .isTrue();
        assertThat(row(id, "rule")).isEqualTo("CLEAN");
        assertThat(jdbc.queryForMap("SELECT a.*" + OF_MOMENT, id).values().toString().toUpperCase())
                .doesNotContain("SELF");
        assertThat(cases(id)).isZero();
    }

    @Test
    void quotedWords_arePublished_andStrongLanguageHoldsNothing() throws Exception {
        Person asha = established("Asha");
        JsonNode quoted = say(asha, "He said \"[QUOTED]\" and we reported it.");
        assertThat(quoted.get("notice").isNull()).isTrue();
        assertThat(row(quoted.get("id").asText(), "rule")).isEqualTo("QUOTED");
        JsonNode strong = say(asha, "That exam was [STRONG] hard.");
        assertThat(strong.get("notice").isNull()).isTrue();
        assertThat(row(strong.get("id").asText(), "rule")).isEqualTo("STRONG_LANGUAGE");
    }
}
