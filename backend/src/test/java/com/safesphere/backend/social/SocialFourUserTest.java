package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.support.MomentClient;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.support.TestAccounts;
import com.safesphere.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 13A (design §11.2): the four people every social endpoint is tested with. OWNER; CONNECTION (connected to the
 * owner); STRANGER (no relationship); BLOCKED (the owner blocked them). Each 13A read and write is checked from each
 * point of view, so a path that forgets the one visibility rule fails here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SocialFourUserTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private SocialClient social;
    private Person owner;
    private Person connection;
    private Person stranger;
    private Person blocked;

    @BeforeEach
    void fourPeople() throws Exception {
        social = new SocialClient(mockMvc, objectMapper, users);
        owner = social.person("Owner");
        connection = social.person("Friend");
        stranger = social.person("Stranger");
        blocked = social.person("Blocked");
        social.connect(owner, connection);
        social.connect(owner, blocked);
        social.connect(blocked, connection);
        social.block(owner, blocked).andExpect(status().isNoContent());
    }

    @Test
    void profile_byHandle_andById() throws Exception {
        social.profile(owner, owner).andExpect(status().isOk()).andExpect(jsonPath("$.relationship").value("SELF"));
        social.profile(connection, owner).andExpect(status().isOk()).andExpect(jsonPath("$.relationship").value("CONNECTED"));
        social.profile(stranger, owner).andExpect(status().isOk()).andExpect(jsonPath("$.relationship").value("NONE"));
        social.profile(blocked, owner).andExpect(status().isNotFound());
        for (Person viewer : new Person[] {owner, connection, stranger}) byId(viewer, owner).andExpect(status().isOk());
        byId(blocked, owner).andExpect(status().isNotFound());
    }

    /**
     * Reports and moderation states from each point of view (13D; moments since N4). Only what one can see can be
     * reported; a collapsed moment stays visible to those who could see it; the blocked person learns nothing.
     */
    @Test
    void reports_andModerationStates_fromEachPointOfView() throws Exception {
        MomentClient moments = new MomentClient(mockMvc, objectMapper);
        String forPeople = moments.say(owner, "MY_PEOPLE", "Only for my people");
        social.report(connection, "MOMENT", forPeople, "SPAM", null).andExpect(status().isAccepted());
        social.report(stranger, "MOMENT", forPeople, "SPAM", null).andExpect(status().isNotFound());
        social.report(blocked, "MOMENT", forPeople, "SPAM", null).andExpect(status().isNotFound());
        social.report(blocked, "PERSON", owner.publicId(), "SPAM", null).andExpect(status().isNotFound());
        social.report(owner, "PERSON", blocked.publicId(), "SPAM", null).andExpect(status().isNotFound());
        moments.get_(owner, forPeople).andExpect(jsonPath("$.notice").isEmpty()); // one report changes nothing

        // Reports of established members count at once (a new member's only after their first week).
        java.time.LocalDateTime longAgo = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(30);
        Person third = social.person("Third");
        for (Person r : new Person[] {connection, stranger, third}) {
            jdbc.update("UPDATE memberships SET verified_at = ?, updated_at = ? WHERE user_id = ?", longAgo, longAgo,
                    r.id());
        }
        String everyone = moments.say(owner, "COMMUNITY", "For all of MIET");
        for (Person r : new Person[] {connection, stranger, third}) {
            social.report(r, "MOMENT", everyone, "HARASSMENT", null).andExpect(status().isAccepted());
        }
        moments.get_(owner, everyone).andExpect(jsonPath("$.notice.state").value("COLLAPSED"))
                .andExpect(jsonPath("$.notice.by").isEmpty());
        moments.get_(connection, everyone).andExpect(jsonPath("$.collapsed").value(true));
        moments.get_(stranger, everyone).andExpect(jsonPath("$.collapsed").value(true));
        moments.get_(blocked, everyone).andExpect(status().isNotFound());
    }

    @Test
    void connectionRequests_toTheOwner() throws Exception {
        social.request(connection, owner).andExpect(status().isConflict());     // already connected
        social.request(stranger, owner).andExpect(status().isAccepted());
        social.request(blocked, owner).andExpect(status().isNotFound());       // looks like no such person
        assertThat(social.publicIds(social.requests(owner, "incoming"))).containsExactly(stranger.publicId());
    }

    @Test
    void connectionLists_showOnlyOwnRelationships_andNeverTheBlockedPair() throws Exception {
        assertThat(social.publicIds(social.connections(owner))).containsExactly(connection.publicId());
        assertThat(social.publicIds(social.connections(connection)))
                .containsExactlyInAnyOrder(owner.publicId(), blocked.publicId()); // the block is between owner and blocked only
        assertThat(social.publicIds(social.connections(stranger))).isEmpty();
        assertThat(social.publicIds(social.connections(blocked))).containsExactly(connection.publicId());
    }

    @Test
    void inviteCodes_ofTheOwner() throws Exception {
        String code = social.json(social.createInvite(owner)).get("code").asText();
        social.redeem(blocked, code).andExpect(status().isNotFound());
        social.redeem(connection, code).andExpect(status().isConflict());
        social.redeem(stranger, code).andExpect(status().isOk());
        assertThat(social.json(social.invites(stranger))).isEmpty();          // nobody sees another's invites
    }

    @Test
    void privateLists_blocksMutesAndInvites_areOnlyTheOwners() throws Exception {
        social.mute(owner, stranger).andExpect(status().isNoContent());
        social.createInvite(owner).andExpect(status().isCreated());
        for (Person other : new Person[] {connection, stranger, blocked}) {
            assertThat(social.publicIds(social.blocks(other))).isEmpty();
            assertThat(social.publicIds(social.mutes(other))).isEmpty();
            assertThat(social.json(social.invites(other))).isEmpty();
        }
        assertThat(social.publicIds(social.blocks(owner))).containsExactly(blocked.publicId());
        assertThat(social.publicIds(social.mutes(owner))).containsExactly(stranger.publicId());
    }

    @Test
    void removingAndAnsweringSomeoneElsesConnection_isNotPossible() throws Exception {
        social.removeConnection(stranger, owner).andExpect(status().isNotFound());
        social.removeConnection(blocked, owner).andExpect(status().isNotFound());
        assertThat(social.publicIds(social.connections(owner))).containsExactly(connection.publicId());
    }

    // ---- Phase 13B: posts, their lists, photos and perspectives, for each audience ----

    private org.springframework.test.web.servlet.ResultActions byId(Person viewer, Person target) throws Exception {
        return mockMvc.perform(get("/api/profiles/{id}", target.publicId())
                .header(HttpHeaders.AUTHORIZATION, TestAccounts.bearer(viewer.token())));
    }
}
