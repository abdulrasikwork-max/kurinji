package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.social.entity.Connection;
import com.safesphere.backend.social.entity.ConnectionStatus;
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
 * Phase 13A through the real API: requests, accept, silent decline, cancel, remove, crossing requests, self, the
 * waiting-request limit, a profile being required, and lists that only their owner can read.
 */
@SpringBootTest(properties = {"app.social.max-pending-outgoing=3", "app.social.requests-per-day=1000"})
@AutoConfigureMockMvc
class ConnectionApiIntegrationTest {

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
    void request_thenAccept_connectsBoth_andBothSeeEachOther() throws Exception {
        Person asha = social.person("Asha");
        Person bala = social.person("Bala");

        social.request(asha, bala).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.person.publicId").value(bala.publicId()));
        social.profile(asha, bala).andExpect(jsonPath("$.relationship").value("REQUESTED"));
        social.profile(bala, asha).andExpect(jsonPath("$.relationship").value("REQUEST_RECEIVED"));
        assertThat(social.publicIds(social.requests(asha, "outgoing"))).containsExactly(bala.publicId());

        String requestId = social.incomingFrom(bala, asha);
        assertThat(requestId).isNotNull();
        social.accept(bala, requestId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONNECTED"))
                .andExpect(jsonPath("$.person.publicId").value(asha.publicId()));

        assertThat(social.publicIds(social.connections(asha))).containsExactly(bala.publicId());
        assertThat(social.publicIds(social.connections(bala))).containsExactly(asha.publicId());
        social.profile(asha, bala).andExpect(jsonPath("$.relationship").value("CONNECTED"));
        assertThat(social.publicIds(social.requests(bala, "incoming"))).isEmpty();
        assertThat(social.publicIds(social.requests(asha, "outgoing"))).isEmpty();

        social.request(asha, bala).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You are already connected"));
    }

    @Test
    void decline_isSilent_theSenderStillSeesRequested_andCanCancel() throws Exception {
        Person asha = social.person("Asha");
        Person bala = social.person("Bala");
        social.request(asha, bala).andExpect(status().isAccepted());
        String requestId = social.incomingFrom(bala, asha);

        social.decline(bala, requestId).andExpect(status().isNoContent());
        assertThat(social.publicIds(social.requests(bala, "incoming"))).isEmpty();
        social.profile(bala, asha).andExpect(jsonPath("$.relationship").value("NONE"));
        // The sender cannot tell: same answers as before the decline, and asking again changes nothing.
        social.profile(asha, bala).andExpect(jsonPath("$.relationship").value("REQUESTED"));
        assertThat(social.publicIds(social.requests(asha, "outgoing"))).containsExactly(bala.publicId());
        social.request(asha, bala).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("REQUESTED"));
        assertThat(social.publicIds(social.requests(bala, "incoming"))).isEmpty();

        // A declined request cannot be accepted later by its old id...
        social.accept(bala, requestId).andExpect(status().isNotFound());
        // ...but if Bala changes her mind and asks Asha, they are connected.
        social.request(bala, asha).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("CONNECTED"));
        assertThat(social.publicIds(social.connections(asha))).containsExactly(bala.publicId());
    }

    @Test
    void cancel_removesTheRequestForBoth_andOnlyTheSenderCanCancel() throws Exception {
        Person asha = social.person("Asha");
        Person bala = social.person("Bala");
        social.request(asha, bala).andExpect(status().isAccepted());
        String requestId = social.incomingFrom(bala, asha);

        social.cancel(bala, requestId).andExpect(status().isNotFound());
        social.cancel(asha, requestId).andExpect(status().isNoContent());
        assertThat(social.publicIds(social.requests(bala, "incoming"))).isEmpty();
        social.profile(asha, bala).andExpect(jsonPath("$.relationship").value("NONE"));
        social.cancel(asha, requestId).andExpect(status().isNotFound());
    }

    @Test
    void crossingRequests_becomeOneConnection() throws Exception {
        Person asha = social.person("Asha");
        Person bala = social.person("Bala");
        social.request(asha, bala).andExpect(jsonPath("$.status").value("REQUESTED"));
        social.request(bala, asha).andExpect(jsonPath("$.status").value("CONNECTED"));
        Connection row = connections.findByUserLowAndUserHigh(Math.min(asha.id(), bala.id()),
                Math.max(asha.id(), bala.id())).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ConnectionStatus.ACCEPTED);
        assertThat(row.getRequesterId()).isEqualTo(asha.id());
    }

    @Test
    void remove_isSilent_andEndsTheConnectionForBoth() throws Exception {
        Person asha = social.person("Asha");
        Person bala = social.person("Bala");
        social.connect(asha, bala);
        social.removeConnection(bala, asha).andExpect(status().isNoContent());
        assertThat(social.publicIds(social.connections(asha))).isEmpty();
        assertThat(social.publicIds(social.connections(bala))).isEmpty();
        social.removeConnection(bala, asha).andExpect(status().isNotFound());
        // They can connect again later.
        social.connect(asha, bala);
    }

    @Test
    void yourself_unknownPeople_andMalformedIds_areRefused() throws Exception {
        Person asha = social.person("Asha");
        social.request(asha, asha).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You cannot connect with yourself"));
        Person ghost = new Person(asha.token(), -1, UUID.randomUUID().toString(), "ghost");
        social.request(asha, ghost).andExpect(status().isNotFound());
        social.accept(asha, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        social.requests(asha, "sideways").andExpect(status().isBadRequest());
    }

    @Test
    void sendingRequests_needsAProfile() throws Exception {
        Person bala = social.person("Bala");
        String noProfile = social.accountWithoutProfile();
        Person nobody = new Person(noProfile, -1, UUID.randomUUID().toString(), "nobody");
        social.request(nobody, bala).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Make your profile first"));
    }

    @Test
    void waitingRequests_areLimited() throws Exception {
        Person asha = social.person("Asha");
        for (int i = 0; i < 3; i++) social.request(asha, social.person("P" + i)).andExpect(status().isAccepted());
        social.request(asha, social.person("Extra")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You have many requests waiting. Cancel some first."));
    }

    @Test
    void someoneElse_cannotAnswerOrSeeARequestBetweenTwoOthers() throws Exception {
        Person asha = social.person("Asha");
        Person bala = social.person("Bala");
        Person chitra = social.person("Chitra");
        social.request(asha, bala).andExpect(status().isAccepted());
        String requestId = social.incomingFrom(bala, asha);

        social.accept(chitra, requestId).andExpect(status().isNotFound());
        social.decline(chitra, requestId).andExpect(status().isNotFound());
        social.cancel(chitra, requestId).andExpect(status().isNotFound());
        social.accept(asha, requestId).andExpect(status().isNotFound()); // the sender cannot accept their own request
        assertThat(social.publicIds(social.requests(chitra, "incoming"))).isEmpty();
        assertThat(social.publicIds(social.connections(chitra))).isEmpty();

        social.connect(asha, chitra);
        // Chitra's own list shows only her connection, never Asha's other people.
        assertThat(social.publicIds(social.connections(chitra))).containsExactly(asha.publicId());
    }

    @Test
    void answers_carryNoCounts() throws Exception {
        Person asha = social.person("Asha");
        Person bala = social.person("Bala");
        JsonNode result = social.json(social.request(asha, bala).andExpect(status().isAccepted()));
        assertThat(result.fieldNames()).toIterable().containsExactlyInAnyOrder("status", "person");
        assertThat(result.get("person").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("publicId", "handle", "displayName");
    }
}
