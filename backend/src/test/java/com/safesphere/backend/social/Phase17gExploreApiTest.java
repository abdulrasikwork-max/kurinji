package com.safesphere.backend.social;

import static com.safesphere.backend.support.SocialClient.experienceBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.community.service.GeoCell;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 17G-1: Explore tags on experiences and the Explore search through the real API, from the points of view of a
 * stranger, a connection, the author, a blocked and a muted person. Each test uses its own catalog city so the shared test
 * database does not mix them. The Q1 delay is ended with SQL on the post rows (never with a clock).
 */
@SpringBootTest
@AutoConfigureMockMvc
class Phase17gExploreApiTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    private SocialClient social;
    private Person asha;     // writes
    private Person bala;     // connected to Asha
    private Person chitra;   // a stranger
    private Person dev;      // blocked by Asha
    private Person elan;     // writes too

    @BeforeEach
    void setUp() throws Exception {
        social = new SocialClient(mockMvc, objectMapper, users);
        asha = social.person("Asha");
        bala = social.person("Bala");
        chitra = social.person("Chitra");
        dev = social.person("Dev");
        elan = social.person("Elan");
        social.connect(asha, bala);
        social.block(asha, dev).andExpect(status().isNoContent());
    }

    // ---- tags on posts ----

    @Test
    void tags_areSavedInTheTagListOrder_everyoneWhoMaySeeThePostSeesThem() throws Exception {
        Map<String, Object> body = experienceBody("EVERYONE", "NONE");
        body.put("facets", List.of("scenic", " QUIET "));
        JsonNode created = social.json(social.createPost(asha, body).andExpect(status().isCreated()));
        assertThat(texts(created.get("facets"))).containsExactly("QUIET", "SCENIC"); // the file's order, clean codes
        String id = created.get("publicId").asText();
        social.getPost(chitra, id).andExpect(jsonPath("$.facets[0]").value("QUIET")).andExpect(jsonPath("$.facets[1]").value("SCENIC"));
        // A post with no tags answers an empty list, never null.
        String plain = social.experience(asha, "EVERYONE");
        social.getPost(chitra, plain).andExpect(jsonPath("$.facets").isEmpty());
    }

    @Test
    void tags_areRefusedOnAsks_andWhenInvalid() throws Exception {
        Map<String, Object> ask = new HashMap<>(experienceBody("EVERYONE", "NONE"));
        ask.put("type", "ASK");
        ask.put("body", "Where can I find a quiet place to study near the bus stand?");
        ask.remove("tip");
        ask.remove("experiencedMonth");
        ask.remove("daypart");
        social.createPost(asha, ask).andExpect(status().isCreated()); // the same Ask without tags is fine
        ask.put("facets", List.of("QUIET"));
        social.createPost(asha, ask).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Tags are for experiences, not questions"));

        Map<String, Object> body = experienceBody("EVERYONE", "NONE");
        body.put("facets", List.of("QUIET", "QUIET"));
        social.createPost(asha, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Choose each tag only once"));
        body.put("facets", List.of("NOT_A_TAG"));
        social.createPost(asha, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Choose tags from the list"));
        body.put("facets", List.of("QUIET", "LIVELY", "SCENIC", "OPEN_LATE", "GOOD_ALONE"));
        social.createPost(asha, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Choose at most 4 tags"));
        List<String> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) nine.add("QUIET");
        body.put("facets", nine);
        social.createPost(asha, body).andExpect(status().isBadRequest());
        body.put("facets", java.util.Arrays.asList("QUIET", null));
        social.createPost(asha, body).andExpect(status().isBadRequest());
        // Four is the most; none of the refused requests created anything.
        body.put("facets", List.of("QUIET", "LIVELY", "SCENIC", "OPEN_LATE"));
        social.createPost(asha, body).andExpect(status().isCreated());
    }

    @Test
    void edit_nullKeepsTheTags_emptyRemovesThem_aListReplacesThem_invalidChangesNothing() throws Exception {
        Map<String, Object> body = experienceBody("EVERYONE", "NONE");
        body.put("facets", List.of("QUIET", "SCENIC"));
        String id = social.json(social.createPost(asha, body).andExpect(status().isCreated())).get("publicId").asText();

        Map<String, Object> edit = new HashMap<>(body);
        edit.remove("type");
        edit.remove("facets");
        edit.put("body", "Edited text, the tags are not mentioned.");
        JsonNode kept = social.json(social.editPost(asha, id, edit).andExpect(status().isOk()));
        assertThat(texts(kept.get("facets"))).containsExactly("QUIET", "SCENIC");

        edit.put("facets", List.of("open_late"));
        JsonNode replaced = social.json(social.editPost(asha, id, edit).andExpect(status().isOk()));
        assertThat(texts(replaced.get("facets"))).containsExactly("OPEN_LATE");

        edit.put("facets", List.of("NOT_A_TAG"));
        social.editPost(asha, id, edit).andExpect(status().isBadRequest());
        assertThat(texts(social.json(social.getPost(chitra, id).andExpect(status().isOk())).get("facets"))).containsExactly("OPEN_LATE");

        edit.put("facets", List.of());
        JsonNode cleared = social.json(social.editPost(asha, id, edit).andExpect(status().isOk()));
        assertThat(cleared.get("facets")).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM post_facets pf JOIN posts p ON p.id = pf.post_id WHERE p.public_id = ?", id)).isZero();
    }

    @Test
    void deletingAPost_removesItsTags() throws Exception {
        Map<String, Object> body = experienceBody("EVERYONE", "NONE");
        body.put("facets", List.of("QUIET", "SCENIC", "OPEN_LATE"));
        String id = social.json(social.createPost(asha, body).andExpect(status().isCreated())).get("publicId").asText();
        long postId = jdbc.queryForObject("SELECT id FROM posts WHERE public_id = ?", Long.class, id);
        assertThat(count("SELECT COUNT(*) FROM post_facets WHERE post_id = ?", postId)).isEqualTo(3);
        social.deletePost(asha, id).andExpect(status().isNoContent());
        assertThat(count("SELECT COUNT(*) FROM post_facets WHERE post_id = ?", postId)).isZero();
    }

    // ---- the search: who sees what ----

    @Test
    void explore_findsPlacesFromExperiencesTheViewerMayRead_andNothingElse() throws Exception {
        String city = "TIRUPPUR";
        String lake = place(asha, "Lake View", "NATURE_OUTDOORS", tiruppurCell());
        String cafe = place(asha, "Corner Cafe", "FOOD_CAFES", tiruppurCell());
        String friendsOnly = place(asha, "Friends Only Spot", "NATURE_OUTDOORS", tiruppurCell());
        String areaOnly = place(asha, "Area Only Spot", "NATURE_OUTDOORS", tiruppurCell());
        String onlyMe = place(asha, "Only Me Spot", "NATURE_OUTDOORS", tiruppurCell());
        // (A person may add 10 places a day, so Elan adds the rest; who added a place is never stored anyway.)
        String held = place(elan, "Held Spot", "NATURE_OUTDOORS", tiruppurCell());
        String collapsed = place(elan, "Collapsed Spot", "NATURE_OUTDOORS", tiruppurCell());
        String delayed = place(elan, "Delayed Spot", "NATURE_OUTDOORS", tiruppurCell());
        String mixed = place(elan, "Mixed Spot", "NATURE_OUTDOORS", tiruppurCell());
        String merged = place(elan, "Merged Spot", "NATURE_OUTDOORS", tiruppurCell());
        String elsewhere = place(elan, "Elsewhere Spot", "NATURE_OUTDOORS", thanjavurCell());

        visible(exact(asha, "EVERYONE", lake, List.of("QUIET", "SCENIC")));
        visible(exact(asha, "EVERYONE", cafe, List.of("LIVELY")));
        visible(exact(asha, "CONNECTIONS", friendsOnly, List.of("QUIET")));
        visible(exact(asha, "EVERYONE", areaOnly, List.of("QUIET"), "AREA"));
        visible(exact(asha, "ONLY_ME", onlyMe, List.of("QUIET")));
        setModeration(visible(exact(asha, "EVERYONE", held, List.of("QUIET"))), "HELD");
        setModeration(visible(exact(asha, "EVERYONE", collapsed, List.of("QUIET"))), "COLLAPSED");
        exact(asha, "EVERYONE", delayed, List.of("QUIET"));                       // still inside its 1-3 h delay
        visible(exact(elan, "EVERYONE", mixed, List.of("LIVELY")));                // a readable post without the tag ...
        setModeration(visible(exact(asha, "EVERYONE", mixed, List.of("QUIET"))), "HELD"); // ... and a held one with it
        visible(exact(asha, "EVERYONE", merged, List.of("QUIET")));
        jdbc.update("UPDATE places SET merged_into = (SELECT id FROM (SELECT id FROM places WHERE public_id = ?) x) WHERE public_id = ?",
                lake, merged);
        visible(exact(asha, "EVERYONE", elsewhere, List.of("QUIET")));

        Map<String, Object> quiet = Map.of("city", city, "facets", List.of("QUIET"));
        List<String> forStranger = social.explorePlaceIds(social.explore(chitra, quiet));
        assertThat(forStranger).containsExactly(lake);
        // The same for a connection: connections-only posts are not searched in v1, and nothing hidden counts.
        assertThat(social.explorePlaceIds(social.explore(bala, quiet))).containsExactly(lake);
        // Never your own posts (the same rule as Discover's Edition).
        assertThat(social.explorePlaceIds(social.explore(asha, quiet))).doesNotContain(lake);
        // Blocked either way: Dev cannot find Asha's places.
        assertThat(social.explorePlaceIds(social.explore(dev, quiet))).doesNotContain(lake);
        // Muted: Bala muted Asha, so Asha's places are not shown to Bala.
        social.mute(bala, asha).andExpect(status().isNoContent());
        assertThat(social.explorePlaceIds(social.explore(bala, quiet))).doesNotContain(lake);
        social.unmute(bala, asha).andExpect(status().isNoContent());
        assertThat(social.explorePlaceIds(social.explore(bala, quiet))).contains(lake);
        // The other city sees only its own places.
        assertThat(social.explorePlaceIds(social.explore(chitra, Map.of("city", "THANJAVUR", "facets", List.of("QUIET")))))
                .contains(elsewhere).doesNotContain(lake);
        // Once the delay is over the place appears.
        visible(postIdOf(delayed));
        assertThat(social.explorePlaceIds(social.explore(chitra, quiet))).containsExactlyInAnyOrder(lake, delayed);
        // A category request matches the place's own category (the cafe is not a nature place).
        List<String> nature = social.explorePlaceIds(social.explore(chitra, Map.of("city", city, "categories", List.of("NATURE_OUTDOORS"))));
        // "Mixed" has one readable experience (Elan's), so as a nature place it is found by category; what its HELD post
        // said (the QUIET tag) was never read, as the tag search above showed.
        assertThat(nature).contains(lake, mixed).doesNotContain(cafe, friendsOnly, areaOnly, onlyMe, held, collapsed, merged);
        assertThat(social.explorePlaceIds(social.explore(chitra, Map.of("city", city, "categories", List.of("FOOD_CAFES"))))).contains(cafe);
    }

    @Test
    void explore_ordersByWhatMatched_thenByNewest_andPopularityNeverLiftsAPlace() throws Exception {
        String city = "DINDIGUL";
        String both = place(asha, "Both Tags", "NATURE_OUTDOORS", dindigulCell());
        String newest = place(asha, "Newest One Tag", "NATURE_OUTDOORS", dindigulCell());
        String busy = place(asha, "Busy One Tag", "NATURE_OUTDOORS", dindigulCell());
        String oldest = place(asha, "Oldest One Tag", "NATURE_OUTDOORS", dindigulCell());

        visibleAgo(exact(asha, "EVERYONE", both, List.of("QUIET", "SCENIC")), Duration.ofHours(30));
        visibleAgo(exact(asha, "EVERYONE", newest, List.of("QUIET")), Duration.ofHours(1));
        visibleAgo(exact(asha, "EVERYONE", oldest, List.of("SCENIC")), Duration.ofHours(10));
        // "Busy" has many experiences, all older than the newest one tag, from several people: it must not outrank it.
        for (Person author : List.of(asha, elan, bala, asha, elan, bala)) {
            visibleAgo(exact(author, "EVERYONE", busy, List.of("QUIET", "LIVELY")), Duration.ofHours(5));
        }
        Map<String, Object> ask = Map.of("city", city, "facets", List.of("QUIET", "SCENIC"));
        // "Both" matched 2 of 2 and comes first although its experience is the oldest; the others matched 1 of 2 and go
        // by the newest readable experience: newest (1 h), busy (5 h), oldest (10 h).
        List<String> order = social.explorePlaceIds(social.explore(chitra, ask));
        assertThat(order).containsExactly(both, newest, busy, oldest);
        JsonNode first = social.json(social.explore(chitra, ask).andExpect(status().isOk())).get("hits").get(0);
        assertThat(first.get("requested").asInt()).isEqualTo(2);
        assertThat(first.get("matched").asInt()).isEqualTo(2);
        assertThat(texts(first.get("matchedFacets"))).containsExactly("QUIET", "SCENIC");
        // Even more experiences at the oldest place change nothing about where it stands.
        for (Person author : List.of(asha, elan, bala, asha, elan, bala, asha, elan)) {
            visibleAgo(exact(author, "EVERYONE", oldest, List.of("SCENIC")), Duration.ofHours(40));
        }
        assertThat(social.explorePlaceIds(social.explore(chitra, ask))).containsExactly(both, newest, busy, oldest);
    }

    @Test
    void explore_theAnswerCarriesNoCoordinatesNoTimesNoCountsNoAuthors() throws Exception {
        String place = place(asha, "Shape Spot", "FOOD_CAFES", nagercoilCell());
        Map<String, Object> body = experienceBody("EVERYONE", "EXACT");
        body.put("placeId", place);
        body.put("facets", List.of("QUIET"));
        body.put("daypart", "EVENING");
        visible(social.json(social.createPost(asha, body).andExpect(status().isCreated())).get("publicId").asText());

        JsonNode answer = social.json(social.explore(chitra, Map.of("city", "NAGERCOIL", "categories", List.of("FOOD_CAFES"),
                "facets", List.of("QUIET"), "daypart", "EVENING")).andExpect(status().isOk()));
        assertThat(fieldNames(answer)).containsExactlyInAnyOrder("city", "requested", "hits");
        assertThat(answer.get("city").asText()).isEqualTo("NAGERCOIL");
        assertThat(answer.get("requested").asInt()).isEqualTo(3);
        JsonNode hit = answer.get("hits").get(0);
        assertThat(fieldNames(hit)).containsExactlyInAnyOrder("place", "matchedCategory", "matchedFacets", "matchedDaypart",
                "requested", "matched");
        assertThat(fieldNames(hit.get("place"))).containsExactlyInAnyOrder("publicId", "name", "category", "city");
        assertThat(hit.get("matched").asInt()).isEqualTo(3);
        assertThat(hit.get("matchedCategory").asBoolean()).isTrue();
        assertThat(hit.get("matchedDaypart").asBoolean()).isTrue();
        String text = answer.toString().toLowerCase();
        for (String forbidden : List.of("latitude", "longitude", "cell", "postedat", "visiblefrom", "author", "handle", "count",
                "butterfl", "rating", "body", "tip")) {
            assertThat(text).as("the answer must not carry " + forbidden).doesNotContain(forbidden);
        }
    }

    @Test
    void explore_aPlaceMatchedByNothingIsNotListed_andAnEmptyCityAnswersAnEmptyList() throws Exception {
        social.explore(chitra, Map.of("city", "KUMBAKONAM", "facets", List.of("QUIET"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.hits").isEmpty()).andExpect(jsonPath("$.requested").value(1));
    }

    // ---- the search: what is refused ----

    @Test
    void explore_refusesAnythingOutsideTheClosedLists() throws Exception {
        social.explore(chitra, Map.of("city", "TIRUPPUR")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Choose what you are looking for"));
        social.explore(chitra, Map.of("facets", List.of("QUIET"))).andExpect(status().isBadRequest());
        social.explore(chitra, Map.of("city", "OTHER", "facets", List.of("QUIET"))).andExpect(status().isBadRequest());
        social.explore(chitra, Map.of("city", "ATLANTIS", "facets", List.of("QUIET"))).andExpect(status().isBadRequest());
        social.explore(chitra, Map.of("city", "TIRUPPUR", "facets", List.of("NOPE"))).andExpect(status().isBadRequest());
        social.explore(chitra, Map.of("city", "TIRUPPUR", "facets", List.of("QUIET", "QUIET"))).andExpect(status().isBadRequest());
        social.explore(chitra, Map.of("city", "TIRUPPUR", "facets", List.of("QUIET", "LIVELY", "SCENIC", "OPEN_LATE", "GOOD_ALONE")))
                .andExpect(status().isBadRequest());
        social.explore(chitra, Map.of("city", "TIRUPPUR", "categories", List.of("NOPE"))).andExpect(status().isBadRequest());
        social.explore(chitra, Map.of("city", "TIRUPPUR", "categories", List.of("FOOD_CAFES", "NATURE_OUTDOORS", "CITY_LIFE")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Choose at most 2 categories"));
        social.explore(chitra, Map.of("city", "TIRUPPUR", "daypart", "TEATIME")).andExpect(status().isBadRequest());
        // Two categories are fine (alternatives).
        social.explore(chitra, Map.of("city", "TIRUPPUR", "categories", List.of("FOOD_CAFES", "NATURE_OUTDOORS"))).andExpect(status().isOk());
        // No sign-in, no search.
        mockMvc.perform(post("/api/explore/search").contentType(MediaType.APPLICATION_JSON)
                .content("{\"city\":\"TIRUPPUR\",\"facets\":[\"QUIET\"]}")).andExpect(status().isUnauthorized());
    }

    @Test
    void explore_isLimitedPerPersonPerHour() throws Exception {
        Person eager = social.person("Eager");
        Map<String, Object> ask = Map.of("city", "NAGERCOIL", "facets", List.of("GOOD_FOR_WORK"));
        for (int i = 0; i < 120; i++) social.explore(eager, ask).andExpect(status().isOk());
        social.explore(eager, ask).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("You have searched a lot this hour. Please try again later."));
        social.explore(chitra, ask).andExpect(status().isOk()); // the limit is per person
    }

    // ---- helpers ----

    private String place(Person who, String name, String category, String cell) throws Exception {
        return social.json(social.createPlace(who, name + " " + suffix(), category, cell).andExpect(status().isOk()))
                .get("publicId").asText();
    }

    /** An EXACT experience at [placeId] with [facets]; returns its public id. */
    private String exact(Person who, String audience, String placeId, List<String> facets) throws Exception {
        return exact(who, audience, placeId, facets, "EXACT");
    }

    private String exact(Person who, String audience, String placeId, List<String> facets, String precision) throws Exception {
        Map<String, Object> body = experienceBody(audience, precision);
        body.put("placeId", placeId);
        body.put("facets", facets);
        return social.json(social.createPost(who, body).andExpect(status().isCreated())).get("publicId").asText();
    }

    private String postIdOf(String placeId) {
        return jdbc.queryForObject("SELECT p.public_id FROM posts p JOIN places pl ON pl.id = p.place_id WHERE pl.public_id = ?"
                + " ORDER BY p.id DESC LIMIT 1", String.class, placeId);
    }

    /** The post became visible to others five minutes ago (the 1-3 h delay is over). */
    private String visible(String postId) {
        return visibleAgo(postId, Duration.ofMinutes(5));
    }

    private String visibleAgo(String postId, Duration ago) {
        LocalDateTime at = LocalDateTime.now(ZoneOffset.UTC).minus(ago);
        jdbc.update("UPDATE posts SET created_at = ?, visible_from = ? WHERE public_id = ?", at, at, postId);
        return postId;
    }

    private void setModeration(String postId, String state) {
        jdbc.update("UPDATE posts SET moderation = ? WHERE public_id = ?", state, postId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode node : array) out.add(node.asText());
        return out;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    /** A random ~150 m cell inside the Tiruppur box (11.05-11.17 N, 77.28-77.40 E). */
    private static String tiruppurCell() {
        return GeoCell.encode(11.06 + RANDOM.nextDouble() * 0.10, 77.29 + RANDOM.nextDouble() * 0.10, 7);
    }

    /** ... the Dindigul box (10.32-10.40 N, 77.93-78.02 E). */
    private static String dindigulCell() {
        return GeoCell.encode(10.33 + RANDOM.nextDouble() * 0.06, 77.94 + RANDOM.nextDouble() * 0.07, 7);
    }

    /** ... the Nagercoil box (8.12-8.24 N, 77.36-77.47 E). */
    private static String nagercoilCell() {
        return GeoCell.encode(8.13 + RANDOM.nextDouble() * 0.10, 77.37 + RANDOM.nextDouble() * 0.09, 7);
    }

    /** ... the Thanjavur box (10.74-10.84 N, 79.07-79.20 E), another city for the "other city" check. */
    private static String thanjavurCell() {
        return GeoCell.encode(10.75 + RANDOM.nextDouble() * 0.08, 79.08 + RANDOM.nextDouble() * 0.11, 7);
    }
}
