package com.safesphere.backend.social;

import static com.safesphere.backend.support.SocialClient.experienceBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.social.policy.EditionPolicy;
import com.safesphere.backend.social.policy.ProfileLists.Interest;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.user.repository.UserRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 13C: Discover through the real API (C5-C13). Each test uses its own launch city so tests in the shared test
 * database do not see each other's posts. Times are moved with SQL on the post rows (never with a clock, so tokens stay
 * valid).
 */
@SpringBootTest
@AutoConfigureMockMvc
class DiscoverApiIntegrationTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    private SocialClient social;
    private Person asha;      // writes
    private Person bala;      // connected to Asha
    private Person chitra;    // stranger

    @BeforeEach
    void setUp() throws Exception {
        social = new SocialClient(mockMvc, objectMapper, users);
        asha = social.person("Asha");
        bala = social.person("Bala");
        chitra = social.person("Chitra");
        social.connect(asha, bala);
    }

    @Test
    void edition_holdsOthersEveryonePostsOfTheCity_afterTheirDelay() throws Exception {
        String placeId = social.place(asha, "Sivaganga Park " + suffix(), thanjavurCell());
        String exact = post(asha, "EVERYONE", "EXACT", placeId, null);
        String area = post(asha, "EVERYONE", "AREA", placeId, null);
        String city = post(asha, "EVERYONE", "CITY", null, "THANJAVUR");
        String connectionsOnly = post(asha, "CONNECTIONS", "CITY", null, "THANJAVUR");
        String noPlace = post(asha, "EVERYONE", "NONE", null, null);
        String elsewhere = post(asha, "EVERYONE", "CITY", null, "MADURAI");

        List<String> seen = social.editionIds(social.edition(chitra, "thanjavur"));
        assertThat(seen).contains(area, city).doesNotContain(exact, connectionsOnly, noPlace, elsewhere);
        makeVisibleEarlier(exact, Duration.ofMinutes(5)); // the 1-3 h delay is over
        assertThat(social.editionIds(social.edition(chitra, "THANJAVUR"))).contains(exact, area, city);
        assertThat(social.editionIds(social.edition(bala, "THANJAVUR"))).contains(exact).doesNotContain(connectionsOnly);
        assertThat(social.editionIds(social.edition(asha, "THANJAVUR"))).doesNotContain(exact, area, city); // own

        JsonNode edition = social.json(social.edition(chitra, "THANJAVUR").andExpect(status().isOk()));
        assertThat(edition.get("city").asText()).isEqualTo("THANJAVUR");
        assertThat(edition.get("weekOf").asText()).isEqualTo(EditionPolicy.weekOf(Instant.now()).toString());
        assertThat(edition.get("theme").asText()).isEqualTo(EditionPolicy.theme(EditionPolicy.weekOf(Instant.now())).name());
        JsonNode first = edition.get("sections").get(0);
        assertThat(first.get("kind").asText()).isEqualTo("CITY");
        JsonNode exactItem = item(edition, exact);
        assertThat(texts(exactItem.get("reasons"))).contains("IN_THIS_CITY", "RECENT", "HAS_PLACE", "HAS_WHEN", "HAS_TIP");
        // No coordinates, no 7-character cell of an AREA post, no counts, no times for others.
        String text = edition.toString();
        assertThat(text).doesNotContain("latitude").doesNotContain("longitude").doesNotContain("\"count\"")
                .doesNotContain("postedAt\":\"");
        assertThat(item(edition, area).get("post").toString()).doesNotContain(thanjavurPlaceCell(placeId));
    }

    @Test
    void edition_cityMustBeALaunchCity() throws Exception {
        social.edition(chitra, null).andExpect(status().isBadRequest());
        social.edition(chitra, "OTHER").andExpect(status().isBadRequest());
        social.edition(chitra, "ATLANTIS").andExpect(status().isBadRequest());
    }

    @Test
    void edition_oldPostsLeave_theThemeKeepsItsCategoryLonger() throws Exception {
        Interest theme = EditionPolicy.theme(EditionPolicy.weekOf(Instant.now()));
        Map<String, Object> body = experienceBody("EVERYONE", "CITY");
        body.put("city", "SALEM");
        body.put("category", theme.name());
        String themed = created(asha, body);
        String plain = post(asha, "EVERYONE", "CITY", null, "SALEM");
        shiftBack(themed, Duration.ofDays(15));
        shiftBack(plain, Duration.ofDays(15));
        JsonNode edition = social.json(social.edition(chitra, "SALEM").andExpect(status().isOk()));
        assertThat(sectionIds(edition, "CITY")).doesNotContain(themed, plain);
        assertThat(sectionIds(edition, "THEME")).contains(themed).doesNotContain(plain);
        assertThat(texts(item(edition, themed).get("reasons"))).contains("THEME_OF_THE_WEEK");
        shiftBack(themed, Duration.ofDays(50)); // now 65 days: older than the theme window
        assertThat(social.editionIds(social.edition(chitra, "SALEM"))).doesNotContain(themed);
    }

    @Test
    void edition_openAsks_leaveOnceAnAnswerHelped() throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("type", "ASK", "body", "How do people get back at night?",
                "precision", "CITY", "city", "ERODE", "audience", "EVERYONE", "commentPolicy", "EVERYONE"));
        String ask = created(asha, body);
        JsonNode edition = social.json(social.edition(chitra, "ERODE").andExpect(status().isOk()));
        assertThat(sectionIds(edition, "ASKS")).contains(ask);
        assertThat(texts(item(edition, ask).get("reasons"))).contains("OPEN_ASK");
        String answer = social.commentId(bala, ask, "ANSWER", "Autos wait near the bus stand.", null);
        assertThat(social.editionIds(social.edition(chitra, "ERODE"))).contains(ask); // answered, not yet helpful
        social.commentAction(asha, answer, "helped").andExpect(status().isNoContent());
        assertThat(social.editionIds(social.edition(chitra, "ERODE"))).doesNotContain(ask);
    }

    @Test
    void mute_and_block_leaveTheLists_aDeliberateVisitStillWorks() throws Exception {
        String city = post(asha, "EVERYONE", "CITY", null, "VELLORE");
        assertThat(social.editionIds(social.edition(chitra, "VELLORE"))).contains(city);
        social.mute(chitra, asha).andExpect(status().isNoContent());
        assertThat(social.editionIds(social.edition(chitra, "VELLORE"))).doesNotContain(city);
        assertThat(social.postIds(social.categoryPage(chitra, "NATURE_OUTDOORS", "VELLORE", null))).doesNotContain(city);
        social.getPost(chitra, city).andExpect(status().isOk());                       // a link still opens
        assertThat(social.postIds(social.profilePosts(chitra, asha, null))).contains(city); // a profile visit too
        social.unmute(chitra, asha).andExpect(status().isNoContent());
        assertThat(social.editionIds(social.edition(chitra, "VELLORE"))).contains(city);

        social.block(asha, chitra).andExpect(status().isNoContent());
        assertThat(social.editionIds(social.edition(chitra, "VELLORE"))).doesNotContain(city);
        assertThat(social.postIds(social.categoryPage(chitra, "NATURE_OUTDOORS", "VELLORE", null))).doesNotContain(city);
    }

    @Test
    void fromConnections_newestFirst_audiencesAllowed_mutesOut_pagesEnd() throws Exception {
        String everyone = post(asha, "EVERYONE", "NONE", null, null);
        String connections = post(asha, "CONNECTIONS", "NONE", null, null);
        String onlyMe = post(asha, "ONLY_ME", "NONE", null, null);
        String strangers = post(chitra, "EVERYONE", "NONE", null, null);
        JsonNode page = social.json(social.fromConnections(bala, null).andExpect(status().isOk()));
        List<String> ids = new ArrayList<>();
        for (JsonNode item : page.get("items")) ids.add(item.get("post").get("publicId").asText());
        assertThat(ids).containsExactly(connections, everyone);
        assertThat(texts(page.get("items").get(0).get("reasons"))).containsExactly("FROM_A_CONNECTION");
        assertThat(page.get("nextCursor").isNull()).isTrue();
        assertThat(ids).doesNotContain(onlyMe, strangers);
        assertThat(social.discoverIds(social.fromConnections(chitra, null))).isEmpty(); // no connections

        shiftBack(everyone, Duration.ofDays(31)); // older than the 30-day window
        assertThat(social.discoverIds(social.fromConnections(bala, null))).containsExactly(connections);
        social.mute(bala, asha).andExpect(status().isNoContent());
        assertThat(social.discoverIds(social.fromConnections(bala, null))).isEmpty();
    }

    @Test
    void fromConnections_pagesOf20() throws Exception {
        Person dev = social.person("Dev");
        social.connect(dev, chitra);
        List<String> newestFirst = new ArrayList<>();
        for (int i = 0; i < 21; i++) newestFirst.add(0, post(dev, "CONNECTIONS", "NONE", null, null));
        // Distinct times so the order is certain.
        for (int i = 0; i < newestFirst.size(); i++) shiftBack(newestFirst.get(i), Duration.ofMinutes(i));
        JsonNode first = social.json(social.fromConnections(chitra, null).andExpect(status().isOk()));
        assertThat(first.get("items")).hasSize(20);
        String cursor = first.get("nextCursor").asText();
        assertThat(cursor).isEqualTo(newestFirst.get(19));
        assertThat(social.discoverIds(social.fromConnections(chitra, cursor))).containsExactly(newestFirst.get(20));
        social.fromConnections(chitra, "not-a-post").andExpect(status().isBadRequest());
    }

    @Test
    void placePage_onlyExactEveryonePosts() throws Exception {
        String placeId = social.place(asha, "Fort Garden " + suffix(), thanjavurCell());
        String exact = post(asha, "EVERYONE", "EXACT", placeId, null);
        String area = post(asha, "EVERYONE", "AREA", placeId, null);
        String exactConnections = post(asha, "CONNECTIONS", "EXACT", placeId, null);
        assertThat(social.postIds(social.placePage(bala, placeId, null))).isEmpty(); // the delay is not over
        makeVisibleEarlier(exact, Duration.ofMinutes(1));
        makeVisibleEarlier(exactConnections, Duration.ofMinutes(1));
        JsonNode page = social.json(social.placePage(chitra, placeId, null).andExpect(status().isOk()));
        assertThat(page.get("place").get("publicId").asText()).isEqualTo(placeId);
        assertThat(page.get("place").get("city").asText()).isEqualTo("THANJAVUR");
        List<String> ids = new ArrayList<>();
        for (JsonNode item : page.get("items")) ids.add(item.get("publicId").asText());
        assertThat(ids).containsExactly(exact);
        assertThat(social.postIds(social.placePage(bala, placeId, null))).containsExactly(exact)
                .doesNotContain(area, exactConnections);
        social.placePage(chitra, UUID.randomUUID().toString(), null).andExpect(status().isNotFound());
    }

    @Test
    void categoryPage_everyonePosts_ofOneCategory_optionallyOneCity() throws Exception {
        Map<String, Object> vellore = experienceBody("EVERYONE", "CITY");
        vellore.put("city", "TIRUNELVELI");
        vellore.put("category", "HIDDEN_GEMS");
        String inCity = created(asha, vellore);
        Map<String, Object> other = experienceBody("EVERYONE", "CITY");
        other.put("city", "PUDUCHERRY");
        other.put("category", "HIDDEN_GEMS");
        String elsewhere = created(asha, other);
        Map<String, Object> connectionsOnly = experienceBody("CONNECTIONS", "CITY");
        connectionsOnly.put("city", "TIRUNELVELI");
        connectionsOnly.put("category", "HIDDEN_GEMS");
        String hidden = created(asha, connectionsOnly);
        assertThat(social.postIds(social.categoryPage(bala, "HIDDEN_GEMS", "TIRUNELVELI", null)))
                .contains(inCity).doesNotContain(elsewhere, hidden);
        assertThat(social.postIds(social.categoryPage(bala, "hidden_gems", null, null))).contains(inCity, elsewhere)
                .doesNotContain(hidden);
        social.categoryPage(bala, "GOSSIP", null, null).andExpect(status().isBadRequest());
        social.categoryPage(bala, "HIDDEN_GEMS", "OTHER", null).andExpect(status().isBadRequest());
    }

    @Test
    void search_placesByName_only() throws Exception {
        String name = "Brihadeeswarar Temple " + suffix();
        String placeId = social.place(asha, name, thanjavurCell());
        assertThat(social.ids(social.searchPlaces(chitra, name.substring(0, 12).toLowerCase(), "THANJAVUR")))
                .contains(placeId);
        assertThat(social.ids(social.searchPlaces(chitra, name, null))).contains(placeId);
        assertThat(social.ids(social.searchPlaces(chitra, name, "CHENNAI"))).doesNotContain(placeId);
        assertThat(social.ids(social.searchPlaces(chitra, asha.handle(), null))).isEmpty(); // no people search
        social.searchPlaces(chitra, "a", null).andExpect(status().isBadRequest());
        social.searchPlaces(chitra, "!!", null).andExpect(status().isBadRequest());
        social.searchPlaces(chitra, "temple", "OTHER").andExpect(status().isBadRequest());
    }

    // ---- helpers ----

    private String post(Person who, String audience, String precision, String placeId, String city) throws Exception {
        Map<String, Object> body = experienceBody(audience, precision);
        if (placeId != null) body.put("placeId", placeId);
        if (city != null) body.put("city", city);
        return created(who, body);
    }

    private String created(Person who, Map<String, ?> body) throws Exception {
        return social.json(social.createPost(who, body).andExpect(status().isCreated())).get("publicId").asText();
    }

    /** The post became visible to others [ago] ago (ends the Q1 delay without moving any clock). */
    private void makeVisibleEarlier(String postId, Duration ago) {
        LocalDateTime at = LocalDateTime.now(ZoneOffset.UTC).minus(ago);
        jdbc.update("UPDATE posts SET created_at = ?, visible_from = ? WHERE public_id = ?", at, at, postId);
    }

    /** Moves the whole post [by] into the past. */
    private void shiftBack(String postId, Duration by) {
        LocalDateTime at = jdbc.queryForObject("SELECT visible_from FROM posts WHERE public_id = ?",
                LocalDateTime.class, postId).minus(by);
        jdbc.update("UPDATE posts SET created_at = ?, visible_from = ? WHERE public_id = ?", at, at, postId);
    }

    private String thanjavurPlaceCell(String placeId) {
        return jdbc.queryForObject("SELECT cell FROM places WHERE public_id = ?", String.class, placeId);
    }

    /** A random ~150 m cell inside the Thanjavur box. */
    private static String thanjavurCell() {
        return GeoCell.encode(10.74 + RANDOM.nextDouble() * 0.10, 79.07 + RANDOM.nextDouble() * 0.13, 7);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    private static JsonNode item(JsonNode edition, String postId) {
        for (JsonNode section : edition.get("sections")) {
            for (JsonNode item : section.get("items")) {
                if (item.get("post").get("publicId").asText().equals(postId)) return item;
            }
        }
        throw new AssertionError("not in the edition: " + postId);
    }

    private static List<String> sectionIds(JsonNode edition, String kind) {
        List<String> ids = new ArrayList<>();
        for (JsonNode section : edition.get("sections")) {
            if (!section.get("kind").asText().equals(kind)) continue;
            for (JsonNode item : section.get("items")) ids.add(item.get("post").get("publicId").asText());
        }
        return ids;
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode node : array) out.add(node.asText());
        return out;
    }
}
