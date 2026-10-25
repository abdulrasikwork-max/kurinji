package com.safesphere.backend.audit;

import static com.safesphere.backend.support.SocialClient.experienceBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.util.Map;
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
 * Phase 14 (security, privacy and abuse-resistance audit): regression tests for the findings fixed in this phase
 * (docs/PHASE14.md). Each test was run against the code before its fix and failed there.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class Phase14SecurityRegressionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;

    private SocialClient social;
    private Person asha;
    private Person bala;
    private Person chitra;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        social = new SocialClient(mockMvc, objectMapper, users);
        asha = social.person("Asha");
        bala = social.person("Bala");
        chitra = social.person("Chitra");
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    /**
     * P14-1: someone's profile list ordered others' posts by the hidden creation time, so a delayed exact-place post
     * showed up BELOW a post written after it, telling a watcher when the author was really at the place (Q1).
     */
    @Test
    void profileList_ordersOthersPostsByWhenTheyCouldSeeThem_notByTheHiddenCreationTime() throws Exception {
        String cell = GeoCell.encode(12.9 + Math.random() * 0.3, 80.1 + Math.random() * 0.2, GeoCell.BLOCK_PRECISION);
        String placeId = social.place(asha, "Semmozhi Poonga", cell);
        Map<String, Object> exact = experienceBody("EVERYONE", "EXACT");
        exact.put("placeId", placeId);
        String exactId = social.json(social.createPost(asha, exact).andExpect(status().isCreated()))
                .get("publicId").asText();
        clock.advance(Duration.ofMinutes(10));
        String laterId = social.experience(asha, "EVERYONE"); // no place: visible at once
        clock.advance(Duration.ofHours(3).plusMinutes(1));     // the exact post is visible now too

        // The exact post became visible last, so it comes first; the author's own list keeps creation order.
        assertThat(social.postIds(social.profilePosts(chitra, asha, null))).containsExactly(exactId, laterId);
        assertThat(social.postIds(social.minePosts(asha, null))).containsExactly(laterId, exactId);
        // Paging follows the same order.
        assertThat(social.postIds(social.profilePosts(chitra, asha, exactId))).containsExactly(laterId);
        assertThat(social.postIds(social.profilePosts(chitra, asha, laterId))).isEmpty();
    }

    /**
     * P14-2: a moderator who wrote a post could decide the case about a perspective on it (and its appeal), although
     * they are a party to it (they can hide it themselves).
     */
    @Test
    void moderatorWhoWroteThePost_takesNoPartInACaseAboutAPerspectiveOnIt() throws Exception {
        Person mod = social.person("Mod");
        Person mod2 = social.person("Modtwo");
        social.makeModerator(mod);
        social.makeModerator(mod2);
        String post = social.experience(mod, "EVERYONE");
        String perspective = social.commentId(chitra, post, "BEEN_HERE", "Went there too, it was fine.", null);
        social.report(bala, "COMMENT", perspective, "HARASSMENT", null).andExpect(status().isAccepted());
        String caseId = jdbc.queryForObject("SELECT c.public_id FROM content_cases c JOIN comments m ON m.id ="
                + " c.comment_id WHERE m.public_id = ? AND c.status = 'OPEN'", String.class, perspective);

        social.contentCase(mod, caseId).andExpect(status().isForbidden());
        social.act(mod, caseId, "CONTENT_REMOVE", "HARASSMENT").andExpect(status().isForbidden());
        JsonNode queue = social.json(social.contentCases(mod, "OPEN").andExpect(status().isOk()));
        for (JsonNode item : queue) {
            if (item.get("caseId").asText().equals(caseId)) assertThat(item.get("youTookPart").asBoolean()).isTrue();
        }

        // Another moderator decides; the post's author is kept out of the appeal too.
        JsonNode decided = social.json(social.act(mod2, caseId, "CONTENT_REMOVE", "HARASSMENT")
                .andExpect(status().isOk()));
        assertThat(decided.get("summary").get("status").asText()).isEqualTo("CLOSED");
        String decisionId = social.json(social.standing(chitra)).get("decisions").get(0).get("decisionId").asText();
        social.appealDecision(chitra, decisionId).andExpect(status().isAccepted());
        String appealId = jdbc.queryForObject("SELECT a.public_id FROM moderation_appeals a JOIN moderation_actions d"
                + " ON d.id = a.action_id WHERE d.public_id = ?", String.class, decisionId);
        assertThat(social.json(social.contentAppeals(mod)).toString()).doesNotContain(appealId);
        social.decideContentAppeal(mod, appealId, "OVERTURNED").andExpect(status().isForbidden());
    }

    /**
     * P14-3: the same block (or mute) sent twice at the same moment answered 500: the losing insert broke the unique
     * key inside the request's transaction, and "it exists, which is what was asked" could not be honoured there.
     * (The same pattern in reports needs MySQL's V23 keys, which the H2 test schema does not have: checked live.)
     */
    @Test
    void doubleBlocksAndMutes_atTheSameMoment_areAllAccepted() throws Exception {
        java.util.List<Integer> statuses = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        for (int round = 0; round < 12; round++) {
            Person blocker = social.person("Block" + round);
            Person blocked = social.person("Target" + round);
            social.connect(blocker, blocked);
            java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(4);
            java.util.List<Thread> threads = new java.util.ArrayList<>();
            for (Runnable job : java.util.List.<Runnable>of(
                    () -> statuses.add(call(start, () -> social.block(blocker, blocked))),
                    () -> statuses.add(call(start, () -> social.block(blocker, blocked))),
                    () -> statuses.add(call(start, () -> social.mute(blocked, blocker))),
                    () -> statuses.add(call(start, () -> social.mute(blocked, blocker))))) {
                Thread t = new Thread(job);
                threads.add(t);
                t.start();
            }
            for (Thread t : threads) t.join();
            // The block still ended the connection, and exactly one block row exists.
            assertThat(social.publicIds(social.connections(blocker))).doesNotContain(blocked.publicId());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blocks WHERE blocker_id = ? AND blocked_id = ?",
                    Integer.class, blocker.id(), blocked.id())).isEqualTo(1);
        }
        assertThat(statuses).hasSize(48).allMatch(s -> s == 204);
    }

    private interface Call {
        org.springframework.test.web.servlet.ResultActions run() throws Exception;
    }

    private static int call(java.util.concurrent.CyclicBarrier start, Call call) {
        try {
            start.await();
            return call.run().andReturn().getResponse().getStatus();
        } catch (Exception e) {
            return -1;
        }
    }
}
