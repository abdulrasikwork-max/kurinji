package com.safesphere.backend.social;

import static com.safesphere.backend.support.TestAccounts.bearer;
import static com.safesphere.backend.support.TestAccounts.registerAndGetToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.social.entity.Block;
import com.safesphere.backend.social.repository.BlockRepository;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Phase 13A through the real API: creating and editing a profile, handle rules (uniqueness, reserved words, 30-day
 * change limit, reservation of a handle someone left), text cleaning, exact look-ups through the one visibility rule
 * (blocked either way, disabled accounts, unknown handles all answer the same 404), the look-up limit, and no counts.
 */
@SpringBootTest(properties = "app.social.lookups-per-hour=15")
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class ProfileApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;
    @Autowired private UserRepository users;
    @Autowired private BlockRepository blocks;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void resetClock() {
        clock.reset();
    }

    // ---- own profile ----------------------------------------------------------------------------------------------

    @Test
    void create_normalisesTheHandle_cleansText_andOrdersTheLists() throws Exception {
        Person asha = person();
        String handle = unique("asha");
        JsonNode body = json(save(asha, profile("@" + handle.toUpperCase(), "  Asha​ Devi ",
                "Quiet cafés,  long walks", List.of("en", "ta", "en"), List.of("HIDDEN_GEMS", "food_cafes"), " Chennai "))
                .andExpect(status().isOk()));
        assertThat(body.get("handle").asText()).isEqualTo(handle);
        assertThat(body.get("displayName").asText()).isEqualTo("Asha Devi");
        assertThat(body.get("bio").asText()).isEqualTo("Quiet cafés, long walks");
        assertThat(body.get("city").asText()).isEqualTo("Chennai");
        assertThat(body.get("languages").toString()).isEqualTo("[\"ta\",\"en\"]");
        assertThat(body.get("interests").toString()).isEqualTo("[\"FOOD_CAFES\",\"HIDDEN_GEMS\"]");
        assertThat(body.get("handleChangeAvailableAt").isNull()).isTrue();

        own(asha).andExpect(status().isOk()).andExpect(jsonPath("$.handle").value(handle));
    }

    @Test
    void noProfileYet_is404_andAnUnconfirmedAgeIsRefused() throws Exception {
        Person asha = person();
        own(asha).andExpect(status().isNotFound());
        jdbc.update("UPDATE users SET age_confirmed_at = NULL WHERE id = ?", asha.id());
        save(asha, profile(unique("asha"), "Asha")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Confirm your age to use this"))
                // Phase 17A: the code lets the app open its one age screen.
                .andExpect(jsonPath("$.code").value("AGE_NOT_CONFIRMED"));
    }

    @Test
    void handleRules_format_reservedWords_andUniquenessIgnoringCase() throws Exception {
        Person asha = person();
        Person bala = person();
        save(asha, profile("1asha", "Asha")).andExpect(status().isBadRequest());
        save(asha, profile("as..ha", "Asha")).andExpect(status().isBadRequest());
        // Digits only around the reserved word (hex letters would make a different word).
        save(asha, profile("admin_" + (100000 + new java.util.Random().nextInt(900000)), "Asha"))
                .andExpect(status().isConflict());
        String handle = unique("asha");
        save(asha, profile(handle, "Asha")).andExpect(status().isOk());
        save(bala, profile(handle.toUpperCase(), "Bala")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("That handle is not available"));
    }

    @Test
    void handleChange_onceIn30Days_andTheOldHandleStaysReservedFor30Days() throws Exception {
        Person asha = person();
        Person bala = person();
        String first = unique("asha");
        String second = unique("asha");
        save(asha, profile(first, "Asha")).andExpect(status().isOk());

        JsonNode changed = json(save(asha, profile(second, "Asha")).andExpect(status().isOk()));
        assertThat(changed.get("handleChangeAvailableAt").isNull()).isFalse();
        save(asha, profile(unique("asha"), "Asha")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You can change your handle once every 30 days"));
        // Editing everything else is still fine.
        save(asha, profile(second, "Asha D")).andExpect(status().isOk());

        // The handle Asha left is reserved for her.
        save(bala, profile(first, "Bala")).andExpect(status().isConflict());

        clock.advance(Duration.ofDays(31));
        save(bala, profile(first, "Bala")).andExpect(status().isOk());
        save(asha, profile(unique("asha"), "Asha")).andExpect(status().isOk());
    }

    @Test
    void text_linksBlankNamesAndUnknownCodes_areRefused_tamilTextIsKept() throws Exception {
        Person asha = person();
        String handle = unique("asha");
        save(asha, profile(handle, "   ")).andExpect(status().isBadRequest());
        save(asha, profile(handle, "Asha", "visit https://example.org", null, null, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Links are not allowed in a profile"));
        save(asha, profile(handle, "Asha", null, List.of("fr"), null, null)).andExpect(status().isBadRequest());
        save(asha, profile(handle, "Asha", null, null, List.of("PARTIES"), null)).andExpect(status().isBadRequest());
        save(asha, profile(handle, "Asha", null, null, null, "Chennai 600001")).andExpect(status().isBadRequest());
        save(asha, profile(handle, "ஆஷா தேவி", "கடற்கரை நடை", List.of("ta"), null, "சென்னை"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("ஆஷா தேவி"));
    }

    // ---- looking someone up ---------------------------------------------------------------------------------------

    @Test
    void lookup_byExactHandleOrId_showsTheRelationship_andNothingElse() throws Exception {
        Person asha = person();
        Person bala = person();
        String handle = unique("asha");
        String publicId = json(save(asha, profile(handle, "Asha", "Hello", List.of("ta"), List.of("CITY_LIFE"), "Chennai"))
                .andExpect(status().isOk())).get("publicId").asText();

        JsonNode seen = json(byHandle(bala, handle).andExpect(status().isOk()));
        assertThat(seen.get("relationship").asText()).isEqualTo("NONE");
        assertThat(fieldNames(seen)).containsExactlyInAnyOrder("publicId", "handle", "displayName", "bio", "languages",
                "interests", "city", "relationship");
        byPublicId(bala, publicId).andExpect(status().isOk()).andExpect(jsonPath("$.handle").value(handle));
        byHandle(asha, handle).andExpect(status().isOk()).andExpect(jsonPath("$.relationship").value("SELF"));
        byHandle(bala, "@" + handle.toUpperCase()).andExpect(status().isOk());

        // No prefix search, unknown and malformed handles: the same 404.
        byHandle(bala, handle.substring(0, handle.length() - 1)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No one with that handle"));
        byHandle(bala, "no..such").andExpect(status().isNotFound());
        byPublicId(bala, UUID.randomUUID().toString()).andExpect(status().isNotFound());
    }

    @Test
    void ownProfile_carriesNoCountsOrInternalIds() throws Exception {
        Person asha = person();
        JsonNode body = json(save(asha, profile(unique("asha"), "Asha")).andExpect(status().isOk()));
        assertThat(fieldNames(body)).containsExactlyInAnyOrder("publicId", "handle", "displayName", "bio", "languages",
                "interests", "city", "handleChangeAvailableAt");
    }

    @Test
    void blockedEitherWay_orADisabledOwner_looksExactlyLikeNoProfile() throws Exception {
        Person asha = person();
        Person bala = person();
        Person chitra = person();
        String ashaHandle = unique("asha");
        String balaHandle = unique("bala");
        save(asha, profile(ashaHandle, "Asha")).andExpect(status().isOk());
        save(bala, profile(balaHandle, "Bala")).andExpect(status().isOk());

        blocks.saveAndFlush(new Block(asha.id(), bala.id(), LocalDateTime.now(ZoneOffset.UTC)));
        byHandle(bala, ashaHandle).andExpect(status().isNotFound());   // the blocked person cannot see the blocker
        byHandle(asha, balaHandle).andExpect(status().isNotFound());   // nor the other way round
        byHandle(chitra, ashaHandle).andExpect(status().isOk());       // others are unaffected

        jdbc.update("UPDATE users SET enabled = FALSE WHERE id = ?", asha.id());
        byHandle(chitra, ashaHandle).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No one with that handle"));
    }

    @Test
    void lookups_areLimitedPerPerson() throws Exception {
        Person asha = person();
        Person bala = person();
        String handle = unique("asha");
        save(asha, profile(handle, "Asha")).andExpect(status().isOk());
        for (int i = 0; i < 15; i++) byHandle(bala, handle).andExpect(status().isOk());
        byHandle(bala, handle).andExpect(status().isTooManyRequests());
        byHandle(asha, handle).andExpect(status().isOk()); // someone else's limit is separate
        clock.advance(Duration.ofHours(1));
        byHandle(bala, handle).andExpect(status().isOk());
    }

    @Test
    void everyProfileEndpoint_needsASignedInCaller() throws Exception {
        mockMvc.perform(get("/api/profiles/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/profiles/by-handle/asha")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/profiles/me").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    record Person(String token, long id) {
    }

    private Person person() throws Exception {
        String email = "profile." + UUID.randomUUID() + "@example.com";
        String token = registerAndGetToken(mockMvc, objectMapper, email);
        return new Person(token, users.findByEmail(email).orElseThrow().getId());
    }

    private static Map<String, Object> profile(String handle, String displayName) {
        return profile(handle, displayName, null, null, null, null);
    }

    private static Map<String, Object> profile(String handle, String displayName, String bio, List<String> languages,
                                               List<String> interests, String city) {
        Map<String, Object> body = new HashMap<>();
        body.put("handle", handle);
        body.put("displayName", displayName);
        body.put("bio", bio);
        body.put("languages", languages);
        body.put("interests", interests);
        body.put("city", city);
        return body;
    }

    private ResultActions save(Person person, Map<String, Object> body) throws Exception {
        return mockMvc.perform(put("/api/profiles/me").header(HttpHeaders.AUTHORIZATION, bearer(person.token()))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions own(Person person) throws Exception {
        return mockMvc.perform(get("/api/profiles/me").header(HttpHeaders.AUTHORIZATION, bearer(person.token())));
    }

    private ResultActions byHandle(Person viewer, String handle) throws Exception {
        return mockMvc.perform(get("/api/profiles/by-handle/{handle}", handle)
                .header(HttpHeaders.AUTHORIZATION, bearer(viewer.token())));
    }

    private ResultActions byPublicId(Person viewer, String publicId) throws Exception {
        return mockMvc.perform(get("/api/profiles/{id}", publicId)
                .header(HttpHeaders.AUTHORIZATION, bearer(viewer.token())));
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) names.add(it.next());
        return names;
    }

    /** A handle unique to this run (the H2 database is shared by the test classes of one run). */
    private static String unique(String prefix) {
        return prefix + "_" + suffix();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
