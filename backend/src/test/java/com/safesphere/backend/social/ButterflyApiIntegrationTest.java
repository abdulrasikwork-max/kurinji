package com.safesphere.backend.social;

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
 * Phase 17E (D-3): the butterfly as a reaction and Save as a private bookmark, through the real API. Both idempotent
 * (double taps, lost answers); only other people's posts the viewer can see; the author's private, thresholded number
 * of REACTIONS and nobody else's; saves counted for nobody; the Saved list follows the one Visibility rule; a deleted
 * post takes its reactions and saves with it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ButterflyApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    private SocialClient social;
    private Person asha;      // the author
    private Person bala;      // connected to Asha
    private Person chitra;    // stranger

    @BeforeEach
    void setUp() throws Exception {
        social = new SocialClient(mockMvc, objectMapper, users);
        asha = social.person("Asha");
        bala = social.person("Bala");
        chitra = social.person("Chitra");
        social.connect(asha, bala);
    }

    @Test
    void react_isIdempotent_andSeparateFromSave() throws Exception {
        String post = social.experience(asha, "EVERYONE");
        social.react(bala, post).andExpect(status().isOk()).andExpect(jsonPath("$.marked").value(true));
        social.react(bala, post).andExpect(status().isOk());                    // a double tap: still one row
        assertThat(count("SELECT COUNT(*) FROM butterflies WHERE user_id = ?", bala.id())).isEqualTo(1);
        social.getPost(bala, post).andExpect(jsonPath("$.youReacted").value(true))
                .andExpect(jsonPath("$.saved").value(false));
        assertThat(social.savedIds(social.saved(bala, null))).isEmpty();       // a reaction is not a save

        social.unreact(bala, post).andExpect(status().isNoContent());
        social.unreact(bala, post).andExpect(status().isNoContent());          // nothing there: the same answer
        social.getPost(bala, post).andExpect(jsonPath("$.youReacted").value(false));
        social.unreact(bala, "00000000-0000-4000-8000-000000000000").andExpect(status().isNoContent());
    }

    @Test
    void save_isIdempotent_andWorksOnQuestionsToo() throws Exception {
        String post = social.experience(asha, "EVERYONE");
        social.save(bala, post).andExpect(status().isOk()).andExpect(jsonPath("$.marked").value(true));
        social.save(bala, post).andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM saved_posts WHERE user_id = ?", bala.id())).isEqualTo(1);
        social.getPost(bala, post).andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.youReacted").value(false));
        assertThat(social.savedIds(social.saved(bala, null))).containsExactly(post);

        String ask = ask(asha);
        social.save(chitra, ask).andExpect(status().isOk());
        social.react(chitra, ask).andExpect(status().isBadRequest());          // butterflies are for experiences
        assertThat(social.savedIds(social.saved(chitra, null))).containsExactly(ask);

        social.unsave(bala, post).andExpect(status().isNoContent());
        social.unsave(bala, post).andExpect(status().isNoContent());
        assertThat(social.savedIds(social.saved(bala, null))).isEmpty();
    }

    @Test
    void ownPosts_cannotBeMarked() throws Exception {
        String own = social.experience(asha, "EVERYONE");
        social.react(asha, own).andExpect(status().isBadRequest());
        social.save(asha, own).andExpect(status().isBadRequest());
    }

    @Test
    void onlyVisiblePosts_canBeMarked() throws Exception {
        String onlyMe = social.experience(asha, "ONLY_ME");
        String connections = social.experience(asha, "CONNECTIONS");
        social.react(bala, onlyMe).andExpect(status().isNotFound());
        social.save(bala, onlyMe).andExpect(status().isNotFound());
        social.react(chitra, connections).andExpect(status().isNotFound());
        social.save(chitra, connections).andExpect(status().isNotFound());
        social.react(bala, connections).andExpect(status().isOk());
        social.save(chitra, "00000000-0000-4000-8000-000000000000").andExpect(status().isNotFound());
    }

    @Test
    void theAuthorAlone_learnsHowManyReacted_andOnlyFromThree_savesNeverCount() throws Exception {
        String post = social.experience(asha, "EVERYONE");
        Person dev = social.person("Dev");
        assertThat(tally(asha, post).get("fewerThanThree").asBoolean()).isTrue();
        social.react(bala, post).andExpect(status().isOk());
        social.react(chitra, post).andExpect(status().isOk());
        // Saves by three people move nothing: saving is never counted.
        social.save(bala, post).andExpect(status().isOk());
        social.save(chitra, post).andExpect(status().isOk());
        social.save(dev, post).andExpect(status().isOk());
        JsonNode two = tally(asha, post);
        assertThat(two.get("fewerThanThree").asBoolean()).isTrue();
        assertThat(two.get("count").isNull()).isTrue();
        social.react(dev, post).andExpect(status().isOk());
        JsonNode three = tally(asha, post);
        assertThat(three.get("count").asInt()).isEqualTo(3);
        assertThat(three.get("fewerThanThree").asBoolean()).isFalse();

        // Nobody else learns a number, or anyone's marks but their own.
        for (Person viewer : List.of(bala, chitra, dev)) {
            JsonNode seen = social.json(social.getPost(viewer, post).andExpect(status().isOk()));
            assertThat(seen.get("butterflies").isNull()).isTrue();
            assertThat(seen.get("youReacted").asBoolean()).isTrue();
        }
        social.getPost(asha, post).andExpect(jsonPath("$.youReacted").value(false))
                .andExpect(jsonPath("$.saved").value(false));
        JsonNode mine = social.json(social.minePosts(asha, null).andExpect(status().isOk())).get("items").get(0);
        assertThat(mine.get("butterflies").get("count").asInt()).isEqualTo(3);
        JsonNode theirs = social.json(social.profilePosts(bala, asha, null).andExpect(status().isOk())).get("items").get(0);
        assertThat(theirs.get("butterflies").isNull()).isTrue();
        assertThat(theirs.toString()).doesNotContain("savedBy").doesNotContain("saveCount");
    }

    @Test
    void onlyMePosts_carryNoTally() throws Exception {
        String post = social.experience(asha, "ONLY_ME");
        assertThat(social.json(social.getPost(asha, post).andExpect(status().isOk())).get("butterflies").isNull()).isTrue();
    }

    @Test
    void saved_followsVisibility_hidesAndShowsAgain() throws Exception {
        String post = social.experience(asha, "EVERYONE");
        social.save(chitra, post).andExpect(status().isOk());
        social.block(asha, chitra).andExpect(status().isNoContent());
        assertThat(social.savedIds(social.saved(chitra, null))).isEmpty();
        social.getPost(chitra, post).andExpect(status().isNotFound());
        social.unblock(asha, chitra).andExpect(status().isNoContent());
        assertThat(social.savedIds(social.saved(chitra, null))).containsExactly(post);

        // While hidden it can still be removed: it is Chitra's own row.
        social.block(asha, chitra).andExpect(status().isNoContent());
        social.unsave(chitra, post).andExpect(status().isNoContent());
        social.unblock(asha, chitra).andExpect(status().isNoContent());
        assertThat(social.savedIds(social.saved(chitra, null))).isEmpty();
    }

    @Test
    void deletingAPost_removesItsReactionsAndSaves() throws Exception {
        String post = social.experience(asha, "EVERYONE");
        social.react(bala, post).andExpect(status().isOk());
        social.save(chitra, post).andExpect(status().isOk());
        social.deletePost(asha, post).andExpect(status().isNoContent());
        assertThat(count("SELECT COUNT(*) FROM butterflies WHERE user_id IN (?, ?)", bala.id(), chitra.id())).isZero();
        assertThat(count("SELECT COUNT(*) FROM saved_posts WHERE user_id IN (?, ?)", bala.id(), chitra.id())).isZero();
        assertThat(social.savedIds(social.saved(chitra, null))).isEmpty();
    }

    @Test
    void saved_pagesOf20_bandsOnly() throws Exception {
        List<String> saved = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            String post = social.experience(asha, "EVERYONE");
            social.save(bala, post).andExpect(status().isOk());
            saved.add(0, post); // newest saved first
        }
        JsonNode first = social.json(social.saved(bala, null).andExpect(status().isOk()));
        assertThat(first.get("items")).hasSize(20);
        assertThat(first.get("nextCursor").asText()).isEqualTo(saved.get(19));
        JsonNode item = first.get("items").get(0);
        assertThat(item.get("savedPeriod").asText()).isEqualTo("THIS_WEEK");
        assertThat(item.toString()).doesNotContain("createdAt").doesNotContain("userId");
        List<String> second = social.savedIds(social.saved(bala, first.get("nextCursor").asText()));
        assertThat(second).containsExactly(saved.get(20));
        social.saved(chitra, saved.get(0)).andExpect(status().isBadRequest()); // not Chitra's item
    }

    @Test
    void theOldButterflyAndCocoonRoutes_areGone() throws Exception {
        String post = social.experience(asha, "EVERYONE");
        int put = social.perform(bala, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/posts/{id}/butterfly", post)).andReturn().getResponse().getStatus();
        int cocoon = social.perform(bala, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/cocoon")).andReturn().getResponse().getStatus();
        assertThat(put).isNotEqualTo(200);
        assertThat(cocoon).isNotEqualTo(200);
    }

    private String ask(Person author) throws Exception {
        Map<String, Object> ask = Map.of("type", "ASK", "body", "How do people get back at night?",
                "precision", "NONE", "audience", "EVERYONE", "commentPolicy", "EVERYONE");
        return social.json(social.createPost(author, ask).andExpect(status().isCreated())).get("publicId").asText();
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private JsonNode tally(Person author, String post) throws Exception {
        return social.json(social.getPost(author, post).andExpect(status().isOk())).get("butterflies");
    }
}
