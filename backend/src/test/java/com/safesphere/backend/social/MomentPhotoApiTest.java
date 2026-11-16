package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.social.entity.MomentEnums.Audience;
import com.safesphere.backend.social.entity.MomentMedia;
import com.safesphere.backend.social.media.MediaStorage;
import com.safesphere.backend.social.policy.MomentPolicy;
import com.safesphere.backend.social.repository.MomentMediaRepository;
import com.safesphere.backend.social.repository.MomentRepository;
import com.safesphere.backend.social.service.SocialMediaCleanupJob;
import com.safesphere.backend.support.MomentClient;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.support.TestImages;
import com.safesphere.backend.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
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
 * Phase N4-B2: the photo of a moment through the real API: upload (re-encoded, no metadata), attach (one photo, the
 * uploader's own, once), who may fetch it (exactly who may see the moment), removal, and the new-member rule (N-56,
 * N-57): a new member's photo for all of MIET waits for a person and nothing releases or deletes it automatically.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class MomentPhotoApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MomentRepository momentRows;
    @Autowired private MomentMediaRepository mediaRows;
    @Autowired private MediaStorage storage;
    @Autowired private SocialMediaCleanupJob cleanup;

    private SocialClient social;
    private MomentClient moments;
    private Person asha;      // established
    private Person bala;      // connected to Asha
    private Person chitra;    // a member, not connected

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        social = new SocialClient(mockMvc, objectMapper, users);
        moments = new MomentClient(mockMvc, objectMapper);
        asha = social.person("Asha");
        bala = social.person("Bala");
        chitra = social.person("Chitra");
        social.connect(asha, bala);
        establish(asha);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    /** As if the membership had been approved 8 days ago: past the 7-day new-member period. */
    private void establish(Person who) {
        LocalDateTime then = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(8)
                .truncatedTo(ChronoUnit.MICROS);
        jdbc.update("UPDATE memberships SET verified_at = ?, updated_at = ? WHERE user_id = ?", then, then, who.id());
    }

    private MomentMedia rowOf(String momentId) {
        long id = momentRows.findByPublicId(momentId).orElseThrow().getId();
        return mediaRows.findByMomentIdIn(List.of(id)).get(0);
    }

    @Test
    void anEstablishedMembersPhoto_isReEncoded_live_andServedOnlyThroughTheMoment() throws Exception {
        JsonNode uploaded = moments.json(moments.upload(asha, TestImages.jpegWithExifGps(320, 240), "image/jpeg")
                .andExpect(status().isCreated()));
        assertThat(uploaded.get("width").asInt()).isEqualTo(320);
        assertThat(uploaded.get("height").asInt()).isEqualTo(240);
        String mediaId = uploaded.get("mediaId").asText();

        JsonNode own = moments.json(moments.create(asha, MomentClient.withPhoto("SAY", "COMMUNITY", "Canteen today",
                mediaId)).andExpect(status().isCreated()));
        assertThat(own.get("status").asText()).isEqualTo("LIVE");
        assertThat(own.get("photo").get("width").asInt()).isEqualTo(320);
        assertThat(own.get("photo").get("height").asInt()).isEqualTo(240);
        assertThat(own.get("photo").size()).isEqualTo(2); // no id, no address, no key
        String id = own.get("id").asText();

        for (Person viewer : List.of(asha, bala, chitra)) {
            byte[] served = moments.photo(viewer, id).andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "image/jpeg"))
                    .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(TestImages.containsExifSegment(served)).isFalse();
            assertThat(new String(served, StandardCharsets.ISO_8859_1)).doesNotContain("SECRET-HOME");
        }
        assertThat(moments.todayIds(chitra, null)).contains(id);
        assertThat(moments.json(moments.get_(chitra, id)).get("photo").get("width").asInt()).isEqualTo(320);
        // The stored file is the re-encoded one, and a photo has no address of its own.
        assertThat(TestImages.containsExifSegment(storage.read(rowOf(id).getStorageKey()))).isFalse();
        moments.photo(asha, mediaId).andExpect(status().isNotFound());
    }

    @Test
    void aPhotoAlone_isAMoment_andAMomentWithoutEither_isNot() throws Exception {
        String mediaId = moments.uploadPhoto(asha, TestImages.jpeg(64, 64));
        JsonNode own = moments.json(moments.create(asha, MomentClient.withPhoto("SAY", "MY_PEOPLE", null, mediaId))
                .andExpect(status().isCreated()));
        assertThat(own.get("text").isNull()).isTrue();
        assertThat(own.get("photo").isNull()).isFalse();
        moments.create(asha, MomentClient.body("SAY", "MY_PEOPLE", "  ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Write something or add a photo"));
        // A moment without a photo answers "not found" for its photo.
        String plain = moments.say(asha, "COMMUNITY", "Words only");
        moments.photo(asha, plain).andExpect(status().isNotFound());
    }

    @Test
    void aPhoto_belongsToItsUploader_andToOneMomentOnly() throws Exception {
        String mediaId = moments.uploadPhoto(asha, TestImages.jpeg(64, 64));
        // Somebody else's upload, an unknown id: the same refusal.
        moments.create(bala, MomentClient.withPhoto("SAY", "MY_PEOPLE", "Mine now", mediaId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("That photo is no longer available. Please take it again."));
        moments.create(asha, MomentClient.withPhoto("SAY", "MY_PEOPLE", "x", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest());
        moments.deleteUpload(bala, mediaId).andExpect(status().isNotFound());

        moments.create(asha, MomentClient.withPhoto("SAY", "MY_PEOPLE", "First", mediaId))
                .andExpect(status().isCreated());
        moments.create(asha, MomentClient.withPhoto("SAY", "MY_PEOPLE", "Second", mediaId))
                .andExpect(status().isBadRequest());
        // Attached: it can no longer be removed as an upload (the moment is deleted instead).
        moments.deleteUpload(asha, mediaId).andExpect(status().isNotFound());
    }

    @Test
    void anUploadCanBeRemoved_andAMomentTakesItsPhotoFileWithIt() throws Exception {
        String spare = moments.uploadPhoto(asha, TestImages.jpeg(64, 64));
        String spareKey = jdbc.queryForObject("SELECT storage_key FROM moment_media WHERE public_id = ?", String.class,
                spare);
        assertThat(storage.exists(spareKey)).isTrue();
        moments.deleteUpload(asha, spare).andExpect(status().isNoContent());
        assertThat(storage.exists(spareKey)).isFalse();
        moments.deleteUpload(asha, spare).andExpect(status().isNotFound());

        String mediaId = moments.uploadPhoto(asha, TestImages.jpeg(64, 64));
        String id = moments.json(moments.create(asha, MomentClient.withPhoto("SAY", "COMMUNITY", "Soon gone", mediaId))
                .andExpect(status().isCreated())).get("id").asText();
        String key = rowOf(id).getStorageKey();
        moments.delete_(asha, id).andExpect(status().isNoContent());
        assertThat(storage.exists(key)).isFalse();
        moments.photo(asha, id).andExpect(status().isNotFound());
    }

    @Test
    void aPhotoFollowsItsMomentsAudience_andBlocks() throws Exception {
        String mediaId = moments.uploadPhoto(asha, TestImages.jpeg(64, 64));
        String id = moments.json(moments.create(asha, MomentClient.withPhoto("SAY", "MY_PEOPLE", "For my people",
                mediaId)).andExpect(status().isCreated())).get("id").asText();
        moments.photo(bala, id).andExpect(status().isOk());
        moments.photo(chitra, id).andExpect(status().isNotFound());

        // Someone whose membership ended: no photo, no upload.
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("UPDATE memberships SET ended_at = ?, end_reason = 'REMOVED' WHERE user_id = ?", now, bala.id());
        moments.photo(bala, id).andExpect(status().isForbidden());
        moments.upload(bala, TestImages.jpeg(32, 32), "image/jpeg").andExpect(status().isForbidden());
    }

    @Test
    void hostileOrOversizedUploads_areRefused_andNothingIsStored() throws Exception {
        long before = mediaRows.count();
        moments.upload(asha, "not an image at all".getBytes(StandardCharsets.UTF_8), "image/jpeg")
                .andExpect(status().isBadRequest());
        moments.upload(asha, TestImages.pngClaiming(60000, 60000), "image/png").andExpect(status().isBadRequest());
        moments.upload(asha, new byte[5 * 1024 * 1024 + 1], "image/jpeg").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A photo can be at most 5 MB"));
        // N-44: no video and no voice in N4.
        moments.upload(asha, TestImages.jpeg(16, 16), "video/mp4").andExpect(status().isUnsupportedMediaType());
        moments.upload(asha, TestImages.jpeg(16, 16), "audio/mp4").andExpect(status().isUnsupportedMediaType());
        assertThat(mediaRows.count()).isEqualTo(before);
        // A PNG is accepted and becomes a JPEG.
        moments.upload(asha, TestImages.png(40, 30), "image/png").andExpect(status().isCreated());
    }

    // ---- The new-member rule (N-56, N-57) ----

    @Test
    void aNewMembersPhotoForAllOfMiet_waitsForAPerson_andNothingReleasesOrDeletesIt() throws Exception {
        Person fresh = social.person("Fresh");   // verified a moment ago
        social.connect(fresh, bala);
        String mediaId = moments.uploadPhoto(fresh, TestImages.jpeg(64, 64));
        JsonNode own = moments.json(moments.create(fresh, MomentClient.withPhoto("SAY", "COMMUNITY", "My first photo",
                mediaId)).andExpect(status().isCreated()));
        String id = own.get("id").asText();
        assertThat(own.get("status").asText()).isEqualTo("WAITING");
        assertThat(own.get("visibleAt").isNull()).isTrue();
        assertThat(own.get("expiresAt").isNull()).isTrue();
        assertThat(own.get("when").isNull()).isTrue();

        // The author sees it and its photo; nobody else does, not even one of their people.
        moments.get_(fresh, id).andExpect(status().isOk());
        moments.photo(fresh, id).andExpect(status().isOk());
        for (Person other : List.of(bala, chitra, asha)) {
            moments.get_(other, id).andExpect(status().isNotFound());
            moments.photo(other, id).andExpect(status().isNotFound());
            assertThat(moments.wholeToday(other, null)).doesNotContain(id);
        }
        // It cannot be kept while it waits.
        moments.keep(fresh, id).andExpect(status().isConflict());

        // N-57: weeks pass, the author becomes established, the clean-up runs: still waiting, still there.
        String key = rowOf(id).getStorageKey();
        clock.advance(Duration.ofDays(30));
        cleanup.run();
        JsonNode later = moments.json(moments.get_(fresh, id).andExpect(status().isOk()));
        assertThat(later.get("status").asText()).isEqualTo("WAITING");
        assertThat(storage.exists(key)).isTrue();
        moments.get_(bala, id).andExpect(status().isNotFound());
        // The author may always delete it.
        moments.delete_(fresh, id).andExpect(status().isNoContent());
        assertThat(storage.exists(key)).isFalse();
    }

    @Test
    void aNewMembersPhotoForMyPeople_theirWords_andAnEstablishedMembersPhoto_areNotHeld() throws Exception {
        Person fresh = social.person("Fresh2");
        social.connect(fresh, bala);
        String forPeople = moments.json(moments.create(fresh, MomentClient.withPhoto("SAY", "MY_PEOPLE", "Us",
                moments.uploadPhoto(fresh, TestImages.jpeg(64, 64)))).andExpect(status().isCreated()))
                .get("id").asText();
        moments.get_(bala, forPeople).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LIVE"));
        moments.photo(bala, forPeople).andExpect(status().isOk());

        String words = moments.say(fresh, "COMMUNITY", "Words reach everyone at once");
        moments.get_(chitra, words).andExpect(status().isOk());

        // Exactly at verified_at + 7 days the member is established: the same photo is live.
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        LocalDateTime almost = now.minus(MomentPolicy.NEW_MEMBER_PERIOD).plusSeconds(60);
        jdbc.update("UPDATE memberships SET verified_at = ?, updated_at = ? WHERE user_id = ?", almost, almost,
                fresh.id());
        moments.create(fresh, MomentClient.withPhoto("SAY", "COMMUNITY", "One minute early",
                        moments.uploadPhoto(fresh, TestImages.jpeg(64, 64))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("WAITING"));
        clock.advance(Duration.ofSeconds(60));
        moments.create(fresh, MomentClient.withPhoto("SAY", "COMMUNITY", "Established now",
                        moments.uploadPhoto(fresh, TestImages.jpeg(64, 64))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("LIVE"));
    }

    @Test
    void theHoldRule_isExactlyNewMember_photo_wholeCommunity() {
        assertThat(MomentPolicy.holdsPhoto(true, Audience.COMMUNITY, true)).isTrue();
        assertThat(MomentPolicy.holdsPhoto(true, Audience.MY_PEOPLE, true)).isFalse();
        assertThat(MomentPolicy.holdsPhoto(true, Audience.COMMUNITY, false)).isFalse();
        assertThat(MomentPolicy.holdsPhoto(false, Audience.COMMUNITY, true)).isFalse();
    }
}
