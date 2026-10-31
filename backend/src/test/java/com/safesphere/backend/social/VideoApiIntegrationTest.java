package com.safesphere.backend.social;

import static com.safesphere.backend.support.SocialClient.experienceBody;
import static com.safesphere.backend.support.TestAccounts.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.config.SocialProperties;
import com.safesphere.backend.social.event.ContentWritten;
import com.safesphere.backend.social.media.MediaStorage;
import com.safesphere.backend.social.service.SocialMediaCleanupJob;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.support.TestImages;
import com.safesphere.backend.support.TestVideos;
import com.safesphere.backend.user.repository.UserRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 17C (D-6): videos through the real API. The stored file is the stripped copy; owner-only until attached, then
 * exactly where the post is visible; never cached; played with byte ranges; a video needs its poster before a post can
 * use it (no partial post); one video per post; refused uploads leave nothing behind; every file of a key goes with
 * the post, the clean-up and the owner's removal.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
@RecordApplicationEvents
class VideoApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MediaStorage storage;
    @Autowired private SocialMediaCleanupJob cleanup;
    @Autowired private SocialProperties properties;
    @Autowired private MutableClock clock;
    @Autowired private ApplicationEvents events;

    private SocialClient social;
    private Person asha;
    private Person bala;      // connected
    private Person chitra;    // stranger
    private Person blocked;   // Asha blocked them

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        social = new SocialClient(mockMvc, objectMapper, users);
        asha = social.person("Asha");
        bala = social.person("Bala");
        chitra = social.person("Chitra");
        blocked = social.person("Blocked");
        social.connect(asha, bala);
        social.block(asha, blocked).andExpect(status().isNoContent());
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void upload_storesOnlyTheStrippedCopy_posterAfterwards_servedPrivatelyWithRanges() throws Exception {
        byte[] original = TestVideos.video().build();
        JsonNode uploaded = social.json(social.uploadVideo(asha, original).andExpect(status().isCreated()));
        String id = uploaded.get("mediaId").asText();
        assertThat(uploaded.get("kind").asText()).isEqualTo("VIDEO");
        assertThat(uploaded.get("durationMs").asInt()).isEqualTo(5_000);
        assertThat(uploaded.get("width").asInt()).isEqualTo(640);
        String key = keyOf(id);
        byte[] stored = Files.readAllBytes(storage.videoFile(key));
        assertThat(stored).hasSameSizeAs(original).isNotEqualTo(original);
        for (String leak : List.of(TestVideos.GPS, TestVideos.XMP_GPS, TestVideos.ANDROID_VERSION, TestVideos.FREE_TEXT)) {
            assertThat(TestVideos.contains(stored, leak)).as(leak).isFalse();
        }
        assertThat(jdbc.queryForObject("SELECT sha256 FROM post_media WHERE public_id = ?", String.class, id))
                .isEqualTo(java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(stored)));
        assertThat(onlyTemporaryFilesLeft()).isZero();                        // the upload's originals are gone

        social.media(asha, id).andExpect(status().isNotFound());              // no poster yet
        JsonNode poster = social.json(social.poster(asha, id, TestImages.jpegWithExifGps(64, 36))
                .andExpect(status().isOk()));
        assertThat(poster.get("mediaId").asText()).isEqualTo(id);
        byte[] posterBytes = social.media(asha, id).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(TestImages.containsExifSegment(posterBytes)).isFalse();
        social.poster(asha, id, TestImages.jpeg(32, 18)).andExpect(status().isOk()); // sent again: replaced

        byte[] served = social.video(asha, id).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andExpect(header().string("Cache-Control", Matchers.allOf(
                        Matchers.containsString("no-store"), Matchers.containsString("private"))))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(served).isEqualTo(stored);
        byte[] part = mockMvc.perform(get("/api/media/{id}/video", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(asha.token())).header(HttpHeaders.RANGE, "bytes=8-107"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 8-107/" + stored.length))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(part).isEqualTo(Arrays.copyOfRange(stored, 8, 108));
        social.video(asha, social.uploadId(asha, TestImages.jpeg(5, 5))).andExpect(status().isNotFound()); // a photo
    }

    @Test
    void visibility_ownerOnlyBeforeAttaching_thenExactlyWhereThePostIs() throws Exception {
        String id = social.videoId(asha);
        for (Person other : new Person[]{bala, chitra, blocked}) {
            social.video(other, id).andExpect(status().isNotFound());
            social.media(other, id).andExpect(status().isNotFound());
            social.poster(other, id, TestImages.jpeg(8, 8)).andExpect(status().isNotFound());
            social.deleteMedia(other, id).andExpect(status().isNotFound());
        }
        Map<String, Object> body = experienceBody("CONNECTIONS", "NONE");
        body.put("mediaIds", List.of(id));
        social.createPost(asha, body).andExpect(status().isCreated());
        int[][] expected = {{200, 200}, {200, 200}, {404, 404}, {404, 404}};
        Person[] viewers = {asha, bala, chitra, blocked};
        for (int i = 0; i < viewers.length; i++) {
            social.video(viewers[i], id).andExpect(status().is(expected[i][0]));
            social.media(viewers[i], id).andExpect(status().is(expected[i][1]));
        }
        social.poster(asha, id, TestImages.jpeg(8, 8)).andExpect(status().isNotFound()); // attached: unchanged
        social.deleteMedia(asha, id).andExpect(status().isNotFound());                    // only through the post
    }

    @Test
    void attachRules_posterFirst_oneVideoPerPost_upToSixItems() throws Exception {
        String noPoster = social.json(social.uploadVideo(asha, TestVideos.video().build())
                .andExpect(status().isCreated())).get("mediaId").asText();
        Map<String, Object> body = experienceBody("EVERYONE", "NONE");
        body.put("mediaIds", List.of(noPoster));
        social.createPost(asha, body).andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value(Matchers.containsString("not ready")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM posts p JOIN users u ON u.id = p.author_id"
                + " WHERE u.id = ?", Long.class, asha.id())).isZero();       // nothing half-made

        String first = social.videoId(asha);
        String second = social.videoId(asha);
        body.put("mediaIds", List.of(first, second));
        social.createPost(asha, body).andExpect(status().isBadRequest());

        List<String> items = new ArrayList<>(List.of(first));
        for (int i = 0; i < 5; i++) items.add(social.uploadId(asha, TestImages.jpeg(10, 10)));
        body.put("mediaIds", items);
        JsonNode post = social.json(social.createPost(asha, body).andExpect(status().isCreated()));
        JsonNode media = post.get("media");
        assertThat(media).hasSize(6);
        assertThat(media.get(0).get("kind").asText()).isEqualTo("VIDEO");
        assertThat(media.get(0).get("durationMs").asInt()).isEqualTo(5_000);
        assertThat(media.get(1).get("kind").asText()).isEqualTo("PHOTO");
        assertThat(media.get(1).get("durationMs").isNull()).isTrue();
        assertThat(social.json(social.getPost(chitra, post.get("publicId").asText())).get("media").get(0)
                .get("kind").asText()).isEqualTo("VIDEO");
    }

    @Test
    void refusedUploads_leaveNothingBehind() throws Exception {
        long rowsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM post_media", Long.class);
        social.uploadVideo(asha, TestImages.jpeg(10, 10)).andExpect(status().isBadRequest());
        social.uploadVideo(asha, TestVideos.video().durationMs(61_000).build()).andExpect(status().isBadRequest());
        social.uploadVideo(asha, TestVideos.video().extraTrack("meta").build()).andExpect(status().isBadRequest());
        social.uploadVideo(asha, TestVideos.video().topLevel("moof").build()).andExpect(status().isBadRequest());
        social.uploadVideo(asha, new byte[0]).andExpect(status().isBadRequest());
        byte[] tooLarge = new byte[properties.maxVideoBytes() + 1];
        byte[] valid = TestVideos.video().build();
        System.arraycopy(valid, 0, tooLarge, 0, valid.length);
        social.uploadVideo(asha, tooLarge).andExpect(status().isBadRequest());
        social.upload(asha, valid, "video/quicktime").andExpect(status().isUnsupportedMediaType());
        social.upload(asha, valid, "image/jpeg").andExpect(status().isBadRequest()); // the bytes decide, not the type
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_media", Long.class)).isEqualTo(rowsBefore);
        assertThat(onlyTemporaryFilesLeft()).isZero();

        String photo = social.uploadId(asha, TestImages.jpeg(5, 5));
        social.poster(asha, photo, TestImages.jpeg(5, 5)).andExpect(status().isNotFound()); // a photo has no poster
        String id = social.json(social.uploadVideo(asha, valid)).get("mediaId").asText();
        social.poster(asha, id, "not a picture".getBytes()).andExpect(status().isBadRequest());
        social.media(asha, id).andExpect(status().isNotFound());               // still no poster
    }

    @Test
    void ownerRemovesAnUnusedUpload_rowAndEveryFile() throws Exception {
        String id = social.videoId(asha);
        String key = keyOf(id);
        assertThat(storage.exists(key) && storage.hasVideo(key)).isTrue();
        social.deleteMedia(asha, id).andExpect(status().isNoContent());
        assertThat(storage.exists(key) || storage.hasVideo(key)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_media WHERE public_id = ?", Long.class, id)).isZero();
        social.deleteMedia(asha, id).andExpect(status().isNotFound());
        String photo = social.uploadId(asha, TestImages.jpeg(5, 5));
        social.deleteMedia(asha, photo).andExpect(status().isNoContent());     // photos too
        social.media(asha, photo).andExpect(status().isNotFound());
    }

    @Test
    void deletingThePost_removesTheVideoAndItsPoster() throws Exception {
        String id = social.videoId(asha);
        String key = keyOf(id);
        Map<String, Object> body = experienceBody("EVERYONE", "NONE");
        body.put("mediaIds", List.of(id));
        String postId = social.json(social.createPost(asha, body)).get("publicId").asText();
        social.deletePost(asha, postId).andExpect(status().isNoContent());
        assertThat(storage.exists(key) || storage.hasVideo(key)).isFalse();
    }

    @Test
    void cleanUpJob_removesOldUnattachedVideos_andOrphanVideoFiles() throws Exception {
        String unattached = social.videoId(asha);
        String unattachedKey = keyOf(unattached);
        String orphan = MediaStorage.newKey();
        Path orphanFile = storage.videoFile(orphan);
        Files.write(orphanFile, TestVideos.video().build());
        Path staleUpload = storage.newTemporary();

        clock.advance(Duration.ofHours(25));
        FileTime old = FileTime.from(Instant.now().minus(Duration.ofDays(2)));
        Files.setLastModifiedTime(orphanFile, old);
        Files.setLastModifiedTime(staleUpload, old);
        cleanup.run();
        assertThat(storage.exists(unattachedKey) || storage.hasVideo(unattachedKey)).isFalse();
        assertThat(Files.exists(orphanFile)).isFalse();
        assertThat(Files.exists(staleUpload)).isFalse();
    }

    @Test
    void videosHaveTheirOwnDailyLimit() throws Exception {
        byte[] valid = TestVideos.video().noMetadata().build();
        for (int i = 0; i < properties.videosPerDay(); i++) social.uploadVideo(chitra, valid).andExpect(status().isCreated());
        social.uploadVideo(chitra, valid).andExpect(status().isTooManyRequests());
        social.upload(chitra, TestImages.jpeg(5, 5), "image/jpeg").andExpect(status().isCreated()); // photos unaffected
    }

    @Test
    void aPostForEveryoneWithAVideo_countsAsAPostWithPhotos_forTheAutomaticCheck() throws Exception {
        String id = social.videoId(bala);
        Map<String, Object> body = experienceBody("EVERYONE", "NONE");
        body.put("mediaIds", List.of(id));
        social.createPost(bala, body).andExpect(status().isCreated());
        assertThat(events.stream(ContentWritten.class).filter(e -> e.authorId() == bala.id()).toList())
                .singleElement().satisfies(e -> assertThat(e.publicPhotos()).isTrue());
    }

    @Test
    void moderators_seeAVideoOfACaseOnlyThroughTheCase() throws Exception {
        Person mod = social.person("Moderator");
        social.makeModerator(mod);
        String id = social.videoId(asha);
        Map<String, Object> body = experienceBody("EVERYONE", "NONE");
        body.put("mediaIds", List.of(id));
        String post = social.json(social.createPost(asha, body).andExpect(status().isCreated())).get("publicId").asText();
        social.report(chitra, "POST", post, "NUDITY_OR_VIOLENCE", null).andExpect(status().isAccepted());
        String caseId = jdbc.queryForObject("SELECT c.public_id FROM content_cases c JOIN posts p ON p.id = c.post_id"
                + " WHERE p.public_id = ? AND c.status = 'OPEN'", String.class, post);
        JsonNode media = social.json(social.contentCase(mod, caseId)).get("content").get("media").get(0);
        assertThat(media.get("kind").asText()).isEqualTo("VIDEO");
        assertThat(media.get("durationMs").asInt()).isEqualTo(5_000);
        social.caseVideo(mod, caseId, id).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")));
        social.caseMedia(mod, caseId, id).andExpect(status().isOk())          // the poster
                .andExpect(header().string("Content-Type", "image/jpeg"));
        social.caseVideo(chitra, caseId, id).andExpect(status().isForbidden()); // not a moderator
        String photo = social.uploadId(asha, TestImages.jpeg(5, 5));
        social.caseVideo(mod, caseId, photo).andExpect(status().isNotFound());
    }

    private String keyOf(String mediaId) {
        return jdbc.queryForObject("SELECT storage_key FROM post_media WHERE public_id = ?", String.class, mediaId);
    }

    private long onlyTemporaryFilesLeft() throws Exception {
        try (var files = Files.list(storage.root())) {
            return files.filter(f -> f.getFileName().toString().endsWith(".tmp")).count();
        }
    }
}
