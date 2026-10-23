package com.safesphere.backend.social;

import static com.safesphere.backend.support.SocialClient.experienceBody;
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
 * Phase 13C: the butterfly and the Cocoon through the real API (C1-C4, C14): save, Been, back, remove; only other
 * people's visible Experiences; the author's private, thresholded number and nobody else's; the Cocoon follows the one
 * Visibility rule; a deleted post takes its butterflies with it.
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
    void save_been_back_remove() throws Exception {
        String post = social.experience(asha, "EVERYONE");
        social.butterfly(bala, post, null).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("WANT"));
        social.butterfly(bala, post, null).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("WANT"));
        social.getPost(bala, post).andExpect(jsonPath("$.yourButterfly").value("WANT"));
        assertThat(social.cocoonIds(social.cocoon(bala, "WANT", null))).containsExactly(post);

        social.butterfly(bala, post, "BEEN").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("BEEN"));
        assertThat(social.cocoonIds(social.cocoon(bala, "WANT", null))).isEmpty();
        assertThat(social.cocoonIds(social.cocoon(bala, "BEEN", null))).containsExactly(post);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM butterflies WHERE user_id = ? AND been_at IS NOT NULL",
                Integer.class, bala.id())).isEqualTo(1);

        social.butterfly(bala, post, "want").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("WANT"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM butterflies WHERE user_id = ? AND been_at IS NULL",
                Integer.class, bala.id())).isEqualTo(1);

        social.removeButterfly(bala, post).andExpect(status().isNoContent());
        social.removeButterfly(bala, post).andExpect(status().isNotFound());
        social.getPost(bala, post).andExpect(jsonPath("$.yourButterfly").isEmpty());
        social.butterfly(bala, post, "MAYBE").andExpect(status().isBadRequest());
    }

    @Test
    void ownPosts_andAsks_cannotBeSaved() throws Exception {
        String own = social.experience(asha, "EVERYONE");
        social.butterfly(asha, own, null).andExpect(status().isBadRequest());
        Map<String, Object> ask = Map.of("type", "ASK", "body", "How do people get back at night?",
                "precision", "NONE", "audience", "EVERYONE", "commentPolicy", "EVERYONE");
        String askId = social.json(social.createPost(asha, ask).andExpect(status().isCreated())).get("publicId").asText();
        social.butterfly(bala, askId, null).andExpect(status().isBadRequest());
    }

    @Test
    void onlyVisiblePosts_canBeSaved() throws Exception {
        String onlyMe = social.experience(asha, "ONLY_ME");
        String connections = social.experience(asha, "CONNECTIONS");
        social.butterfly(bala, onlyMe, null).andExpect(status().isNotFound());
        social.butterfly(chitra, connections, null).andExpect(status().isNotFound());
        social.butterfly(bala, connections, null).andExpect(status().isOk());
        social.butterfly(chitra, "00000000-0000-4000-8000-000000000000", null).andExpect(status().isNotFound());
    }

    @Test
    void theAuthorAlone_learnsHowMany_andOnlyFromThree() throws Exception {
        String post = social.experience(asha, "EVERYONE");
        Person dev = social.person("Dev");
        assertThat(tally(asha, post).get("fewerThanThree").asBoolean()).isTrue();
        social.butterfly(bala, post, null).andExpect(status().isOk());
        social.butterfly(chitra, post, null).andExpect(status().isOk());
        JsonNode two = tally(asha, post);
        assertThat(two.get("fewerThanThree").asBoolean()).isTrue();
        assertThat(two.get("count").isNull()).isTrue();
        social.butterfly(dev, post, "BEEN").andExpect(status().isOk());
        JsonNode three = tally(asha, post);
        assertThat(three.get("count").asInt()).isEqualTo(3);
        assertThat(three.get("fewerThanThree").asBoolean()).isFalse();

        // Nobody else learns a number, or anyone's butterfly but their own.
        for (Person viewer : List.of(bala, chitra, dev)) {
            JsonNode seen = social.json(social.getPost(viewer, post).andExpect(status().isOk()));
            assertThat(seen.get("butterflies").isNull()).isTrue();
        }
        social.getPost(chitra, post).andExpect(jsonPath("$.yourButterfly").value("WANT"));
        social.getPost(dev, post).andExpect(jsonPath("$.yourButterfly").value("BEEN"));
        social.getPost(asha, post).andExpect(jsonPath("$.yourButterfly").isEmpty());
        // In lists too: the author's own list carries the tally; a profile list seen by others does not.
        JsonNode mine = social.json(social.minePosts(asha, null).andExpect(status().isOk())).get("items").get(0);
        assertThat(mine.get("butterflies").get("count").asInt()).isEqualTo(3);
        JsonNode theirs = social.json(social.profilePosts(bala, asha, null).andExpect(status().isOk())).get("items").get(0);
        assertThat(theirs.get("butterflies").isNull()).isTrue();
    }

    @Test
    void onlyMePosts_carryNoTally() throws Exception {
        String post = social.experience(asha, "ONLY_ME");
        assertThat(social.json(social.getPost(asha, post).andExpect(status().isOk())).get("butterflies").isNull()).isTrue();
    }

    @Test
    void cocoon_followsVisibility_hidesAndShowsAgain() throws Exception {
        String post = social.experience(asha, "EVERYONE");
        social.butterfly(chitra, post, null).andExpect(status().isOk());
        social.block(asha, chitra).andExpect(status().isNoContent());
        assertThat(social.cocoonIds(social.cocoon(chitra, "WANT", null))).isEmpty();
        social.getPost(chitra, post).andExpect(status().isNotFound());
        social.unblock(asha, chitra).andExpect(status().isNoContent());
        assertThat(social.cocoonIds(social.cocoon(chitra, "WANT", null))).containsExactly(post);

        // While hidden it can still be removed: it is Chitra's own row.
        social.block(asha, chitra).andExpect(status().isNoContent());
        social.removeButterfly(chitra, post).andExpect(status().isNoContent());
        social.unblock(asha, chitra).andExpect(status().isNoContent());
        assertThat(social.cocoonIds(social.cocoon(chitra, "WANT", null))).isEmpty();
    }

    @Test
    void deletingAPost_removesItsButterflies() throws Exception {
        String post = social.experience(asha, "EVERYONE");
        social.butterfly(bala, post, null).andExpect(status().isOk());
        social.butterfly(chitra, post, "BEEN").andExpect(status().isOk());
        social.deletePost(asha, post).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM butterflies WHERE user_id IN (?, ?)", Integer.class,
                bala.id(), chitra.id())).isZero();
        assertThat(social.cocoonIds(social.cocoon(bala, "WANT", null))).isEmpty();
    }

    @Test
    void cocoon_pagesOf20_bandsOnly() throws Exception {
        List<String> saved = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            String post = social.experience(asha, "EVERYONE");
            social.butterfly(bala, post, null).andExpect(status().isOk());
            saved.add(0, post); // newest saved first
        }
        JsonNode first = social.json(social.cocoon(bala, null, null).andExpect(status().isOk()));
        assertThat(first.get("items")).hasSize(20);
        assertThat(first.get("nextCursor").asText()).isEqualTo(saved.get(19));
        JsonNode item = first.get("items").get(0);
        assertThat(item.get("savedPeriod").asText()).isEqualTo("THIS_WEEK");
        assertThat(item.toString()).doesNotContain("createdAt").doesNotContain("beenAt").doesNotContain("userId");
        List<String> second = social.cocoonIds(social.cocoon(bala, "WANT", first.get("nextCursor").asText()));
        assertThat(second).containsExactly(saved.get(20));
        social.cocoon(bala, "BEEN", saved.get(0)).andExpect(status().isBadRequest());   // cursor from the other list
        social.cocoon(chitra, "WANT", saved.get(0)).andExpect(status().isBadRequest()); // not Chitra's item
    }

    private JsonNode tally(Person author, String post) throws Exception {
        return social.json(social.getPost(author, post).andExpect(status().isOk())).get("butterflies");
    }
}
