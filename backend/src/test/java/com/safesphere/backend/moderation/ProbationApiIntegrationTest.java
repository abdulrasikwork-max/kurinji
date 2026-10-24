package com.safesphere.backend.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.user.repository.UserRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 13D (M16): new accounts are on probation for the real 72 h: lower posting limits, no links, and their reports
 * do not count yet (they still reach a moderator).
 */
@SpringBootTest
@AutoConfigureMockMvc
// Its own in-memory database: a second Spring context on the shared one would drop and re-create the tables under the
// cached main context (ids start again at 1 while that context's in-memory limiters still remember the old ids).
@TestPropertySource(properties = {"app.social.probation=72h",
        "spring.datasource.url=jdbc:h2:mem:safesphere-probation-test;MODE=MySQL;DB_CLOSE_DELAY=-1"})
class ProbationApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void newAccounts_postLess_shareNoLinks_andTheirReportsWait() throws Exception {
        SocialClient social = new SocialClient(mockMvc, objectMapper, users);
        Person fresh = social.person("Fresh");
        Map<String, Object> link = SocialClient.experienceBody("EVERYONE", "NONE");
        link.put("body", "Book at www.example.com for the boat.");
        social.createPost(fresh, link).andExpect(status().isBadRequest());
        String post = social.experience(fresh, "EVERYONE");
        social.comment(fresh, post, "QUESTION", "See https://example.com first?", null).andExpect(status().isBadRequest());
        social.experience(fresh, "EVERYONE");
        social.createPost(fresh, SocialClient.experienceBody("EVERYONE", "NONE")).andExpect(status().isTooManyRequests());

        Person author = social.person("Author");
        String target = social.experience(author, "EVERYONE");
        for (Person r : List.of(social.person("New1"), social.person("New2"), social.person("New3"))) {
            social.report(r, "POST", target, "HARASSMENT", null).andExpect(status().isAccepted());
        }
        assertThat(jdbc.queryForObject("SELECT moderation FROM posts WHERE public_id = ?", String.class, target))
                .isEqualTo("NONE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_reports r JOIN posts p ON p.id = r.post_id"
                + " WHERE p.public_id = ? AND r.counts_from > r.created_at", Integer.class, target)).isEqualTo(3);
    }
}
