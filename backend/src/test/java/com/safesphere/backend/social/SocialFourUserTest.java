package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Test
    void posts_photosAndPerspectives_fromEachPointOfView() throws Exception {
        String everyone = postWithPhoto("EVERYONE");
        String connections = postWithPhoto("CONNECTIONS");
        String onlyMe = postWithPhoto("ONLY_ME");
        social.commentId(connection, everyone, "BEEN_HERE", "Went there too.", null);
        social.commentId(connection, connections, "QUESTION", "Is it open late?", null);

        // [owner, connection, stranger, blocked] for each post.
        expectPost(everyone, 200, 200, 200, 404);
        expectPost(connections, 200, 200, 404, 404);
        expectPost(onlyMe, 200, 404, 404, 404);

        assertThat(social.postIds(social.minePosts(owner, null))).containsExactlyInAnyOrder(everyone, connections, onlyMe);
        assertThat(social.postIds(social.profilePosts(connection, owner, null))).containsExactlyInAnyOrder(everyone,
                connections);
        assertThat(social.postIds(social.profilePosts(stranger, owner, null))).containsExactly(everyone);
        social.profilePosts(blocked, owner, null).andExpect(status().isNotFound());
        assertThat(social.postIds(social.minePosts(stranger, null))).isEmpty();      // "mine" is only ever one's own

        // Perspectives: the list and writing follow the post.
        assertThat(social.json(social.comments(stranger, everyone))).hasSize(1);
        social.comments(stranger, connections).andExpect(status().isNotFound());
        social.comments(blocked, everyone).andExpect(status().isNotFound());
        social.comment(blocked, everyone, "QUESTION", "Hello?", null).andExpect(status().isNotFound());
        social.comment(stranger, connections, "QUESTION", "Hello?", null).andExpect(status().isNotFound());
        social.comment(stranger, everyone, "QUESTION", "Hello?", null).andExpect(status().isCreated());
    }

    @Test
    void aBlockedPair_neverSeeEachOthersPerspectives_onAThirdPersonsPost() throws Exception {
        String strangersPost = social.experience(stranger, "EVERYONE");
        String byOwner = social.commentId(owner, strangersPost, "BEEN_HERE", "Owner was here.", null);
        String byBlocked = social.commentId(blocked, strangersPost, "BEEN_HERE", "Blocked was here.", null);
        assertThat(social.ids(social.comments(owner, strangersPost))).contains(byOwner).doesNotContain(byBlocked);
        assertThat(social.ids(social.comments(blocked, strangersPost))).contains(byBlocked).doesNotContain(byOwner);
        assertThat(social.ids(social.comments(stranger, strangersPost))).contains(byOwner, byBlocked);
        social.comment(blocked, strangersPost, "REPLY", "Replying to the owner", byOwner).andExpect(status().isNotFound());
    }

    private String postWithPhoto(String audience) throws Exception {
        String mediaId = social.uploadId(owner, com.safesphere.backend.support.TestImages.jpeg(12, 12));
        java.util.Map<String, Object> body = SocialClient.experienceBody(audience, "NONE");
        body.put("mediaIds", java.util.List.of(mediaId));
        return social.json(social.createPost(owner, body).andExpect(status().isCreated())).get("publicId").asText();
    }

    private void expectPost(String postId, int... statuses) throws Exception {
        Person[] viewers = {owner, connection, stranger, blocked};
        String mediaId = social.json(social.getPost(owner, postId)).get("media").get(0).get("mediaId").asText();
        for (int i = 0; i < viewers.length; i++) {
            social.getPost(viewers[i], postId).andExpect(status().is(statuses[i]));
            social.media(viewers[i], mediaId).andExpect(status().is(statuses[i]));
        }
    }

    /**
     * Phase 13C: every new read path from each point of view, plus a fifth person who MUTED the owner: the Edition,
     * From your connections, category pages, the butterfly and the Cocoon. Lists leave out the blocked pair and the
     * muted author; a deliberate visit by the muter still works.
     */
    @Test
    void discover_butterflies_andCocoon_fromEachPointOfView() throws Exception {
        Person muter = social.person("Muter");
        social.connect(owner, muter);
        social.mute(muter, owner).andExpect(status().isNoContent());
        java.util.Map<String, Object> body = SocialClient.experienceBody("EVERYONE", "CITY");
        body.put("city", "COIMBATORE");
        body.put("category", "ACTIVITIES_SPORT");
        String everyone = social.json(social.createPost(owner, body).andExpect(status().isCreated()))
                .get("publicId").asText();
        String connectionsOnly = social.experience(owner, "CONNECTIONS");

        // Edition (Everyone posts of the city): connection and stranger yes; blocked and muter no; the owner's own no.
        assertThat(social.editionIds(social.edition(connection, "COIMBATORE"))).contains(everyone);
        assertThat(social.editionIds(social.edition(stranger, "COIMBATORE"))).contains(everyone);
        assertThat(social.editionIds(social.edition(blocked, "COIMBATORE"))).doesNotContain(everyone);
        assertThat(social.editionIds(social.edition(muter, "COIMBATORE"))).doesNotContain(everyone);
        assertThat(social.editionIds(social.edition(owner, "COIMBATORE"))).doesNotContain(everyone);

        // From your connections: only the connection (the muter is connected but muted; the blocked pair is not).
        assertThat(social.discoverIds(social.fromConnections(connection, null))).contains(everyone, connectionsOnly);
        assertThat(social.discoverIds(social.fromConnections(stranger, null))).isEmpty();
        assertThat(social.discoverIds(social.fromConnections(blocked, null))).doesNotContain(everyone, connectionsOnly);
        assertThat(social.discoverIds(social.fromConnections(muter, null))).doesNotContain(everyone, connectionsOnly);

        // Category pages.
        for (Person viewer : new Person[] {connection, stranger}) {
            assertThat(social.postIds(social.categoryPage(viewer, "ACTIVITIES_SPORT", "COIMBATORE", null)))
                    .contains(everyone);
        }
        for (Person viewer : new Person[] {blocked, muter}) {
            assertThat(social.postIds(social.categoryPage(viewer, "ACTIVITIES_SPORT", "COIMBATORE", null)))
                    .doesNotContain(everyone);
        }

        // Butterflies: anyone who can see it; never the blocked; the muter by a deliberate visit.
        social.butterfly(connection, connectionsOnly, null).andExpect(status().isOk());
        social.butterfly(stranger, connectionsOnly, null).andExpect(status().isNotFound());
        social.butterfly(stranger, everyone, null).andExpect(status().isOk());
        social.butterfly(blocked, everyone, null).andExpect(status().isNotFound());
        social.butterfly(muter, everyone, null).andExpect(status().isOk());
        social.butterfly(owner, everyone, null).andExpect(status().isBadRequest());
        // The muter's own Cocoon still shows what they saved (mute hides lists of others, not one's own choices).
        assertThat(social.cocoonIds(social.cocoon(muter, "WANT", null))).containsExactly(everyone);
        assertThat(social.cocoonIds(social.cocoon(stranger, "WANT", null))).containsExactly(everyone);
        // Each sees only their own state; only the owner gets the (thresholded) number.
        social.getPost(connection, everyone).andExpect(jsonPath("$.yourButterfly").isEmpty())
                .andExpect(jsonPath("$.butterflies").isEmpty());
        social.getPost(stranger, everyone).andExpect(jsonPath("$.yourButterfly").value("WANT"))
                .andExpect(jsonPath("$.butterflies").isEmpty());
        social.getPost(owner, everyone).andExpect(jsonPath("$.butterflies.fewerThanThree").value(true))
                .andExpect(jsonPath("$.butterflies.count").isEmpty());
    }

    private org.springframework.test.web.servlet.ResultActions byId(Person viewer, Person target) throws Exception {
        return mockMvc.perform(get("/api/profiles/{id}", target.publicId())
                .header(HttpHeaders.AUTHORIZATION, TestAccounts.bearer(viewer.token())));
    }
}
