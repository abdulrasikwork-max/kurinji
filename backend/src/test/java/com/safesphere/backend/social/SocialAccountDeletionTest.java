package com.safesphere.backend.social;

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
@Import({MutableClockConfig.class, com.safesphere.backend.support.CapturingOtpSenderConfig.class})
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
        // Moments, their photos and the cases about them: MomentPurgeAndDeletionTest and MomentModerationApiTest.
        // Phase 17E: p also has a profile photo.
        social.uploadProfilePhoto(p, com.safesphere.backend.support.TestImages.jpeg(400, 300)).andExpect(status().isOk());
        profilePhotoKey = jdbc.queryForObject("SELECT photo_key FROM profiles WHERE user_id = ?", String.class, p.id());
        assertThat(media.exists(profilePhotoKey)).isTrue();
        return friend;
    }

    private String profilePhotoKey;

    private void assertNothingLeft(long userId) {
        assertThat(count("SELECT COUNT(*) FROM moments WHERE author_id = ?", userId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM profiles WHERE user_id = ?", userId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM connections WHERE user_low = ? OR user_high = ?", userId, userId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM connection_invites WHERE owner_id = ?", userId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM blocks WHERE blocker_id = ? OR blocked_id = ?", userId, userId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM mutes WHERE muter_id = ? OR muted_id = ?", userId, userId)).isZero();
        assertThat(media.exists(profilePhotoKey)).isFalse();
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
                        .content(objectMapper.writeValueAsString(
                                com.safesphere.backend.support.TestAccounts.confirmation(mockMvc, objectMapper, p.token()))))
                .andExpect(status().isAccepted());
    }
}
