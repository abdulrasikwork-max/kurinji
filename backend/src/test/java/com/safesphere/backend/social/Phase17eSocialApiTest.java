package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import javax.imageio.ImageIO;
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
 * Phase 17E: the profile photo (square, re-encoded, no metadata, a new key per upload, served only through the profile
 * visibility rule, in every person summary), from four points of view: the person, a connection, a stranger, someone
 * blocked. (Today's state was retired in N1.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class Phase17eSocialApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MediaStorage media;
    @Autowired private MutableClock clock;
    @Autowired private SocialMediaCleanupJob cleanup;
    @Autowired private com.safesphere.backend.social.service.PlaceCityBackfill backfill;

    private SocialClient social;
    private Person asha;     // the person
    private Person bala;     // connected
    private Person chitra;   // stranger
    private Person dev;      // blocked by Asha

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        social = new SocialClient(mockMvc, objectMapper, users);
        asha = social.person("Asha");
        bala = social.person("Bala");
        chitra = social.person("Chitra");
        dev = social.person("Dev");
        social.connect(asha, bala);
        social.block(asha, dev).andExpect(status().isNoContent());
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    // ---- Profile photo ----

    @Test
    void photo_isASquareJpegWithoutMetadata_servedOnlyToPeopleWhoMaySeeTheProfile() throws Exception {
        String key = photoKey(social.uploadProfilePhoto(asha, TestImages.jpegWithExifGps(1200, 800))
                .andExpect(status().isOk()));
        assertThat(key).matches("[0-9a-f-]{36}");
        social.ownProfile(asha).andExpect(jsonPath("$.photo").value(key));
        social.profile(bala, asha).andExpect(jsonPath("$.photo").value(key));
        social.profile(chitra, asha).andExpect(jsonPath("$.photo").value(key));

        byte[] served = social.profilePhoto(chitra, asha, key).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(served));
        assertThat(image.getWidth()).isEqualTo(image.getHeight()).isLessThanOrEqualTo(720);
        assertThat(new String(served, StandardCharsets.ISO_8859_1)).doesNotContain(TestImages.EXIF_MARKER);

        social.profilePhoto(dev, asha, key).andExpect(status().isNotFound());               // blocked = not found
        social.profilePhoto(chitra, asha, "00000000-0000-4000-8000-000000000000").andExpect(status().isNotFound());
        social.profile(dev, asha).andExpect(status().isNotFound());
    }

    @Test
    void photo_isInEveryPersonSummary_andANewUploadGetsANewKey_theOldFileGoes() throws Exception {
        String first = photoKey(social.uploadProfilePhoto(asha, TestImages.jpeg(300, 300)).andExpect(status().isOk()));
        String post = social.experience(asha, "EVERYONE");
        social.getPost(chitra, post).andExpect(jsonPath("$.author.photo").value(first));

        String second = photoKey(social.uploadProfilePhoto(asha, TestImages.png(500, 200)).andExpect(status().isOk()));
        assertThat(second).isNotEqualTo(first);
        assertThat(media.exists(first)).isFalse();
        assertThat(media.exists(second)).isTrue();
        social.profilePhoto(bala, asha, first).andExpect(status().isNotFound());           // old key: gone
        social.getPost(chitra, post).andExpect(jsonPath("$.author.photo").value(second));

        social.removeProfilePhoto(asha).andExpect(status().isNoContent());
        social.removeProfilePhoto(asha).andExpect(status().isNoContent());                 // same answer
        assertThat(media.exists(second)).isFalse();
        social.ownProfile(asha).andExpect(jsonPath("$.photo").isEmpty());
        social.getPost(chitra, post).andExpect(jsonPath("$.author.photo").isEmpty());
    }

    @Test
    void photo_refusesWhatIsNotAPhoto_andNeedsAProfile() throws Exception {
        social.uploadProfilePhoto(asha, "not an image at all".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isBadRequest());
        social.ownProfile(asha).andExpect(jsonPath("$.photo").isEmpty());
        jdbc.update("DELETE FROM profiles WHERE user_id = ?", chitra.id());
        social.uploadProfilePhoto(chitra, TestImages.jpeg(100, 100)).andExpect(status().isNotFound());
    }

    @Test
    void theCleanUp_keepsProfilePhotoFiles() throws Exception {
        String key = photoKey(social.uploadProfilePhoto(asha, TestImages.jpeg(200, 200)).andExpect(status().isOk()));
        clock.advance(Duration.ofDays(2));          // older than the unattached-upload lifetime
        cleanup.run();
        assertThat(media.exists(key)).isTrue();
    }

    // ---- Cities (D-5) ----

    @Test
    void aPlaceInANewCatalogCity_getsItsCity_andCanBeListedThere() throws Exception {
        String delhi = com.safesphere.backend.common.geo.GeoCell.encode(28.6139, 77.2090, 7);
        String id = social.place(asha, "India Gate lawns", delhi);
        social.createPlace(asha, "India Gate lawns", "NATURE_OUTDOORS", delhi).andExpect(jsonPath("$.city").value("DELHI"));
        assertThat(jdbc.queryForObject("SELECT city FROM places WHERE public_id = ?", String.class, id))
                .isEqualTo("DELHI");
        // A place stored before V28 with no city gets one from the start-up backfill, and never loses one.
        jdbc.update("UPDATE places SET city = NULL WHERE public_id = ?", id);
        assertThat(backfill.backfill()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT city FROM places WHERE public_id = ?", String.class, id))
                .isEqualTo("DELHI");
        social.edition(bala, "delhi").andExpect(status().isOk()).andExpect(jsonPath("$.city").value("DELHI"));
        social.edition(bala, "OTHER").andExpect(status().isBadRequest());
        social.edition(bala, "ATLANTIS").andExpect(status().isBadRequest());
    }

    @Test
    void aCityPost_takesAnyCatalogCityOrOther_andNothingElse() throws Exception {
        java.util.Map<String, Object> body = new java.util.HashMap<>(SocialClient.experienceBody("EVERYONE", "CITY"));
        body.put("city", "MYSURU");
        social.createPost(asha, body).andExpect(status().isCreated()).andExpect(jsonPath("$.city").value("MYSURU"));
        body.put("city", "OTHER");
        social.createPost(asha, body).andExpect(status().isCreated());
        body.put("city", "ATLANTIS");
        social.createPost(asha, body).andExpect(status().isBadRequest());
    }

    private String photoKey(org.springframework.test.web.servlet.ResultActions answer) throws Exception {
        JsonNode node = social.json(answer);
        return node.get("photo").asText();
    }
}
