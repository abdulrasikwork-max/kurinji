package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.social.entity.Block;
import com.safesphere.backend.social.policy.InviteCodes;
import com.safesphere.backend.social.repository.BlockRepository;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
 * Phase 13A through the real API: invite codes are shown once, stored hashed, work once for 7 days, connect at once,
 * and every unusable code (unknown, used, expired, revoked, own, blocked either way) gets the same 404.
 */
@SpringBootTest(properties = {"app.social.redeems-per-hour=1000", "app.social.invites-per-day=1000"})
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class InviteApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private BlockRepository blocks;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private SocialClient social;

    @BeforeEach
    void setUp() {
        social = new SocialClient(mockMvc, objectMapper, users);
        clock.reset();
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void redeeming_connectsAtOnce_andTheCodeWorksOnlyOnce() throws Exception {
        Person asha = social.person("Asha");
        Person bala = social.person("Bala");
        Person chitra = social.person("Chitra");
        JsonNode invite = social.json(social.createInvite(asha).andExpect(status().isCreated()));
        String code = invite.get("code").asText();
        assertThat(code).hasSize(26);

        String typed = (code.substring(0, 13) + " " + code.substring(13)).toLowerCase();
        social.redeem(bala, typed).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONNECTED"))
                .andExpect(jsonPath("$.person.publicId").value(asha.publicId()));
        assertThat(social.publicIds(social.connections(asha))).containsExactly(bala.publicId());

        social.redeem(chitra, code).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("This invite code does not work"));
        assertThat(social.json(social.invites(asha))).isEmpty(); // used = no longer active
    }

    @Test
    void theCodeIsShownOnce_andOnlyItsHashIsStored() throws Exception {
        Person asha = social.person("Asha");
        JsonNode invite = social.json(social.createInvite(asha).andExpect(status().isCreated()));
        String code = invite.get("code").asText();

        JsonNode listed = social.json(social.invites(asha)).get(0);
        assertThat(listed.get("id").asText()).isEqualTo(invite.get("id").asText());
        assertThat(listed.has("code")).isFalse();

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM connection_invites WHERE public_id = ?", invite.get("id").asText());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).toString()).doesNotContain(code);
        assertThat(rows.get(0).get("CODE_HASH")).isEqualTo(InviteCodes.hash(code));
    }

    @Test
    void expired_revoked_own_unknown_andMalformedCodes_allGetTheSame404() throws Exception {
        Person asha = social.person("Asha");
        Person bala = social.person("Bala");

        String own = social.json(social.createInvite(asha)).get("code").asText();
        social.redeem(asha, own).andExpect(status().isNotFound());

        JsonNode revoked = social.json(social.createInvite(asha));
        social.revokeInvite(asha, revoked.get("id").asText()).andExpect(status().isNoContent());
        social.redeem(bala, revoked.get("code").asText()).andExpect(status().isNotFound());

        social.redeem(bala, InviteCodes.newCode()).andExpect(status().isNotFound());
        social.redeem(bala, "not-a-code").andExpect(status().isNotFound());

        String expiring = social.json(social.createInvite(asha)).get("code").asText();
        clock.advance(Duration.ofDays(7).plusMinutes(1));
        social.redeem(bala, expiring).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("This invite code does not work"));
    }

    @Test
    void aBlockEitherWay_makesTheCodeNotWork() throws Exception {
        Person asha = social.person("Asha");
        Person bala = social.person("Bala");
        String code = social.json(social.createInvite(asha)).get("code").asText();
        blocks.saveAndFlush(new Block(asha.id(), bala.id(), LocalDateTime.now(ZoneOffset.UTC)));
        social.redeem(bala, code).andExpect(status().isNotFound());
    }

    @Test
    void activeInvites_areLimited_andOnlyTheOwnerCanListOrRevokeThem() throws Exception {
        Person asha = social.person("Asha");
        Person bala = social.person("Bala");
        String firstId = null;
        for (int i = 0; i < 5; i++) {
            String id = social.json(social.createInvite(asha).andExpect(status().isCreated())).get("id").asText();
            if (firstId == null) firstId = id;
        }
        social.createInvite(asha).andExpect(status().isConflict());
        assertThat(social.json(social.invites(bala))).isEmpty();
        social.revokeInvite(bala, firstId).andExpect(status().isNotFound());
        social.revokeInvite(asha, firstId).andExpect(status().isNoContent());
        social.createInvite(asha).andExpect(status().isCreated());
    }

    @Test
    void redeeming_whenAlreadyConnected_is409_andAPendingRequestIsCompleted() throws Exception {
        Person asha = social.person("Asha");
        Person bala = social.person("Bala");
        social.connect(asha, bala);
        social.redeem(bala, social.json(social.createInvite(asha)).get("code").asText())
                .andExpect(status().isConflict());

        Person chitra = social.person("Chitra");
        social.request(chitra, asha).andExpect(status().isAccepted());
        social.redeem(chitra, social.json(social.createInvite(asha)).get("code").asText())
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONNECTED"));
        assertThat(social.publicIds(social.requests(asha, "incoming"))).isEmpty();
    }

    @Test
    void invitesNeedAProfile() throws Exception {
        String token = social.accountWithoutProfile();
        Person nobody = new Person(token, -1, "x", "nobody");
        social.createInvite(nobody).andExpect(status().isConflict());
        social.redeem(nobody, InviteCodes.newCode()).andExpect(status().isConflict());
    }
}
