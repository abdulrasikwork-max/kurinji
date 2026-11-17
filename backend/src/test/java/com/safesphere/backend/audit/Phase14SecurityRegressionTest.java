package com.safesphere.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
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

    // P14-1 (profile list order of delayed posts) and P14-2 (the author of a post deciding about a perspective on it)
    // left with posts and perspectives in N4: a moment has no delay and no perspectives. A moderator who is the author
    // or a reporter still takes no part: MomentModerationApiTest.

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
