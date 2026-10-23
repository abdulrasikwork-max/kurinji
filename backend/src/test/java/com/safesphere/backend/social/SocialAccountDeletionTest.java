package com.safesphere.backend.social;

import static com.safesphere.backend.support.TestAccounts.PASSWORD;
import static com.safesphere.backend.support.TestAccounts.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.account.service.AccountDeletionJob;
import com.safesphere.backend.account.service.AccountEraser.Outcome;
import com.safesphere.backend.social.media.MediaStorage;
import com.safesphere.backend.support.TestImages;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 13A + 10A.3: a deleted account, and an anonymised one (a moderator the audit log names), leave no social row
 * behind (profile, connections either way, invites, blocks and mutes by or of the person). Other people's rows that do
 * not involve the person stay.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class SocialAccountDeletionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private AccountDeletionJob job;
    @Autowired private MediaStorage media;

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
    void aDeletedAccount_leavesNoSocialRows() throws Exception {
        Person leaving = social.person("Leaving");
        Person others = everything(leaving);
        requestDeletion(leaving);
        clock.advance(Duration.ofDays(8));
        // The job runs over the shared test database, so other test classes' due accounts may be counted too.
        assertThat(job.run().getOrDefault(Outcome.DELETED, 0)).isGreaterThanOrEqualTo(1);
        assertThat(users.findById(leaving.id())).isEmpty();
        assertNothingLeft(leaving.id());
        assertOthersKept(others);
    }

    @Test
    void anAnonymisedAccount_leavesNoSocialRowsEither() throws Exception {
        Person leaving = social.person("Moderator");
        Person others = everything(leaving);
        jdbc.update("UPDATE users SET role = 'MODERATOR' WHERE id = ?", leaving.id());
        jdbc.update("INSERT INTO moderation_actions (public_id, actor_id, action, reason, occurred_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), leaving.id(), "DISMISS", "NO_VIOLATION", LocalDateTime.now(ZoneOffset.UTC));
        requestDeletion(leaving);
        clock.advance(Duration.ofDays(8));
        assertThat(job.run().getOrDefault(Outcome.ANONYMISED, 0)).isGreaterThanOrEqualTo(1);
        assertThat(users.findById(leaving.id()).orElseThrow().isErased()).isTrue();
        assertNothingLeft(leaving.id());
        assertOthersKept(others);
    }

    /** Gives [p] every kind of social row, by and of them; returns a person whose own unrelated rows must survive. */
    private Person everything(Person p) throws Exception {
        Person friend = social.person("Friend");
        Person asker = social.person("Asker");
        Person blocker = social.person("Blocker");
        Person bystander = social.person("Bystander");
        social.connect(p, friend);
        social.request(asker, p).andExpect(status().isAccepted());
        social.createInvite(p).andExpect(status().isCreated());
        social.block(p, bystander).andExpect(status().isNoContent());
        social.block(blocker, p).andExpect(status().isNoContent());
        social.mute(p, asker).andExpect(status().isNoContent());
        social.mute(friend, p).andExpect(status().isNoContent());
        // Rows between other people only.
        social.connect(friend, asker);
        social.block(friend, bystander).andExpect(status().isNoContent());
        // Phase 13B: a post with a photo by p, a perspective of the friend on it, p's perspective and reply on the
        // friend's post, an upload never attached; the friend's own perspective on their own post must stay.
        String photo = social.uploadId(p, TestImages.jpeg(8, 8));
        social.uploadId(p, TestImages.jpeg(9, 9));
        Map<String, Object> body = SocialClient.experienceBody("EVERYONE", "NONE");
        body.put("mediaIds", java.util.List.of(photo));
        String ownPost = social.json(social.createPost(p, body).andExpect(status().isCreated())).get("publicId").asText();
        social.commentId(friend, ownPost, "BEEN_HERE", "Friend on p's post", null);
        String friendsPost = social.experience(friend, "EVERYONE");
        String friendsOwn = social.commentId(friend, friendsPost, "QUESTION", "Friend asks on own post", null);
        String byP = social.commentId(p, friendsPost, "BEEN_HERE", "p on the friend's post", null);
        social.commentId(friend, friendsPost, "REPLY", "Friend replies to p", byP);
        social.commentId(p, friendsPost, "REPLY", "p replies to the friend", friendsOwn);
        // Phase 13C: p's Cocoon holds the friend's post; the friend saved p's post; the asker's butterfly on the
        // friend's post must stay.
        social.butterfly(p, friendsPost, null).andExpect(status().isOk());
        social.butterfly(friend, ownPost, "BEEN").andExpect(status().isOk());
        social.butterfly(asker, friendsPost, null).andExpect(status().isOk());
        photoKeys = jdbc.queryForList("SELECT storage_key FROM post_media WHERE owner_id = ?", String.class, p.id());
        assertThat(photoKeys).hasSize(2).allMatch(media::exists);
        friendsPostId = friendsPost;
        return friend;
    }

    private java.util.List<String> photoKeys;
    private String friendsPostId;

    private void assertNothingLeft(long userId) {
        assertThat(count("SELECT COUNT(*) FROM posts WHERE author_id = ?", userId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM post_media WHERE owner_id = ?", userId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM comments WHERE author_id = ?", userId)).isZero();
        assertThat(photoKeys).noneMatch(media::exists);                       // the files went before the rows
        // Only the friend's own perspective is left on the friend's post (the friend's reply to p went with p's).
        assertThat(count("SELECT COUNT(*) FROM comments c JOIN posts p ON p.id = c.post_id WHERE p.public_id = ?",
                friendsPostId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM profiles WHERE user_id = ?", userId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM connections WHERE user_low = ? OR user_high = ?", userId, userId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM connection_invites WHERE owner_id = ?", userId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM blocks WHERE blocker_id = ? OR blocked_id = ?", userId, userId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM mutes WHERE muter_id = ? OR muted_id = ?", userId, userId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM butterflies WHERE user_id = ?", userId)).isZero();
        // Only the asker's butterfly is left on the friend's post; nothing points at p's deleted post.
        assertThat(count("SELECT COUNT(*) FROM butterflies b JOIN posts p ON p.id = b.post_id WHERE p.public_id = ?",
                friendsPostId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM butterflies WHERE post_id NOT IN (SELECT id FROM posts)")).isZero();
    }

    private void assertOthersKept(Person friend) throws Exception {
        assertThat(social.publicIds(social.connections(friend))).hasSize(1);
        assertThat(social.publicIds(social.blocks(friend))).hasSize(1);
        assertThat(social.publicIds(social.mutes(friend))).isEmpty(); // the only mute was of the deleted person
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private void requestDeletion(Person p) throws Exception {
        mockMvc.perform(post("/api/users/me/deletion").header(HttpHeaders.AUTHORIZATION, bearer(p.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", PASSWORD))))
                .andExpect(status().isAccepted());
    }
}
