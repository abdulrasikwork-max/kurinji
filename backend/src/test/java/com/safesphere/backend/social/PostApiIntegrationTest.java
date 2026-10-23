package com.safesphere.backend.social;

import static com.safesphere.backend.support.SocialClient.experienceBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.support.TestAccounts;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.time.YearMonth;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Phase 13B: posts through the real API: each kind, what each precision reveals, bands instead of timestamps, the Q1
 * delay, narrow-only edits, delete, cursor lists and limits.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class PostApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;

    private SocialClient social;
    private Person asha;
    private Person bala;      // connected to Asha
    private Person chitra;    // stranger

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        social = new SocialClient(mockMvc, objectMapper, users);
        asha = social.person("Asha");
        bala = social.person("Bala");
        chitra = social.person("Chitra");
        social.connect(asha, bala);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void eachPrecision_revealsOnlyWhatItSays() throws Exception {
        String cell = PlaceApiIntegrationTest.randomCell();
        String placeId = social.place(asha, "Elliot's Beach", cell);

        Map<String, Object> exact = experienceBody("EVERYONE", "EXACT");
        exact.put("placeId", placeId);
        String exactId = created(asha, exact);
        Map<String, Object> area = experienceBody("EVERYONE", "AREA");
        area.put("placeId", placeId);
        String areaId = created(asha, area);
        Map<String, Object> city = experienceBody("EVERYONE", "CITY");
        city.put("city", "chennai");
        String cityId = created(asha, city);
        String noneId = created(asha, experienceBody("EVERYONE", "NONE"));

        clock.advance(Duration.ofHours(3).plusSeconds(5)); // past the exact-place delay
        JsonNode e = social.json(social.getPost(chitra, exactId).andExpect(status().isOk()));
        assertThat(e.get("place").get("name").asText()).isEqualTo("Elliot's Beach");
        assertThat(e.get("areaCell").isNull()).isTrue();
        JsonNode a = social.json(social.getPost(chitra, areaId).andExpect(status().isOk()));
        assertThat(a.get("place").isNull()).isTrue();
        assertThat(a.get("areaCell").asText()).isEqualTo(cell.substring(0, 6)).hasSize(6);
        JsonNode c = social.json(social.getPost(chitra, cityId).andExpect(status().isOk()));
        assertThat(c.get("city").asText()).isEqualTo("CHENNAI");
        assertThat(c.get("place").isNull() && c.get("areaCell").isNull()).isTrue();
        JsonNode n = social.json(social.getPost(chitra, noneId).andExpect(status().isOk()));
        assertThat(n.get("place").isNull() && n.get("areaCell").isNull() && n.get("city").isNull()).isTrue();
        // No answer ever carries the 7-character cell or anything like a coordinate.
        for (JsonNode post : List.of(e, a, c, n)) {
            assertThat(post.toString()).doesNotContain(cell).doesNotContain("latitude").doesNotContain("longitude");
        }
    }

    @Test
    void othersGetBands_theAuthorGetsTimes() throws Exception {
        String id = social.experience(asha, "EVERYONE");
        JsonNode own = social.json(social.getPost(asha, id).andExpect(status().isOk()));
        assertThat(own.get("own").asBoolean()).isTrue();
        assertThat(own.get("postedAt").isNull()).isFalse();
        assertThat(own.get("audience").asText()).isEqualTo("EVERYONE");
        JsonNode other = social.json(social.getPost(chitra, id).andExpect(status().isOk()));
        assertThat(other.get("own").asBoolean()).isFalse();
        assertThat(other.get("postedAt").isNull()).isTrue();
        assertThat(other.get("visibleFrom").isNull()).isTrue();
        assertThat(other.get("audience").isNull()).isTrue();
        assertThat(other.get("postedPeriod").asText()).isEqualTo("THIS_WEEK");
        assertThat(other.get("experiencedMonth").asText()).matches("\\d{4}-\\d{2}");
        assertThat(other.get("author").get("handle").asText()).isEqualTo(asha.handle());
        clock.advance(Duration.ofDays(8));
        social.getPost(chitra, id).andExpect(jsonPath("$.postedPeriod").value("THIS_MONTH"));
        clock.advance(Duration.ofDays(30));
        social.getPost(chitra, id).andExpect(jsonPath("$.postedPeriod").value("EARLIER"));
    }

    @Test
    void exactPlacePosts_appearToOthersOnlyAfterTheDelay() throws Exception {
        String placeId = social.place(asha, "Kapaleeshwarar Temple", PlaceApiIntegrationTest.randomCell());
        Map<String, Object> body = experienceBody("CONNECTIONS", "EXACT");
        body.put("placeId", placeId);
        String id = created(asha, body);
        JsonNode own = social.json(social.getPost(asha, id).andExpect(status().isOk()));
        Duration delay = Duration.between(java.time.Instant.parse(own.get("postedAt").asText()),
                java.time.Instant.parse(own.get("visibleFrom").asText()));
        assertThat(delay).isBetween(Duration.ofHours(1), Duration.ofHours(3));
        social.getPost(bala, id).andExpect(status().isNotFound());             // even a connection waits
        assertThat(social.postIds(social.profilePosts(bala, asha, null))).doesNotContain(id);
        assertThat(social.postIds(social.minePosts(asha, null))).contains(id);
        clock.advance(delay.plusSeconds(1));
        social.getPost(bala, id).andExpect(status().isOk());
        assertThat(social.postIds(social.profilePosts(bala, asha, null))).contains(id);
        social.getPost(chitra, id).andExpect(status().isNotFound());           // CONNECTIONS only
        // Area / city / no place: no delay.
        Map<String, Object> area = experienceBody("EVERYONE", "AREA");
        area.put("placeId", placeId);
        social.getPost(chitra, created(asha, area)).andExpect(status().isOk());
    }

    @Test
    void asks_andValidation() throws Exception {
        Map<String, Object> ask = new HashMap<>(Map.of("type", "ASK", "body", "Anyone been to Besant Nagar at night?",
                "precision", "CITY", "city", "CHENNAI", "daypart", "NIGHT", "audience", "EVERYONE",
                "commentPolicy", "EVERYONE"));
        JsonNode created = social.json(social.createPost(asha, ask).andExpect(status().isCreated()));
        assertThat(created.get("type").asText()).isEqualTo("ASK");
        assertThat(created.get("experiencedMonth").isNull()).isTrue();

        Map<String, Object> take = new HashMap<>(ask);
        take.put("type", "TAKE");
        social.createPost(asha, take).andExpect(status().isBadRequest());
        Map<String, Object> noAudience = experienceBody("EVERYONE", "NONE");
        noAudience.remove("audience");
        social.createPost(asha, noAudience).andExpect(status().isBadRequest());
        Map<String, Object> exactWithoutPlace = experienceBody("EVERYONE", "EXACT");
        social.createPost(asha, exactWithoutPlace).andExpect(status().isBadRequest());
        Map<String, Object> unknownPlace = experienceBody("EVERYONE", "EXACT");
        unknownPlace.put("placeId", UUID.randomUUID().toString());
        social.createPost(asha, unknownPlace).andExpect(status().isNotFound());
        Map<String, Object> future = experienceBody("EVERYONE", "NONE");
        future.put("experiencedMonth", YearMonth.now(ZoneOffset.UTC).plusMonths(2).toString());
        social.createPost(asha, future).andExpect(status().isBadRequest());
        Map<String, Object> badCity = experienceBody("EVERYONE", "CITY");
        badCity.put("city", "Madras");
        social.createPost(asha, badCity).andExpect(status().isBadRequest());
        // Only me never takes perspectives, whatever is asked.
        Map<String, Object> note = experienceBody("ONLY_ME", "NONE");
        note.put("commentPolicy", "EVERYONE");
        social.createPost(asha, note).andExpect(status().isCreated())
                .andExpect(jsonPath("$.commentPolicy").value("NOBODY")).andExpect(jsonPath("$.canComment").value(false));
    }

    @Test
    void aProfileIsNeeded_toPost() throws Exception {
        String token = social.accountWithoutProfile();
        mockMvc.perform(MockMvcRequestBuilders.post("/api/posts").header(HttpHeaders.AUTHORIZATION, TestAccounts.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(experienceBody("EVERYONE", "NONE"))))
                .andExpect(status().isConflict());
    }

    @Test
    void edits_mayOnlyNarrow_andAreMarked() throws Exception {
        String placeId = social.place(asha, "Semmozhi Poonga", PlaceApiIntegrationTest.randomCell());
        Map<String, Object> body = experienceBody("EVERYONE", "AREA");
        body.put("placeId", placeId);
        String id = created(asha, body);
        Map<String, Object> edit = new HashMap<>(body);
        edit.remove("type");
        edit.remove("placeId");
        edit.put("body", "Edited text.");
        edit.put("audience", "CONNECTIONS");
        social.editPost(asha, id, edit).andExpect(status().isOk()).andExpect(jsonPath("$.edited").value(true))
                .andExpect(jsonPath("$.body").value("Edited text."));
        social.getPost(chitra, id).andExpect(status().isNotFound());
        social.getPost(bala, id).andExpect(status().isOk());

        edit.put("audience", "EVERYONE");
        social.editPost(asha, id, edit).andExpect(status().isConflict());     // widening
        edit.put("audience", "CONNECTIONS");
        edit.put("precision", "EXACT");
        social.editPost(asha, id, edit).andExpect(status().isConflict());     // widening the place
        edit.put("precision", "CITY");
        edit.put("city", "CHENNAI");
        social.editPost(asha, id, edit).andExpect(status().isOk()).andExpect(jsonPath("$.city").value("CHENNAI"))
                .andExpect(jsonPath("$.areaCell").isEmpty());
        edit.put("city", "MADURAI");
        social.editPost(asha, id, edit).andExpect(status().isConflict());     // moving the city sideways
        edit.put("precision", "NONE");
        edit.remove("city");
        social.editPost(asha, id, edit).andExpect(status().isOk()).andExpect(jsonPath("$.precision").value("NONE"));
        social.editPost(bala, id, edit).andExpect(status().isNotFound());     // only the author
    }

    @Test
    void delete_isTheAuthorsOnly() throws Exception {
        String id = social.experience(asha, "EVERYONE");
        social.deletePost(chitra, id).andExpect(status().isNotFound());
        social.deletePost(asha, id).andExpect(status().isNoContent());
        social.getPost(asha, id).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM posts WHERE public_id = ?", Long.class, id)).isZero();
    }

    @Test
    void lists_pageByExplicitCursor_andShowOnlyWhatTheViewerMaySee() throws Exception {
        List<String> everyone = new ArrayList<>();
        for (int i = 0; i < 21; i++) everyone.add(social.experience(asha, "EVERYONE"));
        String forConnections = social.experience(asha, "CONNECTIONS");
        String onlyMe = social.experience(asha, "ONLY_ME");

        JsonNode first = social.json(social.minePosts(asha, null).andExpect(status().isOk()));
        assertThat(first.get("items")).hasSize(20);
        assertThat(first.get("items").get(0).get("publicId").asText()).isEqualTo(onlyMe);   // newest first
        String cursor = first.get("nextCursor").asText();
        JsonNode second = social.json(social.minePosts(asha, cursor).andExpect(status().isOk()));
        assertThat(second.get("items")).hasSize(3);
        assertThat(second.get("nextCursor").isNull()).isTrue();

        List<String> strangerSees = allPages(chitra, asha);
        assertThat(strangerSees).containsExactlyInAnyOrderElementsOf(everyone);
        List<String> connectionSees = allPages(bala, asha);
        assertThat(connectionSees).contains(forConnections).doesNotContain(onlyMe).hasSize(22);
        // A cursor naming a post the viewer cannot see is refused.
        social.profilePosts(chitra, asha, onlyMe).andExpect(status().isBadRequest());
    }

    @Test
    void posting_isLimitedPerDay() throws Exception {
        Person busy = social.person("Busy");
        for (int i = 0; i < 25; i++) social.experience(busy, "ONLY_ME");
        social.createPost(busy, experienceBody("ONLY_ME", "NONE")).andExpect(status().isTooManyRequests());
    }

    private List<String> allPages(Person viewer, Person owner) throws Exception {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = social.json(social.profilePosts(viewer, owner, cursor).andExpect(status().isOk()));
            for (JsonNode item : page.get("items")) ids.add(item.get("publicId").asText());
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
        } while (cursor != null);
        return ids;
    }

    private String created(Person who, Map<String, Object> body) throws Exception {
        return social.json(social.createPost(who, body).andExpect(status().isCreated())).get("publicId").asText();
    }
}
