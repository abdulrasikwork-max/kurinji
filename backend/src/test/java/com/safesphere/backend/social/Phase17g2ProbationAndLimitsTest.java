package com.safesphere.backend.social;

import static com.safesphere.backend.support.SocialClient.experienceBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * Phase 17G-2 (U5): the two rules that need the REAL probation (the test default is none) and small daily limits: ratings from
 * accounts still on probation do not count; the daily limits of new "I went" marks and of rating writes. Own context.
 */
@SpringBootTest(properties = {"app.social.probation=72h", "app.social.visits-per-day=3", "app.social.rating-writes-per-day=3"})
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class Phase17g2ProbationAndLimitsTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private SocialClient social;
    private Person author;

    @BeforeEach
    void setUp() throws Exception {
        social = new SocialClient(mockMvc, objectMapper, users);
        author = social.person("Author");
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    @Test
    void ratingsFromAccountsOnProbation_doNotCount_untilTheyAreOlder() throws Exception {
        String placeId = visiblePlace(author);
        List<Person> fresh = new ArrayList<>();
        for (int i = 0; i < 5; i++) fresh.add(social.person("Fresh" + i));
        for (Person rater : fresh) {
            social.went(rater, placeId).andExpect(status().isOk());
            social.rate(rater, placeId, 5).andExpect(status().isOk());
        }
        Person reader = social.person("Reader");
        assertThat(social.json(social.placePage(reader, placeId, null)).get("rating").isNull())
                .as("five accounts on probation make no rating").isTrue();
        clock.advance(Duration.ofDays(4)); // a new IST day and the accounts are now older than the 72 h probation
        assertThat(social.json(social.placePage(reader, placeId, null)).get("rating").asText()).isEqualTo("4.3");
    }

    @Test
    void newMarksAreLimitedPerDay_repeatingOneCostsNothing() throws Exception {
        Person other = social.person("Other");
        List<String> places = new ArrayList<>();
        for (int i = 0; i < 2; i++) places.add(visiblePlace(author));
        for (int i = 0; i < 2; i++) places.add(visiblePlace(other));
        Person goer = social.person("Goer");
        for (int i = 0; i < 3; i++) social.went(goer, places.get(i)).andExpect(status().isOk());
        for (int i = 0; i < 5; i++) social.went(goer, places.get(0)).andExpect(status().isOk()); // already marked: free
        social.went(goer, places.get(3)).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("You have marked many places today. Please try again tomorrow."));
        social.notWent(goer, places.get(0)).andExpect(status().isNoContent());              // taking back is never refused
    }

    @Test
    void ratingWritesAreLimitedPerDay() throws Exception {
        String placeId = visiblePlace(author);
        Person goer = social.person("Rater");
        social.went(goer, placeId).andExpect(status().isOk());
        social.rate(goer, placeId, 5).andExpect(status().isOk());
        social.rate(goer, placeId, 4).andExpect(status().isOk());
        social.clearRating(goer, placeId).andExpect(status().isOk());
        social.rate(goer, placeId, 3).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("You have changed many ratings today. Please try again tomorrow."));
    }

    private String visiblePlace(Person who) throws Exception {
        String cell = GeoCell.encode(10.74 + RANDOM.nextDouble() * 0.10, 79.07 + RANDOM.nextDouble() * 0.13, 7);
        String placeId = social.json(social.createPlace(who, "Probation Place " + UUID.randomUUID().toString().substring(0, 8),
                "NATURE_OUTDOORS", cell).andExpect(status().isOk())).get("publicId").asText();
        Map<String, Object> body = experienceBody("EVERYONE", "EXACT");
        body.put("placeId", placeId);
        String post = social.json(social.createPost(who, body).andExpect(status().isCreated())).get("publicId").asText();
        LocalDateTime at = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5);
        jdbc.update("UPDATE posts SET created_at = ?, visible_from = ? WHERE public_id = ?", at, at, post);
        return placeId;
    }
}
