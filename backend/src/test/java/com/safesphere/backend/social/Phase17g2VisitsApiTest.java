package com.safesphere.backend.social;

import static com.safesphere.backend.support.SocialClient.experienceBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.user.repository.UserRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * Phase 17G-2 (U5): "I went" and the guarded rating through the real API, from the points of view of the author, a stranger, a
 * blocked person and a moderator. Each test uses its own Thanjavur cell so the shared test database does not mix them. The Q1 delay
 * is ended with SQL on the post rows; "past probation" is made with SQL on users.created_at; the IST-day memo is moved with the clock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class Phase17g2VisitsApiTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private SocialClient social;
    private Person asha;     // writes the experiences
    private Person bala;
    private Person chitra;   // a stranger
    private Person dev;      // blocked by Asha

    @BeforeEach
    void setUp() throws Exception {
        social = new SocialClient(mockMvc, objectMapper, users);
        asha = social.person("Asha");
        bala = social.person("Bala");
        chitra = social.person("Chitra");
        dev = social.person("Dev");
        social.block(asha, dev).andExpect(status().isNoContent());
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    // ---- "I went" ----

    @Test
    void went_isOnlyForAPlaceThePersonCanSee() throws Exception {
        String empty = place(asha);
        social.went(chitra, empty).andExpect(status().isNotFound());                  // no experience at all

        String placeId = place(asha);
        String post = exact(asha, "EVERYONE", placeId);
        social.went(chitra, placeId).andExpect(status().isNotFound());                // the 1-3 h delay is not over
        social.went(asha, placeId).andExpect(status().isOk());                        // their own post: they went
        visible(post);
        social.went(chitra, placeId).andExpect(status().isOk()).andExpect(jsonPath("$.rating").isEmpty());
        social.went(dev, placeId).andExpect(status().isNotFound());                   // blocked by the author: not seen

        String friends = place(asha);
        visible(exact(asha, "CONNECTIONS", friends));
        social.went(chitra, friends).andExpect(status().isNotFound());                // never for a post they may not read
        social.went(chitra, UUID.randomUUID().toString()).andExpect(status().isNotFound());
    }

    @Test
    void went_isIdempotent_private_andShownOnlyToItsOwner() throws Exception {
        String placeId = place(asha);
        visible(exact(asha, "EVERYONE", placeId));
        social.went(chitra, placeId).andExpect(status().isOk());
        social.went(chitra, placeId).andExpect(status().isOk());
        assertThat(rows(placeId)).hasSize(1);

        JsonNode mine = social.json(social.placePage(chitra, placeId, null).andExpect(status().isOk()));
        assertThat(mine.get("yourVisit").isNull()).isFalse();
        assertThat(mine.get("yourVisit").has("rating")).isTrue();
        JsonNode others = social.json(social.placePage(bala, placeId, null).andExpect(status().isOk()));
        assertThat(others.get("yourVisit") == null || others.get("yourVisit").isNull()).isTrue();
        // nobody learns who went: no person, handle or id of Chitra in what Bala (or Asha) is given
        for (Person viewer : List.of(bala, asha)) {
            String raw = social.placePage(viewer, placeId, null).andReturn().getResponse().getContentAsString();
            assertThat(raw).doesNotContain(chitra.publicId()).doesNotContain(chitra.handle());
        }
    }

    @Test
    void notWent_takesBackTheVisitAndTheRating_andAnswersTheSameWhateverWasThere() throws Exception {
        String placeId = place(asha);
        visible(exact(asha, "EVERYONE", placeId));
        social.went(chitra, placeId).andExpect(status().isOk());
        social.rate(chitra, placeId, 4).andExpect(status().isOk());
        social.notWent(chitra, placeId).andExpect(status().isNoContent());
        assertThat(rows(placeId)).isEmpty();
        social.notWent(chitra, placeId).andExpect(status().isNoContent());
        social.notWent(chitra, UUID.randomUUID().toString()).andExpect(status().isNoContent());
        social.rate(chitra, placeId, 4).andExpect(status().isConflict());              // the rating went with it
    }

    // ---- the rating ----

    @Test
    void rating_needsWent_isOneToFive_andCanBeChangedAndTakenBack() throws Exception {
        String placeId = place(asha);
        visible(exact(asha, "EVERYONE", placeId));
        social.rate(chitra, placeId, 4).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Mark that you went there first"));
        social.clearRating(chitra, placeId).andExpect(status().isConflict());
        social.went(chitra, placeId).andExpect(status().isOk());
        for (Object bad : new Object[] {0, 6, -1, null, 100}) {
            social.rate(chitra, placeId, bad).andExpect(status().isBadRequest());
        }
        social.rate(chitra, placeId, "five").andExpect(status().isBadRequest());
        social.rate(chitra, placeId, 4).andExpect(status().isOk()).andExpect(jsonPath("$.rating").value(4));
        social.rate(chitra, placeId, 2).andExpect(status().isOk()).andExpect(jsonPath("$.rating").value(2));
        assertThat(social.json(social.placePage(chitra, placeId, null)).get("yourVisit").get("rating").asInt()).isEqualTo(2);
        social.clearRating(chitra, placeId).andExpect(status().isOk()).andExpect(jsonPath("$.rating").isEmpty());
        assertThat(rows(placeId)).hasSize(1);                                          // still "went"
        assertThat(jdbc.queryForObject("SELECT rated_at FROM place_visits WHERE rating IS NULL AND user_id = ?",
                Object.class, chitra.id())).isNull();
        social.rate(asha, placeId, 5).andExpect(status().isConflict());                // the author went too, but did not say so
    }

    @Test
    void aPausedPersonCannotRate_butCanStillMarkWent() throws Exception {
        String placeId = place(asha);
        visible(exact(asha, "EVERYONE", placeId));
        social.went(chitra, placeId).andExpect(status().isOk());
        jdbc.update("INSERT INTO social_standing (user_id, restriction, restricted_until, reason, decision, decided_at)"
                + " VALUES (?, 'POSTING_PAUSED', DATEADD('DAY', 3, CURRENT_TIMESTAMP), 'SPAM',"
                + " '33333333-3333-4333-8333-333333333333', CURRENT_TIMESTAMP)", chitra.id());
        social.rate(chitra, placeId, 5).andExpect(status().isForbidden());
        social.went(chitra, placeId).andExpect(status().isOk());
    }

    @Test
    void thePublicRating_needsFiveCountedRaters_andIsText_withNoCountNoNamesNoTimes() throws Exception {
        String placeId = place(asha);
        visible(exact(asha, "EVERYONE", placeId));
        List<Person> raters = people("Rater", 5, true);
        for (int i = 0; i < 5; i++) {
            social.went(raters.get(i), placeId).andExpect(status().isOk());
            social.rate(raters.get(i), placeId, 5).andExpect(status().isOk());
        }
        JsonNode page = social.json(social.placePage(chitra, placeId, null).andExpect(status().isOk()));
        assertThat(page.get("rating").asText()).isEqualTo("4.0");                      // 18A-1: five 10-day-old, one-place accounts count half: (17.5 + 12.5) / 7.5
        String raw = social.placePage(chitra, placeId, null).andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain("raters").doesNotContain("count").doesNotContain("average").doesNotContain("votes");
        for (Person rater : raters) assertThat(raw).doesNotContain(rater.publicId()).doesNotContain(rater.handle());
        // the same text for everybody who may read the page; the posts shown are not affected by it
        assertThat(social.json(social.placePage(bala, placeId, null)).get("rating").asText()).isEqualTo("4.0");
    }

    @Test
    void fewerThanFiveCountedRaters_showNothing() throws Exception {
        String four = place(asha);
        visible(exact(asha, "EVERYONE", four));
        for (Person rater : people("Four", 4, true)) {
            social.went(rater, four).andExpect(status().isOk());
            social.rate(rater, four, 5).andExpect(status().isOk());
        }
        assertThat(social.json(social.placePage(chitra, four, null)).get("rating").isNull()).isTrue();
    }

    @Test
    void theRatingChangesOnlyOncePerIstDay_soOneMoreRatingCannotBeRead() throws Exception {
        String placeId = place(asha);
        visible(exact(asha, "EVERYONE", placeId));
        List<Person> raters = people("Day", 6, true);
        for (int i = 0; i < 5; i++) {
            social.went(raters.get(i), placeId).andExpect(status().isOk());
            social.rate(raters.get(i), placeId, 5).andExpect(status().isOk());
        }
        assertThat(social.json(social.placePage(chitra, placeId, null)).get("rating").asText()).isEqualTo("4.0");
        social.went(raters.get(5), placeId).andExpect(status().isOk());
        social.rate(raters.get(5), placeId, 1).andExpect(status().isOk());
        assertThat(social.json(social.placePage(chitra, placeId, null)).get("rating").asText())
                .as("same IST day: unchanged, so before/after cannot be compared").isEqualTo("4.0");
        clock.advance(Duration.ofDays(1));
        assertThat(social.json(social.placePage(chitra, placeId, null)).get("rating").asText())
                .isEqualTo("3.8");                                                      // (17.5 + 12.5 + 0.5) / 8 = 3.81
    }

    @Test
    void ratingsNeverChangeWhichPostsAreShownOrInWhatOrder() throws Exception {
        String placeId = place(asha);
        String first = visibleAgo(exact(asha, "EVERYONE", placeId), Duration.ofMinutes(30));
        String second = visibleAgo(exact(asha, "EVERYONE", placeId), Duration.ofMinutes(10));
        List<String> before = social.postIds(social.placePage(chitra, placeId, null));
        for (Person rater : people("Order", 5, true)) {
            social.went(rater, placeId).andExpect(status().isOk());
            social.rate(rater, placeId, 5).andExpect(status().isOk());
        }
        clock.advance(Duration.ofDays(1));
        assertThat(social.postIds(social.placePage(chitra, placeId, null))).isEqualTo(before)
                .containsExactly(second, first);
    }

    // ---- merges ----

    @Test
    void aMergedPlace_takesItsVisitsAlong_keepingOneRowPerPerson_andTheRatedOne() throws Exception {
        Person mod = social.person("Mod");
        social.makeModerator(mod);
        String area = GeoCell.encode(10.74 + RANDOM.nextDouble() * 0.10, 79.07 + RANDOM.nextDouble() * 0.13, 7).substring(0, 6);
        String dup = place(asha, area + "b");
        String keep = place(asha, area + "c");
        visible(exact(asha, "EVERYONE", dup));
        visible(exact(asha, "EVERYONE", keep));
        Person onlyDup = chitra;
        Person ratedAtDup = bala;
        Person ratedBoth = social.person("Both");
        for (String placeId : List.of(dup, keep)) social.went(ratedBoth, placeId).andExpect(status().isOk());
        social.went(onlyDup, dup).andExpect(status().isOk());
        social.went(ratedAtDup, dup).andExpect(status().isOk());
        social.went(ratedAtDup, keep).andExpect(status().isOk());
        social.rate(onlyDup, dup, 3).andExpect(status().isOk());
        social.rate(ratedAtDup, dup, 5).andExpect(status().isOk());            // rated at the duplicate only
        social.rate(ratedBoth, dup, 2).andExpect(status().isOk());
        social.rate(ratedBoth, keep, 4).andExpect(status().isOk());            // rated at both: the survivor's stays

        social.mergePlace(mod, dup, keep).andExpect(status().isNoContent());

        assertThat(rows(dup)).isEmpty();
        assertThat(rows(keep)).hasSize(3);                                      // one row per person
        assertThat(ratingOf(keep, onlyDup)).isEqualTo(3);
        assertThat(ratingOf(keep, ratedAtDup)).isEqualTo(5);                    // the rated duplicate row replaced the unrated one
        assertThat(ratingOf(keep, ratedBoth)).isEqualTo(4);
        social.went(onlyDup, dup).andExpect(status().isOk());                   // a merged place stands for the survivor
        assertThat(rows(keep)).hasSize(3);
    }

    // ---- helpers ----

    private String place(Person who) throws Exception {
        return place(who, GeoCell.encode(10.74 + RANDOM.nextDouble() * 0.10, 79.07 + RANDOM.nextDouble() * 0.13, 7));
    }

    private String place(Person who, String cell) throws Exception {
        return social.json(social.createPlace(who, "Visit Place " + UUID.randomUUID().toString().substring(0, 8),
                "NATURE_OUTDOORS", cell).andExpect(status().isOk())).get("publicId").asText();
    }

    private String exact(Person who, String audience, String placeId) throws Exception {
        Map<String, Object> body = experienceBody(audience, "EXACT");
        body.put("placeId", placeId);
        return social.json(social.createPost(who, body).andExpect(status().isCreated())).get("publicId").asText();
    }

    private String visible(String postId) {
        return visibleAgo(postId, Duration.ofMinutes(5));
    }

    private String visibleAgo(String postId, Duration ago) {
        LocalDateTime at = LocalDateTime.now(ZoneOffset.UTC).minus(ago);
        jdbc.update("UPDATE posts SET created_at = ?, visible_from = ? WHERE public_id = ?", at, at, postId);
        return postId;
    }

    /** [count] new people; [aged] puts their accounts well past the 72 h probation. */
    @Test
    void fiveConnectedRaters_areOneVoice_soThePlaceShowsNothing_untilIndependentPeopleRate() throws Exception {
        String placeId = place(asha);
        visible(exact(asha, "EVERYONE", placeId));
        List<Person> friends = people("Friend", 5, true);
        for (int i = 1; i < 5; i++) social.connect(friends.get(0), friends.get(i));
        for (Person friend : friends) {
            social.went(friend, placeId).andExpect(status().isOk());
            social.rate(friend, placeId, 5).andExpect(status().isOk());
        }
        assertThat(social.json(social.placePage(chitra, placeId, null)).get("rating").isNull())
                .as("five friends are one voice").isTrue();
        // every one of them still sees their own rating: nothing is hidden from its owner
        assertThat(social.json(social.placePage(friends.get(2), placeId, null)).get("yourVisit").get("rating").asInt()).isEqualTo(5);
        for (Person other : people("Other", 4, true)) {
            social.went(other, placeId).andExpect(status().isOk());
            social.rate(other, placeId, 3).andExpect(status().isOk());
        }
        clock.advance(Duration.ofDays(1));
        // five voices, each from 10/11-day-old one-place accounts (half): (17.5 + 0.5*5 + 4*0.5*3) / 7.5 = 3.47
        assertThat(social.json(social.placePage(chitra, placeId, null)).get("rating").asText()).isEqualTo("3.5");
    }

    private List<Person> people(String name, int count, boolean aged) throws Exception {
        List<Person> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Person person = social.person(name + i);
            if (aged) {
                jdbc.update("UPDATE users SET created_at = ? WHERE id = ?",
                        LocalDateTime.now(ZoneOffset.UTC).minusDays(10), person.id());
            }
            out.add(person);
        }
        return out;
    }

    private List<Long> rows(String placePublicId) {
        return jdbc.queryForList("SELECT v.id FROM place_visits v JOIN places p ON p.id = v.place_id WHERE p.public_id = ?",
                Long.class, placePublicId);
    }

    private Integer ratingOf(String placePublicId, Person who) {
        return jdbc.queryForObject("SELECT v.rating FROM place_visits v JOIN places p ON p.id = v.place_id"
                + " WHERE p.public_id = ? AND v.user_id = ?", Integer.class, placePublicId, who.id());
    }
}
