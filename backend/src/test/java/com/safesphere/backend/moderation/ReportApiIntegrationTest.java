package com.safesphere.backend.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.user.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 13D: reports through the real API (plan M3-M6). Only what the reporter can see can be reported; reporting twice
 * changes nothing; the reported person observes nothing until 3 independent counting reports collapse the content, and
 * then nothing mentions reports; linked reporters and paused reporters do not add up; people reports never act alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    private SocialClient social;
    private Person asha;   // the author
    private Person bala;   // connected to Asha
    private Person chitra; // stranger

    @BeforeEach
    void setUp() throws Exception {
        social = new SocialClient(mockMvc, objectMapper, users);
        asha = social.person("Asha");
        bala = social.person("Bala");
        chitra = social.person("Chitra");
        social.connect(asha, bala);
    }

    @Test
    void reports_needAVisibleTarget_aReason_andAreFiledOnce() throws Exception {
        String post = social.experience(asha, "EVERYONE");
        String connectionsOnly = social.experience(asha, "CONNECTIONS");
        social.report(bala, "POST", post, "HARASSMENT", null).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RECEIVED"));
        social.report(bala, "POST", post, "SPAM", null).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RECEIVED"));
        assertThat(reportsBy(bala)).isEqualTo(1);

        social.report(chitra, "POST", connectionsOnly, "HARASSMENT", null).andExpect(status().isNotFound());
        social.report(chitra, "POST", "00000000-0000-4000-8000-000000000000", "SPAM", null)
                .andExpect(status().isNotFound());
        social.report(asha, "POST", post, "SPAM", null).andExpect(status().isBadRequest());
        social.report(asha, "PERSON", asha.publicId(), "SPAM", null).andExpect(status().isBadRequest());
        social.report(chitra, "POST", post, "BORING", null).andExpect(status().isBadRequest());
        social.report(chitra, "POST", post, "NEEDS_REVIEW", null).andExpect(status().isBadRequest());
        social.report(chitra, "PLACE", post, "SPAM", null).andExpect(status().isBadRequest());
        social.report(chitra, "POST", post, "IMPERSONATION", null).andExpect(status().isBadRequest());
        social.report(chitra, "POST", post, "OTHER", null).andExpect(status().isBadRequest());
        social.report(chitra, "POST", post, "SPAM", "a note").andExpect(status().isBadRequest());
        social.report(chitra, "POST", post, "OTHER", "x".repeat(201)).andExpect(status().isBadRequest());
        social.report(chitra, "POST", post, "OTHER", "Shares a stranger's phone number").andExpect(status().isAccepted());
        social.report(chitra, "PERSON", asha.publicId(), "IMPERSONATION", null).andExpect(status().isAccepted());
        assertThat(reportsBy(chitra)).isEqualTo(2);
    }

    @Test
    void theReportedPerson_observesNothing_untilThreeIndependentReports() throws Exception {
        String post = social.experience(asha, "EVERYONE");
        String comment = social.commentId(bala, post, "BEEN_HERE", "Went there too, lovely.", null);
        social.report(bala, "POST", post, "HARASSMENT", null).andExpect(status().isAccepted());
        social.report(chitra, "POST", post, "HARASSMENT", null).andExpect(status().isAccepted());
        social.report(asha, "COMMENT", comment, "SPAM", null).andExpect(status().isAccepted());

        // Two reports: everything looks exactly as before, to the author and to everyone else.
        JsonNode own = social.json(social.getPost(asha, post).andExpect(status().isOk()));
        assertThat(own.get("moderation").isNull()).isTrue();
        assertThat(social.json(social.getPost(chitra, post)).get("collapsed").isNull()).isTrue();
        assertThat(social.json(social.standing(asha).andExpect(status().isOk())).get("decisions")).isEmpty();
        assertThat(social.json(social.standing(bala).andExpect(status().isOk())).get("decisions")).isEmpty();
        social.editPost(asha, post, edit("Edited later; nothing was refused.")).andExpect(status().isOk());

        // The third independent report collapses it, visibly, without a word about reports.
        Person dev = social.person("Dev");
        social.report(dev, "POST", post, "HATE", null).andExpect(status().isAccepted());
        social.getPost(chitra, post).andExpect(status().isOk()).andExpect(jsonPath("$.collapsed").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.moderation").isEmpty());
        social.getPost(asha, post).andExpect(jsonPath("$.moderation.state").value("COLLAPSED"))
                .andExpect(jsonPath("$.moderation.reason").isEmpty())
                .andExpect(jsonPath("$.moderation.decisionId").isEmpty())
                .andExpect(jsonPath("$.collapsed").isEmpty());
        assertThat(social.postIds(social.profilePosts(chitra, asha, null))).contains(post);
        String text = social.json(social.getPost(asha, post)).toString().toLowerCase();
        assertThat(text).doesNotContain("report");

        // Deleting it is never refused (a refusal would tell the author someone reported it).
        social.deletePost(asha, post).andExpect(status().isNoContent());
    }

    @Test
    void reportersWhoKeepReportingTogether_countOnce() throws Exception {
        Person r1 = social.person("Ravi");
        Person r2 = social.person("Rani");
        Person r3 = social.person("Raj");
        Person other = social.person("Other");
        for (int i = 0; i < 3; i++) {
            String decoy = social.experience(other, "EVERYONE");
            for (Person r : List.of(r1, r2, r3)) social.report(r, "POST", decoy, "SPAM", null)
                    .andExpect(status().isAccepted());
        }
        String target = social.experience(asha, "EVERYONE");
        for (Person r : List.of(r1, r2, r3)) {
            social.report(r, "POST", target, "HARASSMENT", null).andExpect(status().isAccepted());
        }
        assertThat(state("posts", target)).isEqualTo("NONE");
        // Two independent people join: three independent reports now.
        social.report(bala, "POST", target, "HARASSMENT", null).andExpect(status().isAccepted());
        assertThat(state("posts", target)).isEqualTo("NONE");
        social.report(chitra, "POST", target, "HARASSMENT", null).andExpect(status().isAccepted());
        assertThat(state("posts", target)).isEqualTo("COLLAPSED");
    }

    @Test
    void pausedReporters_doNotCount_andPeopleReports_neverActAlone() throws Exception {
        String post = social.experience(asha, "EVERYONE");
        Person dev = social.person("Dev");
        jdbc.update("INSERT INTO social_standing (user_id, restriction, restricted_until, reason, decision, decided_at)"
                + " VALUES (?, 'COMMENTS_PAUSED', DATEADD('DAY', 3, CURRENT_TIMESTAMP), 'SPAM',"
                + " '11111111-1111-4111-8111-111111111111', CURRENT_TIMESTAMP)", dev.id());
        for (Person r : List.of(bala, chitra, dev)) social.report(r, "POST", post, "HARASSMENT", null)
                .andExpect(status().isAccepted());
        assertThat(state("posts", post)).isEqualTo("NONE");

        for (Person r : List.of(bala, chitra, social.person("Esha"), social.person("Farah"))) {
            social.report(r, "PERSON", asha.publicId(), "HARASSMENT", null).andExpect(status().isAccepted());
        }
        social.profile(chitra, asha).andExpect(status().isOk());
        assertThat(social.json(social.standing(asha)).get("restriction").isNull()).isTrue();
        social.experience(asha, "EVERYONE");
    }

    @Test
    void perspectives_collapse_atThree_andWritersSeeTheirOwnState() throws Exception {
        String post = social.experience(asha, "EVERYONE");
        String comment = social.commentId(chitra, post, "BEEN_HERE", "Not worth the trip.", null);
        for (Person r : List.of(asha, bala, social.person("Dev"))) {
            social.report(r, "COMMENT", comment, "HARASSMENT", null).andExpect(status().isAccepted());
        }
        JsonNode seen = find(social.json(social.comments(bala, post).andExpect(status().isOk())), comment);
        assertThat(seen.get("collapsed").asText()).isEqualTo("UNDER_REVIEW");
        assertThat(seen.get("body").asText()).isEqualTo("Not worth the trip.");
        JsonNode writer = find(social.json(social.comments(chitra, post)), comment);
        assertThat(writer.get("moderation").get("state").asText()).isEqualTo("COLLAPSED");
        assertThat(writer.get("collapsed").isNull()).isTrue();
    }

    @Test
    void reporters_seeOnlyTheirOwnReports_withoutNamesOrCounts() throws Exception {
        String post = social.experience(asha, "EVERYONE");
        social.report(bala, "POST", post, "SPAM", null).andExpect(status().isAccepted());
        social.report(chitra, "POST", post, "HATE", null).andExpect(status().isAccepted());
        JsonNode mine = social.json(social.myReports(bala).andExpect(status().isOk()));
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("reason").asText()).isEqualTo("SPAM");
        assertThat(mine.get(0).get("status").asText()).isEqualTo("RECEIVED");
        assertThat(mine.get(0).get("period").asText()).isEqualTo("THIS_WEEK");
        List<String> fields = new ArrayList<>();
        mine.get(0).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("reportId", "targetKind", "reason", "status", "outcome", "period");
        assertThat(social.json(social.myReports(asha))).isEmpty();
    }

    @Test
    void reportsPerDay_areLimited() throws Exception {
        Person author = social.person("Prolific");
        Person reporter = social.person("Busy");
        for (int i = 0; i < 20; i++) {
            social.report(reporter, "POST", social.experience(author, "EVERYONE"), "SPAM", null)
                    .andExpect(status().isAccepted());
        }
        social.report(reporter, "POST", social.experience(author, "EVERYONE"), "SPAM", null)
                .andExpect(status().isTooManyRequests());
    }

    private Map<String, Object> edit(String body) {
        Map<String, Object> request = SocialClient.experienceBody("EVERYONE", "NONE");
        request.remove("type");
        request.put("body", body);
        return request;
    }

    private int reportsBy(Person who) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM content_reports WHERE reporter_id = ?", Integer.class, who.id());
    }

    private String state(String table, String publicId) {
        return jdbc.queryForObject("SELECT moderation FROM " + table + " WHERE public_id = ?", String.class, publicId);
    }

    private static JsonNode find(JsonNode list, String publicId) {
        for (JsonNode item : list) if (item.get("publicId").asText().equals(publicId)) return item;
        throw new AssertionError("not in the list: " + publicId);
    }
}
