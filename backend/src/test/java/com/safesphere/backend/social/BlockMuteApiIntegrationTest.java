package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.social.repository.ConnectionRepository;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.user.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 13A through the real API: a block removes the connection and every request between the two (whoever sent it),
 * hides them from each other in both directions, is silent and private; unblocking does not reconnect. A mute is
 * private and changes nothing for the muted person.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BlockMuteApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private ConnectionRepository connections;

    private SocialClient social;

    @BeforeEach
    void client() {
        social = new SocialClient(mockMvc, objectMapper, users);
    }

    @Test
    void block_removesTheConnection_andHidesBothFromEachOther() throws Exception {
        Person asha = social.person("Asha");
        Person bala = social.person("Bala");
        social.connect(asha, bala);

        social.block(asha, bala).andExpect(status().isNoContent());
        assertThat(social.publicIds(social.connections(asha))).isEmpty();
        assertThat(social.publicIds(social.connections(bala))).isEmpty();
        assertThat(connections.findByUserLowAndUserHigh(Math.min(asha.id(), bala.id()), Math.max(asha.id(), bala.id())))
                .isEmpty();
        social.profile(asha, bala).andExpect(status().isNotFound());
        social.profile(bala, asha).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No one with that handle"));
        social.request(bala, asha).andExpect(status().isNotFound());
        social.request(asha, bala).andExpect(status().isNotFound());
    }

    @Test
    void block_removesWaitingRequestsInBothDirections() throws Exception {
        Person asha = social.person("Asha");
        Person bala = social.person("Bala");
        Person chitra = social.person("Chitra");
        social.request(bala, asha).andExpect(status().isAccepted());
        social.request(asha, chitra).andExpect(status().isAccepted());

        social.block(asha, bala).andExpect(status().isNoContent());
        social.block(chitra, asha).andExpect(status().isNoContent());
        assertThat(social.publicIds(social.requests(asha, "incoming"))).isEmpty();
        assertThat(social.publicIds(social.requests(bala, "outgoing"))).isEmpty();
        assertThat(social.publicIds(social.requests(asha, "outgoing"))).isEmpty();
        assertThat(social.publicIds(social.requests(chitra, "incoming"))).isEmpty();
    }

    @Test
    void blockLists_arePrivate_idempotent_andUnblockingDoesNotReconnect() throws Exception {
        Person asha = social.person("Asha");
        Person bala = social.person("Bala");
        Person chitra = social.person("Chitra");
        social.connect(asha, bala);

        social.block(asha, bala).andExpect(status().isNoContent());
        social.block(asha, bala).andExpect(status().isNoContent());
        assertThat(social.publicIds(social.blocks(asha))).containsExactly(bala.publicId());
        assertThat(social.publicIds(social.blocks(bala))).isEmpty();   // the blocked person cannot find out
        assertThat(social.publicIds(social.blocks(chitra))).isEmpty();

        social.unblock(asha, bala).andExpect(status().isNoContent());
        social.unblock(asha, bala).andExpect(status().isNotFound());
        social.profile(bala, asha).andExpect(status().isOk()).andExpect(jsonPath("$.relationship").value("NONE"));
        assertThat(social.publicIds(social.connections(asha))).isEmpty();
    }

    @Test
    void theBlockedPerson_canStillBlockBack_andBothBlocksStandAlone() throws Exception {
        Person asha = social.person("Asha");
        Person bala = social.person("Bala");
        social.block(asha, bala).andExpect(status().isNoContent());
        social.block(bala, asha).andExpect(status().isNoContent());
        social.unblock(asha, bala).andExpect(status().isNoContent());
        social.profile(asha, bala).andExpect(status().isNotFound()); // Bala's block still hides them
    }

    @Test
    void selfAndUnknownPeople_areRefused() throws Exception {
        Person asha = social.person("Asha");
        social.block(asha, asha).andExpect(status().isBadRequest());
        social.mute(asha, asha).andExpect(status().isBadRequest());
        Person ghost = new Person(asha.token(), -1, UUID.randomUUID().toString(), "ghost");
        social.block(asha, ghost).andExpect(status().isNotFound());
        social.mute(asha, ghost).andExpect(status().isNotFound());
    }

    @Test
    void mute_isPrivate_andChangesNothingForTheMutedPerson() throws Exception {
        Person asha = social.person("Asha");
        Person bala = social.person("Bala");
        social.connect(asha, bala);

        social.mute(asha, bala).andExpect(status().isNoContent());
        social.mute(asha, bala).andExpect(status().isNoContent());
        assertThat(social.publicIds(social.mutes(asha))).containsExactly(bala.publicId());
        assertThat(social.publicIds(social.mutes(bala))).isEmpty();
        assertThat(social.publicIds(social.connections(bala))).containsExactly(asha.publicId());
        social.profile(bala, asha).andExpect(status().isOk()).andExpect(jsonPath("$.relationship").value("CONNECTED"));

        social.unmute(asha, bala).andExpect(status().isNoContent());
        assertThat(social.publicIds(social.mutes(asha))).isEmpty();
        social.unmute(asha, bala).andExpect(status().isNotFound());
    }
}
