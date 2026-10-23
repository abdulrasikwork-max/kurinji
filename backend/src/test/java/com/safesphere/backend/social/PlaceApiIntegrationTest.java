package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.user.repository.UserRepository;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 13B: places are created from a 7-character cell, de-duplicated per cell and normalised name, listed by a
 * 6-character area and its 8 neighbours, and record no creator (Q12).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlaceApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    private SocialClient social;
    private Person asha;
    private Person bala;

    @BeforeEach
    void setUp() throws Exception {
        social = new SocialClient(mockMvc, objectMapper, users);
        asha = social.person("Asha");
        bala = social.person("Bala");
    }

    @Test
    void create_returnsThePlace_withCellsAndNoPerson() throws Exception {
        String cell = randomCell();
        JsonNode place = social.json(social.createPlace(asha, "  Marina   Beach ", "nature_outdoors", cell)
                .andExpect(status().isOk()));
        assertThat(place.get("name").asText()).isEqualTo("Marina Beach");
        assertThat(place.get("category").asText()).isEqualTo("NATURE_OUTDOORS");
        assertThat(place.get("cell").asText()).isEqualTo(cell);
        assertThat(place.get("areaCell").asText()).isEqualTo(cell.substring(0, 6));
        List<String> fields = new ArrayList<>();
        place.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("publicId", "name", "category", "cell", "areaCell");
        // The table has no column that could name a person or a time of day.
        List<String> columns = jdbc.queryForList(
                "SELECT LOWER(COLUMN_NAME) FROM INFORMATION_SCHEMA.COLUMNS WHERE LOWER(TABLE_NAME) = 'places'", String.class);
        assertThat(columns).containsExactlyInAnyOrder("id", "public_id", "name", "normalized_name", "category", "cell",
                "area_cell", "created_on", "merged_into");
    }

    @Test
    void sameNameInTheSameCell_isTheSamePlace_evenFromSomeoneElse() throws Exception {
        String cell = randomCell();
        String first = social.place(asha, "Marina Beach", cell);
        String again = social.place(bala, "marina-beach!", cell);
        assertThat(again).isEqualTo(first);
        String elsewhere = social.place(bala, "Marina Beach", otherCellInArea(cell));
        assertThat(elsewhere).isNotEqualTo(first);
    }

    @Test
    void invalidInput_isRefused() throws Exception {
        String cell = randomCell();
        social.createPlace(asha, "Cafe", "FOOD_CAFES", cell.substring(0, 6)).andExpect(status().isBadRequest());
        social.createPlace(asha, "Cafe", "FOOD_CAFES", "tf2c1xa").andExpect(status().isBadRequest());
        social.createPlace(asha, "Cafe", "SHOPPING", cell).andExpect(status().isBadRequest());
        social.createPlace(asha, "C", "FOOD_CAFES", cell).andExpect(status().isBadRequest());
        social.createPlace(asha, "my home", "FOOD_CAFES", cell).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("private place")));
        social.createPlace(asha, "Cafe www.cafe.com", "FOOD_CAFES", cell).andExpect(status().isBadRequest());
    }

    @Test
    void nearby_isTheAreaAndItsEightNeighbours_only() throws Exception {
        String cell = randomCell();
        String area = cell.substring(0, 6);
        List<String> ring = new ArrayList<>(GeoCell.neighbourhood(area));
        ring.remove(area);
        String inArea = social.place(asha, "Inside " + suffix(), cell);
        String inNeighbour = social.place(asha, "Next door " + suffix(), ring.get(0) + "0");
        String far = social.place(asha, "Far away " + suffix(), farCell(area) + "0");
        List<String> ids = social.ids(social.nearbyPlaces(bala, area));
        assertThat(ids).contains(inArea, inNeighbour).doesNotContain(far);
        social.nearbyPlaces(bala, cell).andExpect(status().isBadRequest());   // a 7-character cell is not an area
        social.nearbyPlaces(bala, "TF2C1X").andExpect(status().isOk());       // upper case is read as the same area
    }

    @Test
    void oneByPublicId_andUnknown404() throws Exception {
        String id = social.place(asha, "Book stall " + suffix(), randomCell());
        mockMvcGet(bala, "/api/places/" + id).andExpect(status().isOk()).andExpect(jsonPath("$.publicId").value(id));
        mockMvcGet(bala, "/api/places/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void placeCreation_isLimitedPerDay() throws Exception {
        Person busy = social.person("Busy");
        for (int i = 0; i < 10; i++) social.place(busy, "Stall " + i + " " + suffix(), randomCell());
        social.createPlace(busy, "One more " + suffix(), "FOOD_CAFES", randomCell()).andExpect(status().isTooManyRequests());
    }

    private org.springframework.test.web.servlet.ResultActions mockMvcGet(Person who, String path) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header(org.springframework.http.HttpHeaders.AUTHORIZATION,
                        com.safesphere.backend.support.TestAccounts.bearer(who.token())));
    }

    /** A random cell in Chennai (each test gets its own area, so tests never see each other's places). */
    static String randomCell() {
        double lat = 12.9 + Math.random() * 0.3;
        double lng = 80.1 + Math.random() * 0.2;
        return GeoCell.encode(lat, lng, GeoCell.BLOCK_PRECISION);
    }

    private static String otherCellInArea(String cell) {
        Iterator<String> children = GeoCell.children(cell.substring(0, 6)).iterator();
        String other = children.next();
        return other.equals(cell) ? children.next() : other;
    }

    private static String farCell(String area) {
        double[] centre = GeoCell.centre(area);
        return GeoCell.encode(centre[0] + 0.1, centre[1] + 0.1, GeoCell.AREA_PRECISION);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 6).replaceAll("[0-9]", "x");
    }
}
