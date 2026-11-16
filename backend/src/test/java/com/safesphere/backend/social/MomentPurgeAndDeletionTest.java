package com.safesphere.backend.social;

import static com.safesphere.backend.support.TestAccounts.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.account.service.AccountDeletionJob;
import com.safesphere.backend.account.service.AccountEraser.Outcome;
import com.safesphere.backend.social.entity.DailyQuestion;
import com.safesphere.backend.social.entity.Moment;
import com.safesphere.backend.social.entity.MomentEnums.Audience;
import com.safesphere.backend.social.entity.MomentEnums.Kind;
import com.safesphere.backend.social.entity.MomentMedia;
import com.safesphere.backend.social.entity.PostEnums.ModerationReason;
import com.safesphere.backend.social.entity.PostEnums.ModerationState;
import com.safesphere.backend.social.media.MediaStorage;
import com.safesphere.backend.social.policy.MomentTimes;
import com.safesphere.backend.social.repository.DailyQuestionRepository;
import com.safesphere.backend.social.repository.MomentMediaRepository;
import com.safesphere.backend.social.repository.MomentRepository;
import com.safesphere.backend.social.service.SocialMediaCleanupJob;
import com.safesphere.backend.support.CapturingOtpSenderConfig;
import com.safesphere.backend.support.MomentClient;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.support.TestAccounts;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
 * Phase N4-B1: what happens to a moment's data. N-47: purged 7 days after its 24 hours ended, unless the author keeps
 * it, an open case needs it or a moderator removed it; a moment waiting for a human is never purged (N-57). Account
 * deletion removes every moment, photo file and keep of the person (PHASE_N4 §3).
 *
 * Photo rows and files are made directly here (uploading arrives in N4-B2); the rules about them are B1's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({MutableClockConfig.class, CapturingOtpSenderConfig.class})
class MomentPurgeAndDeletionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private SocialMediaCleanupJob cleanup;
    @Autowired private AccountDeletionJob deletion;
    @Autowired private MediaStorage storage;
    @Autowired private MomentRepository momentRows;
    @Autowired private MomentMediaRepository mediaRows;
    @Autowired private DailyQuestionRepository questions;

    private SocialClient social;
    private MomentClient moments;

    @BeforeEach
    void setUp() {
        clock.reset();
        social = new SocialClient(mockMvc, objectMapper, users);
        moments = new MomentClient(mockMvc, objectMapper);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** A photo file and its row for [momentId] (null = uploaded, never attached); returns the file's key. */
    private String photo(long ownerId, Long momentId) {
        String key = MediaStorage.newKey();
        storage.write(key, new byte[] {1, 2, 3});
        MomentMedia row = new MomentMedia(ownerId, key, 8, 8, 3, "a".repeat(64), now());
        if (momentId != null) row.attach(momentId);
        mediaRows.saveAndFlush(row);
        return key;
    }

    private long rowId(String publicId) {
        return momentRows.findByPublicId(publicId).orElseThrow().getId();
    }

    private boolean exists(String publicId) {
        return momentRows.findByPublicId(publicId).isPresent();
    }

    @Test
    void purge_sevenDaysAfterTheEnd_unlessKept_inReview_removed_orNeverVisible() throws Exception {
        Person asha = social.person("Asha");
        long miet = jdbc.queryForObject("SELECT id FROM communities WHERE code = 'MIET'", Long.class);

        String plain = moments.say(asha, "COMMUNITY", "Gone in a week");
        String plainPhoto = photo(asha.id(), rowId(plain));
        String kept = moments.say(asha, "COMMUNITY", "Kept");
        moments.keep(asha, kept).andExpect(status().isOk());
        String keptPhoto = photo(asha.id(), rowId(kept));
        String inReview = moments.say(asha, "COMMUNITY", "A case is open");
        Moment reviewed = momentRows.findByPublicId(inReview).orElseThrow();
        reviewed.setInReview(true);
        momentRows.saveAndFlush(reviewed);
        String removed = moments.say(asha, "COMMUNITY", "Removed by a moderator");
        Moment gone = momentRows.findByPublicId(removed).orElseThrow();
        gone.moderate(ModerationState.REMOVED, ModerationReason.HARASSMENT, UUID.randomUUID().toString());
        momentRows.saveAndFlush(gone);
        // Never visible: waiting for a human, with its photo (the new-member photo hold of B2).
        Moment waiting = momentRows.saveAndFlush(new Moment(asha.id(), miet, Kind.SAY, Audience.COMMUNITY, null, now()));
        String waitingPhoto = photo(asha.id(), waiting.getId());
        // A photo that was uploaded and never shared.
        String strayPhoto = photo(asha.id(), null);

        // Nothing is old yet.
        cleanup.run();
        assertThat(List.of(plain, kept, inReview, removed)).allMatch(this::exists);
        assertThat(List.of(plainPhoto, keptPhoto, waitingPhoto, strayPhoto)).allMatch(storage::exists);

        // One minute before the 7 days after the end are over: the moment has ended, its data is still there. The
        // stray upload is long gone (24 h); a photo with a row is never taken for a file without one.
        clock.advance(Duration.ofHours(24).plusDays(7).minusMinutes(1));
        cleanup.run();
        assertThat(exists(plain)).isTrue();
        assertThat(storage.exists(plainPhoto)).isTrue();
        assertThat(storage.exists(strayPhoto)).isFalse();
        assertThat(mediaRows.findByStorageKeyIn(List.of(strayPhoto))).isEmpty();
        assertThat(storage.exists(waitingPhoto)).isTrue();

        // Past it: the row, its photo row and the file go. Everything with a reason to stay, stays.
        clock.advance(Duration.ofMinutes(2));
        assertThat(cleanup.run().moments()).isGreaterThanOrEqualTo(1);
        assertThat(exists(plain)).isFalse();
        assertThat(mediaRows.findByStorageKeyIn(List.of(plainPhoto))).isEmpty();
        assertThat(storage.exists(plainPhoto)).isFalse();
        assertThat(List.of(kept, inReview, removed)).allMatch(this::exists);
        assertThat(storage.exists(keptPhoto)).isTrue();
        assertThat(momentRows.findById(waiting.getId())).isPresent();           // N-57: never deleted automatically
        assertThat(storage.exists(waitingPhoto)).isTrue();

        // A year later, still: kept, needed by a case, removed (its retention is an N8 decision), waiting.
        clock.advance(Duration.ofDays(365));
        cleanup.run();
        assertThat(List.of(kept, inReview, removed)).allMatch(this::exists);
        assertThat(momentRows.findById(waiting.getId())).isPresent();
        assertThat(storage.exists(waitingPhoto)).isTrue();

        // Un-kept, and the case closed: both go at the next run.
        moments.unkeep(asha, kept).andExpect(status().isNoContent());
        reviewed = momentRows.findByPublicId(inReview).orElseThrow();
        reviewed.setInReview(false);
        momentRows.saveAndFlush(reviewed);
        cleanup.run();
        assertThat(exists(kept)).isFalse();
        assertThat(storage.exists(keptPhoto)).isFalse();
        assertThat(exists(inReview)).isFalse();
        assertThat(exists(removed)).isTrue();
    }

    @Test
    void accountDeletion_removesEveryMoment_photoFileAndKeep_andNothingOfAnyoneElse() throws Exception {
        Person leaving = social.person("Leaving");
        Person friend = social.person("Friend");
        long miet = jdbc.queryForObject("SELECT id FROM communities WHERE code = 'MIET'", Long.class);

        String live = moments.say(leaving, "COMMUNITY", "Live");
        String kept = moments.say(leaving, "MY_PEOPLE", "Kept");
        moments.keep(leaving, kept).andExpect(status().isOk());
        Moment waiting = momentRows.saveAndFlush(new Moment(leaving.id(), miet, Kind.SAY, Audience.COMMUNITY, null,
                now()));
        List<String> photos = List.of(photo(leaving.id(), rowId(live)), photo(leaving.id(), waiting.getId()),
                photo(leaving.id(), null));
        // As an admin, the person wrote a daily question: the question stays, without its writer.
        DailyQuestion question = questions.saveAndFlush(new DailyQuestion(miet,
                MomentTimes.indiaDay(now()).plusDays(30), "Written by someone who left?", leaving.id(), now()));

        String friendsMoment = moments.say(friend, "COMMUNITY", "Staying");
        String friendsPhoto = photo(friend.id(), rowId(friendsMoment));

        mockMvc.perform(post("/api/users/me/deletion").header(HttpHeaders.AUTHORIZATION, bearer(leaving.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                TestAccounts.confirmation(mockMvc, objectMapper, leaving.token()))))
                .andExpect(status().isAccepted());
        clock.advance(Duration.ofDays(8));
        assertThat(deletion.run().getOrDefault(Outcome.DELETED, 0)).isGreaterThanOrEqualTo(1);

        assertThat(users.findById(leaving.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM moments WHERE author_id = ?", Long.class, leaving.id()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM moment_media WHERE owner_id = ?", Long.class,
                leaving.id())).isZero();
        assertThat(photos).noneMatch(storage::exists);                          // the files went before the rows
        assertThat(questions.findById(question.getId()).orElseThrow().getCreatedBy()).isNull();

        assertThat(exists(friendsMoment)).isTrue();
        assertThat(storage.exists(friendsPhoto)).isTrue();
        questions.deleteById(question.getId());
    }
}
