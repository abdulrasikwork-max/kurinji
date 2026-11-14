package com.safesphere.backend.social;

import static com.safesphere.backend.support.SocialClient.experienceBody;
import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.HashMap;
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
 * Phase 18A-2 (V31): presence ("Been here" tapped at the place) and disclosure ("I was paid, or got this free") through the real
 * API. What is stored is read with SQL, because no answer may say it. Each test uses its own Thanjavur cell.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class Phase18a2PresenceDisclosureApiTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** A cell in Chennai: far from every test place. */
    private static final String FAR = GeoCell.encode(13.05, 80.27, 7);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private SocialClient social;
    private Person asha;     // writes the experiences
    private Person bala;
    private Person chitra;   // a stranger

    @BeforeEach
    void setUp() throws Exception {
        social = new SocialClient(mockMvc, objectMapper, users);
        asha = social.person("Asha");
        bala = social.person("Bala");
        chitra = social.person("Chitra");
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    // ---- presence ----

    @Test
    void beenHere_isConfirmedOnlyByACellAtThePlace_canBeConfirmedLater_andIsNeverTakenBack() throws Exception {
        String cell = cell();
        String placeId = place(asha, cell);
        visible(exact(asha, placeId, null));
        String nextDoor = GeoCell.neighbourhood(cell).stream().filter(c -> !c.equals(cell)).findFirst().orElseThrow();

        social.went(chitra, placeId).andExpect(status().isOk());                       // an older app: no body
        assertThat(atPlace(placeId, chitra)).isFalse();
        social.went(chitra, placeId, FAR).andExpect(status().isOk());                  // somewhere else: saved, not confirmed
        assertThat(atPlace(placeId, chitra)).isFalse();
        social.went(chitra, placeId, nextDoor).andExpect(status().isOk());             // came back: the cell beside the place
        assertThat(atPlace(placeId, chitra)).isTrue();
        social.went(chitra, placeId, FAR).andExpect(status().isOk());                  // never lowered
        social.went(chitra, placeId).andExpect(status().isOk());
        assertThat(atPlace(placeId, chitra)).isTrue();

        social.went(bala, placeId, cell.toUpperCase()).andExpect(status().isOk());     // the first tap, at the place
        assertThat(atPlace(placeId, bala)).isTrue();
        assertThat(rows(placeId)).as("still one row per person").isEqualTo(2);
    }

    @Test
    void aMalformedCell_isRefused_andStoresNothing() throws Exception {
        String placeId = place(asha, cell());
        visible(exact(asha, placeId, null));
        for (String bad : List.of("aaaaaaa", "tf344", "tf344zebb", "13.05,80.27")) {     // not base 32, too short, too long, a coordinate
            social.went(chitra, placeId, bad).andExpect(status().isBadRequest());
        }
        assertThat(rows(placeId)).isZero();
        social.went(chitra, placeId, "  ").andExpect(status().isOk());                 // blank = no cell
        assertThat(atPlace(placeId, chitra)).isFalse();
    }

    @Test
    void noAnswerSaysWhetherPresenceWasConfirmed() throws Exception {
        String cell = cell();
        String placeId = place(asha, cell);
        visible(exact(asha, placeId, null));
        String confirmed = social.went(chitra, placeId, cell).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String not = social.went(bala, placeId, FAR).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(confirmed).as("the same bytes either way").isEqualTo(not);
        for (Person viewer : List.of(chitra, bala, asha)) {
            String page = social.placePage(viewer, placeId, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(page.toLowerCase()).doesNotContain("atplace").doesNotContain("at_place").doesNotContain("present");
        }
    }

    @Test
    void aVisitConfirmedAtThePlace_countsOneAndAHalfTimes_inThePublicRating() throws Exception {
        String cell = cell();
        String placeId = place(asha, cell);
        visible(exact(asha, placeId, null));
        // five independent 10-day-old one-place accounts (weight 0.5) rate 3 from home; without presence: (17.5 + 7.5) / 7.5 = 3.3
        for (Person rater : people("Home", 5)) {
            social.went(rater, placeId).andExpect(status().isOk());
            social.rate(rater, placeId, 3).andExpect(status().isOk());
        }
        // five more rate 5 at the place (0.75 each): (17.5 + 7.5 + 18.75) / (5 + 2.5 + 3.75) = 3.89; unweighted it would be 3.75
        for (Person rater : people("There", 5)) {
            social.went(rater, placeId, cell).andExpect(status().isOk());
            social.rate(rater, placeId, 5).andExpect(status().isOk());
        }
        assertThat(social.json(social.placePage(chitra, placeId, null)).get("rating").asText()).isEqualTo("3.9");
    }

    // ---- disclosure ----

    @Test
    void aDisclosedPost_showsItsLabelToEveryReader_andOthersDoNot() throws Exception {
        String placeId = place(asha, cell());
        String paid = visible(exact(asha, placeId, true));
        String plain = visible(exact(asha, placeId, null));
        String notSaid = visible(exact(asha, placeId, false));
        for (Person viewer : List.of(asha, bala, chitra)) {
            assertThat(social.json(social.getPost(viewer, paid).andExpect(status().isOk())).get("disclosed").asBoolean()).isTrue();
            assertThat(social.json(social.getPost(viewer, plain).andExpect(status().isOk())).get("disclosed").asBoolean()).isFalse();
            assertThat(social.json(social.getPost(viewer, notSaid).andExpect(status().isOk())).get("disclosed").asBoolean()).isFalse();
        }
    }

    @Test
    void aDisclosure_canBeAddedByAnEdit_andIsNeverTurnedOff() throws Exception {
        Map<String, Object> body = experienceBody("EVERYONE", "NONE");
        String id = social.json(social.createPost(asha, body).andExpect(status().isCreated())).get("publicId").asText();
        Map<String, Object> edit = new HashMap<>(body);
        assertThat(social.json(social.editPost(asha, id, edit).andExpect(status().isOk())).get("disclosed").asBoolean()).isFalse();
        edit.put("disclosed", true);
        assertThat(social.json(social.editPost(asha, id, edit).andExpect(status().isOk())).get("disclosed").asBoolean()).isTrue();
        edit.put("disclosed", false);
        assertThat(social.json(social.editPost(asha, id, edit).andExpect(status().isOk())).get("disclosed").asBoolean()).isTrue();
        edit.remove("disclosed");
        assertThat(social.json(social.editPost(asha, id, edit).andExpect(status().isOk())).get("disclosed").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT disclosed FROM posts WHERE public_id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    void aQuestion_isNeverDisclosed() throws Exception {
        Map<String, Object> ask = new HashMap<>(Map.of("type", "ASK", "body", "How do people get back at night?",
                "precision", "CITY", "city", "ERODE", "audience", "EVERYONE", "commentPolicy", "EVERYONE"));
        ask.put("disclosed", true);
        JsonNode made = social.json(social.createPost(asha, ask).andExpect(status().isCreated()));
        assertThat(made.get("disclosed").asBoolean()).isFalse();
        assertThat(social.json(social.editPost(asha, made.get("publicId").asText(), ask).andExpect(status().isOk()))
                .get("disclosed").asBoolean()).isFalse();
    }

    @Test
    void theRatingOfSomeoneWhoDisclosedAPaidVisitThere_isNotCounted_butStaysTheirs() throws Exception {
        String placeId = place(asha, cell());
        visible(exact(asha, placeId, null));
        List<Person> raters = people("Rater", 5);
        for (Person rater : raters) {
            social.went(rater, placeId).andExpect(status().isOk());
            social.rate(rater, placeId, 5).andExpect(status().isOk());
        }
        Person paid = people("Paid", 1).get(0);
        social.went(paid, placeId).andExpect(status().isOk());
        social.rate(paid, placeId, 1).andExpect(status().isOk());
        exact(paid, placeId, true);                                                     // their disclosed post at this place
        Person honest = people("Honest", 1).get(0);                                     // an ordinary post does not exclude anyone
        social.went(honest, placeId).andExpect(status().isOk());
        social.rate(honest, placeId, 5).andExpect(status().isOk());
        exact(honest, placeId, null);

        // six counted raters at 0.5, all 5: (17.5 + 15) / 8 = 4.06. With the paid 1 it would be (17.5 + 15.5) / 8.5 = 3.88
        assertThat(social.json(social.placePage(chitra, placeId, null)).get("rating").asText()).isEqualTo("4.1");
        JsonNode own = social.json(social.placePage(paid, placeId, null).andExpect(status().isOk()));
        assertThat(own.get("yourVisit").get("rating").asInt()).as("still saved and shown to its owner").isEqualTo(1);
    }

    @Test
    void aDisclosedPostElsewhere_doesNotTouchTheRatingOfAnotherPlace() throws Exception {
        String placeId = place(asha, cell());
        visible(exact(asha, placeId, null));
        String other = place(asha, cell());
        List<Person> raters = people("Elsewhere", 5);
        for (Person rater : raters) {
            social.went(rater, placeId).andExpect(status().isOk());
            social.rate(rater, placeId, 5).andExpect(status().isOk());
        }
        exact(raters.get(0), other, true);                                              // paid at ANOTHER place
        assertThat(social.json(social.placePage(chitra, placeId, null)).get("rating").asText()).isEqualTo("4.0");
    }

    // ---- helpers ----

    private static String cell() {
        return GeoCell.encode(10.74 + RANDOM.nextDouble() * 0.10, 79.07 + RANDOM.nextDouble() * 0.13, 7);
    }

    private String place(Person who, String cell) throws Exception {
        return social.json(social.createPlace(who, "Trust Place " + UUID.randomUUID().toString().substring(0, 8),
                "NATURE_OUTDOORS", cell).andExpect(status().isOk())).get("publicId").asText();
    }

    /** An Everyone experience at the exact place; [disclosed] null = the field is not sent. */
    private String exact(Person who, String placeId, Boolean disclosed) throws Exception {
        Map<String, Object> body = experienceBody("EVERYONE", "EXACT");
        body.put("placeId", placeId);
        if (disclosed != null) body.put("disclosed", disclosed);
        return social.json(social.createPost(who, body).andExpect(status().isCreated())).get("publicId").asText();
    }

    private String visible(String postId) {
        LocalDateTime at = LocalDateTime.now(ZoneOffset.UTC).minus(Duration.ofMinutes(5));
        jdbc.update("UPDATE posts SET created_at = ?, visible_from = ? WHERE public_id = ?", at, at, postId);
        return postId;
    }

    /** [count] new people whose accounts are 10 days old: past the 72 h probation, not yet established. */
    private List<Person> people(String name, int count) throws Exception {
        List<Person> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Person person = social.person(name + i);
            jdbc.update("UPDATE users SET created_at = ? WHERE id = ?", LocalDateTime.now(ZoneOffset.UTC).minusDays(10), person.id());
            out.add(person);
        }
        return out;
    }

    private Boolean atPlace(String placePublicId, Person who) {
        return jdbc.queryForObject("SELECT v.at_place FROM place_visits v JOIN places p ON p.id = v.place_id"
                + " WHERE p.public_id = ? AND v.user_id = ?", Boolean.class, placePublicId, who.id());
    }

    private int rows(String placePublicId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM place_visits v JOIN places p ON p.id = v.place_id WHERE p.public_id = ?",
                Integer.class, placePublicId);
    }
}
