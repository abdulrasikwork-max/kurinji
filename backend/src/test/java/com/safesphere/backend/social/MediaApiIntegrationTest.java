package com.safesphere.backend.social;

import static com.safesphere.backend.support.SocialClient.experienceBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.social.media.MediaStorage;
import com.safesphere.backend.social.service.SocialMediaCleanupJob;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.support.TestImages;
import com.safesphere.backend.user.repository.UserRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Phase 13B: photos through the real API. Uploads are sanitised before they are stored; owner-only until attached;
 * afterwards visible exactly where the post is; never cached; deleted with the post; orphans cleaned up.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class MediaApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MediaStorage storage;
    @Autowired private SocialMediaCleanupJob cleanup;
    @Autowired private MutableClock clock;

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
    void upload_storesOnlyTheSanitisedJpeg_andServesItPrivately() throws Exception {
        JsonNode uploaded = social.json(social.upload(asha, TestImages.jpegWithExifGps(80, 60), "image/jpeg")
                .andExpect(status().isCreated()));
        String mediaId = uploaded.get("mediaId").asText();
        assertThat(uploaded.get("width").asInt()).isEqualTo(80);
        String key = jdbc.queryForObject("SELECT storage_key FROM post_media WHERE public_id = ?", String.class, mediaId);
        byte[] stored = Files.readAllBytes(storage.root().resolve(key + ".jpg"));
        assertThat(TestImages.containsExifSegment(stored)).isFalse();
        byte[] served = social.media(asha, mediaId).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("no-store"), org.hamcrest.Matchers.containsString("private"))))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(served).isEqualTo(stored);
        // PNG in, JPEG out.
        String pngId = social.json(social.upload(asha, TestImages.png(10, 10), "image/png")
                .andExpect(status().isCreated())).get("mediaId").asText();
        byte[] png = social.media(asha, pngId).andReturn().getResponse().getContentAsByteArray();
        assertThat(png[0] & 0xFF).isEqualTo(0xFF);
    }

    @Test
    void beforeAttaching_onlyTheOwnerCanFetchIt() throws Exception {
        String mediaId = social.uploadId(asha, TestImages.jpeg(20, 20));
        social.media(asha, mediaId).andExpect(status().isOk());
        for (Person other : new Person[] {bala, chitra, blocked}) social.media(other, mediaId).andExpect(status().isNotFound());
        social.media(asha, UUID.randomUUID().toString()).andExpect(status().isNotFound());
    }

    @Test
    void afterAttaching_visibleExactlyWhereThePostIs() throws Exception {
        String everyone = photo(asha, "EVERYONE");
        String connections = photo(asha, "CONNECTIONS");
        String onlyMe = photo(asha, "ONLY_ME");
        expect(everyone, 200, 200, 200, 404);
        expect(connections, 200, 200, 404, 404);
        expect(onlyMe, 200, 404, 404, 404);
    }

    @Test
    void attachRules_ownUnattachedPhotosOnly_atMostSix() throws Exception {
        String ashas = social.uploadId(asha, TestImages.jpeg(10, 10));
        Map<String, Object> body = experienceBody("EVERYONE", "NONE");
        body.put("mediaIds", List.of(ashas));
        social.createPost(bala, body).andExpect(status().isNotFound());       // someone else's upload
        social.createPost(asha, body).andExpect(status().isCreated());
        social.createPost(asha, body).andExpect(status().isNotFound());       // already attached
        List<String> seven = new ArrayList<>();
        for (int i = 0; i < 7; i++) seven.add(social.uploadId(asha, TestImages.jpeg(5, 5)));
        body.put("mediaIds", seven);
        social.createPost(asha, body).andExpect(status().isBadRequest());
        body.put("mediaIds", seven.subList(0, 6));
        JsonNode post = social.json(social.createPost(asha, body).andExpect(status().isCreated()));
        List<String> order = new ArrayList<>();
        post.get("media").forEach(m -> order.add(m.get("mediaId").asText()));
        assertThat(order).containsExactlyElementsOf(seven.subList(0, 6));    // the order chosen on the phone
    }

    @Test
    void refusedUploads() throws Exception {
        social.upload(asha, "not an image at all".getBytes(), "image/jpeg").andExpect(status().isBadRequest());
        social.upload(asha, TestImages.pngClaiming(50_000, 50_000), "image/png").andExpect(status().isBadRequest());
        social.upload(asha, TestImages.jpeg(10, 10), "text/plain").andExpect(status().isUnsupportedMediaType());
        byte[] tooLarge = new byte[5 * 1024 * 1024 + 1];
        byte[] jpeg = TestImages.jpeg(10, 10);
        System.arraycopy(jpeg, 0, tooLarge, 0, jpeg.length);
        social.upload(asha, tooLarge, "image/jpeg").andExpect(status().isBadRequest());
    }

    @Test
    void deletingThePost_removesRowsAndFiles() throws Exception {
        String mediaId = social.uploadId(asha, TestImages.jpeg(10, 10));
        String key = jdbc.queryForObject("SELECT storage_key FROM post_media WHERE public_id = ?", String.class, mediaId);
        Map<String, Object> body = experienceBody("EVERYONE", "NONE");
        body.put("mediaIds", List.of(mediaId));
        String postId = social.json(social.createPost(asha, body)).get("publicId").asText();
        social.commentId(bala, postId, "BEEN_HERE", "Lovely place.", null);
        assertThat(storage.exists(key)).isTrue();
        social.deletePost(asha, postId).andExpect(status().isNoContent());
        assertThat(storage.exists(key)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_media WHERE public_id = ?", Long.class, mediaId)).isZero();
        social.media(asha, mediaId).andExpect(status().isNotFound());
    }

    @Test
    void cleanUpJob_removesOldUnattachedUploads_andOrphanFiles_only() throws Exception {
        String unattached = social.uploadId(asha, TestImages.jpeg(10, 10));
        String unattachedKey = keyOf(unattached);
        String attached = photo(asha, "EVERYONE");
        String attachedKey = keyOf(attached);
        String orphan = MediaStorage.newKey();
        storage.write(orphan, TestImages.jpeg(5, 5));
        Path orphanFile = storage.root().resolve(orphan + ".jpg");

        cleanup.run();                                                          // nothing is old yet
        assertThat(storage.exists(unattachedKey) && storage.exists(orphan)).isTrue();

        clock.advance(Duration.ofHours(25));
        Files.setLastModifiedTime(orphanFile, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        Files.setLastModifiedTime(storage.root().resolve(attachedKey + ".jpg"),
                FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        cleanup.run();
        assertThat(storage.exists(unattachedKey)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_media WHERE public_id = ?", Long.class, unattached)).isZero();
        assertThat(storage.exists(orphan)).isFalse();
        assertThat(storage.exists(attachedKey)).isTrue();                      // in use: kept
        clock.reset();
        social.media(chitra, attached).andExpect(status().isOk());
    }

    /** [viewer order: owner, connection, stranger, blocked]. */
    private void expect(String mediaId, int owner, int connection, int stranger, int blockedStatus) throws Exception {
        social.media(asha, mediaId).andExpect(status().is(owner));
        social.media(bala, mediaId).andExpect(status().is(connection));
        social.media(chitra, mediaId).andExpect(status().is(stranger));
        social.media(blocked, mediaId).andExpect(status().is(blockedStatus));
    }

    /** A photo attached to a new NONE-precision post of [who] for [audience]; returns the media id. */
    private String photo(Person who, String audience) throws Exception {
        String mediaId = social.uploadId(who, TestImages.jpeg(16, 16));
        Map<String, Object> body = experienceBody(audience, "NONE");
        body.put("mediaIds", List.of(mediaId));
        social.createPost(who, body).andExpect(status().isCreated());
        return mediaId;
    }

    private String keyOf(String mediaId) {
        return jdbc.queryForObject("SELECT storage_key FROM post_media WHERE public_id = ?", String.class, mediaId);
    }
}
