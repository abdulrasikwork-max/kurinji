package com.safesphere.backend.moderation.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.support.StubModerationService;
import com.safesphere.backend.support.TestImages;
import com.safesphere.backend.user.repository.UserRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 13E (E7, E23) with the real 72 h probation: a new account's content waits for an unavailable check, at most
 * 2 hours; a new account's photo post for everyone is looked at by a moderator first.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AssessmentProbationIntegrationTest {

    static final StubModerationService STUB = new StubModerationService();

    @DynamicPropertySource
    static void ai(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:safesphere-ai-probation-test;MODE=MySQL;DB_CLOSE_DELAY=-1");
        registry.add("app.social.probation", () -> "72h");
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

    @Test
    void duringAnOutage_aNewAccountWaits_atMostTwoHours() throws Exception {
        SocialClient social = new SocialClient(mockMvc, objectMapper, users);
        Person fresh = social.person("Fresh");
        Person other = social.person("Other");
        STUB.down = true;
        try {
            JsonNode post = social.json(social.createPost(fresh, SocialClient.experienceBody("EVERYONE", "NONE"))
                    .andExpect(status().isCreated()));
            String id = post.get("publicId").asText();
            assertThat(post.get("moderation").get("state").asText()).isEqualTo("HELD");
            assertThat(post.get("moderation").get("reason").asText()).isEqualTo("AWAITING_CHECK");
            social.getPost(other, id).andExpect(status().isNotFound());
            String ofThisPost = " FROM content_assessments a JOIN posts p ON p.id = a.post_id WHERE p.public_id = ?";
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_cases c JOIN posts p ON p.id = c.post_id"
                    + " WHERE p.public_id = ?", Integer.class, id)).as("waiting for a check is not a case").isZero();

            jdbc.update("UPDATE content_assessments SET created_at = DATEADD('MINUTE', -121, created_at),"
                    + " next_attempt_at = DATEADD('MINUTE', -121, next_attempt_at) WHERE post_id ="
                    + " (SELECT id FROM posts WHERE public_id = ?)", id);
            assessments.maintain();   // the service is still down: published anyway
            social.getPost(other, id).andExpect(status().isOk());
            assertThat(jdbc.queryForMap("SELECT a.status, a.action" + ofThisPost, id))
                    .isEqualTo(Map.of("STATUS", "PENDING", "ACTION", "PUBLISH"));
        } finally {
            STUB.down = false;
        }
    }

    @Test
    void aNewAccountsPhotoPostForEveryone_waitsForAModerator_aConnectionsOneDoesNot() throws Exception {
        SocialClient social = new SocialClient(mockMvc, objectMapper, users);
        Person fresh = social.person("Photos");
        Map<String, Object> body = SocialClient.experienceBody("EVERYONE", "NONE");
        body.put("mediaIds", List.of(social.uploadId(fresh, TestImages.jpeg(40, 30))));
        JsonNode post = social.json(social.createPost(fresh, body).andExpect(status().isCreated()));
        assertThat(post.get("moderation").get("state").asText()).isEqualTo("HELD");
        assertThat(post.get("moderation").get("reason").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc.queryForObject("SELECT origin FROM content_cases c JOIN posts p ON p.id = c.post_id"
                + " WHERE p.public_id = ?", String.class, post.get("publicId").asText())).isEqualTo("SYSTEM");

        Map<String, Object> friends = SocialClient.experienceBody("CONNECTIONS", "NONE");
        friends.put("mediaIds", List.of(social.uploadId(fresh, TestImages.jpeg(40, 30))));
        JsonNode shared = social.json(social.createPost(fresh, friends).andExpect(status().isCreated()));
        assertThat(shared.get("moderation").isNull()).isTrue();
    }
}
