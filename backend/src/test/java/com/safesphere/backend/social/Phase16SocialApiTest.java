package com.safesphere.backend.social;

import static com.safesphere.backend.support.SocialClient.experienceBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.social.policy.EditionPolicy;
import com.safesphere.backend.social.policy.EditionPolicy.Picked;
import com.safesphere.backend.social.policy.EditionPolicy.Reason;
import com.safesphere.backend.social.policy.EditionPolicy.Talked;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * Phase 16 (U1, U2): the readable comment count on a post and "Talking now" in the Edition, through the real API. Each
 * test uses its own launch city (THOOTHUKUDI, TIRUCHIRAPPALLI), which no other test posts in.
 */
@SpringBootTest
@AutoConfigureMockMvc
class Phase16SocialApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    private SocialClient social;
    private Person asha;   // writes
    private Person bala;   // comments
    private Person chitra; // comments
    private Person dev;    // reads

    @BeforeEach
    void setUp() throws Exception {
        social = new SocialClient(mockMvc, objectMapper, users);
        asha = social.person("Asha");
        bala = social.person("Bala");
        chitra = social.person("Chitra");
        dev = social.person("Dev");
    }

    @Test
    void commentCount_isWhatTheViewerCanRead_sameAsTheList() throws Exception {
        String post = cityPost(asha, "THOOTHUKUDI");
        assertCount(dev, post, 0);
        social.commentId(bala, post, "BEEN_HERE", "Went on Sunday, quiet in the morning.", null);
        String hidden = social.commentId(chitra, post, "QUESTION", "Is there parking?", null);
        assertCount(dev, post, 2);

        social.commentAction(asha, hidden, "hide").andExpect(status().isNoContent());
        assertCount(dev, post, 1);    // a hidden perspective is not counted for others...
        assertCount(asha, post, 2);   // ...the post's author still reads it
        assertCount(chitra, post, 2); // ...and so does its writer

        social.block(dev, bala).andExpect(status().isNoContent());
        assertCount(dev, post, 0);    // a blocked writer's perspective is neither shown nor counted
    }

    @Test
    void commentCount_isTheOnlyNumber_andListsCarryIt() throws Exception {
        String post = cityPost(asha, "THOOTHUKUDI");
        social.commentId(bala, post, "BEEN_HERE", "Worth the walk.", null);
        JsonNode answer = social.json(social.getPost(dev, post).andExpect(status().isOk()));
        assertThat(answer.get("commentCount").asInt()).isEqualTo(1);
        assertThat(answer.get("butterflies").isNull()).isTrue(); // still no butterfly number for others
        JsonNode page = social.json(social.profilePosts(dev, asha, null).andExpect(status().isOk()));
        assertThat(page.get("items").get(0).get("commentCount").asInt()).isEqualTo(1);
    }

    @Test
    void talkingNow_postsWithRecentReadablePerspectives_newestPerspectiveFirst() throws Exception {
        String older = cityPost(asha, "TIRUCHIRAPPALLI");
        String newer = cityPost(asha, "TIRUCHIRAPPALLI");
        String quiet = cityPost(asha, "TIRUCHIRAPPALLI");
        String ownOnly = cityPost(asha, "TIRUCHIRAPPALLI");
        shiftPostBack(older, Duration.ofDays(3));
        shiftPostBack(newer, Duration.ofDays(1));

        String onNewer = social.commentId(bala, newer, "BEEN_HERE", "Good chai at the corner.", null);
        String onOlder = social.commentId(chitra, older, "QUESTION", "Open on Mondays?", null);
        social.commentId(asha, ownOnly, "QUESTION", "Anyone been?", null);
        shiftCommentBack(onNewer, Duration.ofHours(5));
        shiftCommentBack(onOlder, Duration.ofHours(1)); // the older post has the newer perspective

        JsonNode edition = social.json(social.edition(dev, "TIRUCHIRAPPALLI").andExpect(status().isOk()));
        assertThat(sectionIds(edition, "TALKING")).containsExactly(older, newer).doesNotContain(quiet, ownOnly);
        assertThat(reasons(edition, "TALKING")).containsExactly("IN_THIS_CITY", "NEW_PERSPECTIVES");

        shiftCommentBack(onOlder, Duration.ofDays(8)); // older than the 7-day window
        edition = social.json(social.edition(dev, "TIRUCHIRAPPALLI").andExpect(status().isOk()));
        assertThat(sectionIds(edition, "TALKING")).containsExactly(newer);

        social.commentAction(asha, onNewer, "hide").andExpect(status().isNoContent());
        edition = social.json(social.edition(dev, "TIRUCHIRAPPALLI").andExpect(status().isOk()));
        assertThat(sectionIds(edition, "TALKING")).isEmpty(); // a hidden perspective puts nothing in "Talking now"
    }

    @Test
    void pickTalking_ordersByNewestPerspectiveOnly_neverByTotals() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 26, 12, 0);
        List<Talked<String>> candidates = List.of(
                new Talked<>("busy", now.minusHours(6), 1),
                new Talked<>("fresh", now.minusMinutes(5), 2),
                new Talked<>("none", null, 3),
                new Talked<>("tie", now.minusHours(6), 4));
        List<Picked<String>> picked = EditionPolicy.pickTalking(candidates, 2);
        assertThat(picked).extracting(Picked::item).containsExactly("fresh", "tie");
        assertThat(picked.get(0).reasons()).containsExactly(Reason.IN_THIS_CITY, Reason.NEW_PERSPECTIVES);
        assertThat(EditionPolicy.pickTalking(candidates, 8)).extracting(Picked::item)
                .containsExactly("fresh", "tie", "busy");
    }

    // ---- helpers ----

    private String cityPost(Person who, String city) throws Exception {
        Map<String, Object> body = experienceBody("EVERYONE", "CITY");
        body.put("city", city);
        return social.json(social.createPost(who, body).andExpect(status().isCreated())).get("publicId").asText();
    }

    private void assertCount(Person viewer, String postId, int expected) throws Exception {
        JsonNode post = social.json(social.getPost(viewer, postId).andExpect(status().isOk()));
        JsonNode list = social.json(social.comments(viewer, postId).andExpect(status().isOk()));
        assertThat(post.get("commentCount").asInt()).isEqualTo(expected);
        assertThat(list.size()).isEqualTo(expected);
    }

    private void shiftPostBack(String postId, Duration by) {
        LocalDateTime at = LocalDateTime.now(ZoneOffset.UTC).minus(by);
        jdbc.update("UPDATE posts SET created_at = ?, visible_from = ? WHERE public_id = ?", at, at, postId);
    }

    private void shiftCommentBack(String commentId, Duration by) {
        LocalDateTime at = LocalDateTime.now(ZoneOffset.UTC).minus(by);
        jdbc.update("UPDATE comments SET created_at = ? WHERE public_id = ?", at, commentId);
    }

    private static List<String> sectionIds(JsonNode edition, String kind) {
        List<String> ids = new ArrayList<>();
        for (JsonNode section : edition.get("sections")) {
            if (!section.get("kind").asText().equals(kind)) continue;
            for (JsonNode item : section.get("items")) ids.add(item.get("post").get("publicId").asText());
        }
        return ids;
    }

    private static List<String> reasons(JsonNode edition, String kind) {
        for (JsonNode section : edition.get("sections")) {
            if (!section.get("kind").asText().equals(kind)) continue;
            List<String> out = new ArrayList<>();
            for (JsonNode r : section.get("items").get(0).get("reasons")) out.add(r.asText());
            return out;
        }
        throw new AssertionError("no section " + kind);
    }
}
