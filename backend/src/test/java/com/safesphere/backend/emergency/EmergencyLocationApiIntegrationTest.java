package com.safesphere.backend.emergency;

import static com.safesphere.backend.support.TestAccounts.bearer;
import static com.safesphere.backend.support.TestAccounts.registerAndGetToken;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.emergency.repository.EmergencyEventRepository;
import com.safesphere.backend.emergency.repository.EmergencyLocationRepository;
import com.safesphere.backend.safetycontact.repository.SafetyContactRepository;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * End-to-end tests of /api/emergencies/{id}/locations (Phase 4A): validation, time windows, the upload
 * grace period, duplicates, the per-emergency limit and ownership between two users.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmergencyLocationApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private SafetyContactRepository contactRepository;
    @Autowired private EmergencyEventRepository emergencyRepository;
    @Autowired private EmergencyLocationRepository locationRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() throws Exception {
        locationRepository.deleteAll();
        contactRepository.deleteAll();
        emergencyRepository.deleteAll();
        userRepository.deleteAll();
        tokenA = registerAndGetToken(mockMvc, objectMapper, "user.a@example.com");
        tokenB = registerAndGetToken(mockMvc, objectMapper, "user.b@example.com");
    }

    // ---- happy path ----------------------------------------------------------------------------

    @Test
    void upload_storesThePoint_andItIsReturnedAsLatest() throws Exception {
        long id = start(tokenA);
        Instant recordedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        upload(tokenA, id, List.of(point(12.9715987, 77.5945627, recordedAt)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.received").value(1))
                .andExpect(jsonPath("$.stored").value(1));

        mockMvc.perform(get(latestUrl(id)).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isOk())
                // Stored with 6 decimals (DECIMAL(9,6)).
                .andExpect(jsonPath("$.latitude").value(12.971599))
                .andExpect(jsonPath("$.longitude").value(77.594563))
                .andExpect(jsonPath("$.accuracyMeters").value(8.5))
                .andExpect(jsonPath("$.source").value("FUSED"))
                .andExpect(jsonPath("$.batteryPercent").value(64))
                .andExpect(jsonPath("$.recordedAt").value(recordedAt.toString()))
                .andExpect(jsonPath("$.receivedAt").exists());
    }

    @Test
    void optionalFieldsMayBeLeftOut_andSourceDefaultsToUnknown() throws Exception {
        long id = start(tokenA);
        Map<String, Object> minimal = Map.of("latitude", 1.5, "longitude", -2.5, "recordedAt", Instant.now().toString());

        upload(tokenA, id, List.of(minimal)).andExpect(status().isCreated());

        mockMvc.perform(get(latestUrl(id)).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.source").value("UNKNOWN"))
                .andExpect(jsonPath("$.accuracyMeters").doesNotExist())
                .andExpect(jsonPath("$.batteryPercent").doesNotExist());
    }

    @Test
    void anOfflineBatch_isStored_andTheListIsNewestFirst() throws Exception {
        long id = start(tokenA);
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        upload(tokenA, id, List.of(point(1, 1, base), point(3, 3, base.plusSeconds(20)), point(2, 2, base.plusSeconds(10))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stored").value(3));

        mockMvc.perform(get(listUrl(id)).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].latitude").value(3.0))
                .andExpect(jsonPath("$[1].latitude").value(2.0))
                .andExpect(jsonPath("$[2].latitude").value(1.0));
        mockMvc.perform(get(latestUrl(id)).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.latitude").value(3.0));
    }

    @Test
    void resendingABatch_doesNotCreateDuplicates() throws Exception {
        long id = start(tokenA);
        Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        List<Map<String, Object>> batch = List.of(point(1, 1, t), point(1, 1, t), point(2, 2, t.plusSeconds(5)));

        upload(tokenA, id, batch).andExpect(status().isCreated())
                .andExpect(jsonPath("$.received").value(3))
                .andExpect(jsonPath("$.stored").value(2));
        // The phone did not get the answer and sends the same batch again.
        upload(tokenA, id, batch).andExpect(status().isCreated())
                .andExpect(jsonPath("$.stored").value(0));

        assertEquals(2, locationRepository.countByEmergencyId(id));
    }

    // ---- validation ----------------------------------------------------------------------------

    @Test
    void invalidPoints_return400() throws Exception {
        long id = start(tokenA);
        Instant now = Instant.now();

        upload(tokenA, id, List.of()).andExpect(status().isBadRequest());
        upload(tokenA, id, List.of(point(90.000001, 0, now))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['points[0].latitude']").exists());
        upload(tokenA, id, List.of(point(-91, 0, now))).andExpect(status().isBadRequest());
        upload(tokenA, id, List.of(point(0, 180.5, now))).andExpect(status().isBadRequest());
        upload(tokenA, id, List.of(point(0, -181, now))).andExpect(status().isBadRequest());
        upload(tokenA, id, List.of(with(point(0, 0, now), "batteryPercent", 101))).andExpect(status().isBadRequest());
        upload(tokenA, id, List.of(with(point(0, 0, now), "batteryPercent", -1))).andExpect(status().isBadRequest());
        upload(tokenA, id, List.of(with(point(0, 0, now), "accuracyMeters", -3))).andExpect(status().isBadRequest());
        upload(tokenA, id, List.of(Map.of("latitude", 0, "longitude", 0))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['points[0].recordedAt']").exists());
        upload(tokenA, id, List.of(Map.of("longitude", 0, "recordedAt", now.toString()))).andExpect(status().isBadRequest());
        // Unknown source value and an unreadable time are malformed JSON for us.
        upload(tokenA, id, List.of(with(point(0, 0, now), "source", "SATELLITE"))).andExpect(status().isBadRequest());
        upload(tokenA, id, List.of(with(point(0, 0, now), "recordedAt", "yesterday"))).andExpect(status().isBadRequest());

        assertEquals(0, locationRepository.countByEmergencyId(id));
    }

    @Test
    void moreThan50PointsInOneBatch_returns400() throws Exception {
        long id = start(tokenA);

        upload(tokenA, id, points(51, Instant.now())).andExpect(status().isBadRequest());
        upload(tokenA, id, points(50, Instant.now())).andExpect(status().isCreated());
    }

    @Test
    void oneBadPoint_rejectsTheWholeBatch() throws Exception {
        long id = start(tokenA);
        Instant now = Instant.now();

        upload(tokenA, id, List.of(point(1, 1, now), point(2, 2, now.plus(1, ChronoUnit.HOURS))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Location time cannot be in the future"));

        assertEquals(0, locationRepository.countByEmergencyId(id));
    }

    // ---- time windows --------------------------------------------------------------------------

    @Test
    void pointsSlightlyInTheFuture_areAccepted_withinTheClockTolerance() throws Exception {
        long id = start(tokenA);

        upload(tokenA, id, List.of(point(1, 1, Instant.now().plus(4, ChronoUnit.MINUTES)))).andExpect(status().isCreated());
        upload(tokenA, id, List.of(point(1, 1, Instant.now().plus(6, ChronoUnit.MINUTES)))).andExpect(status().isBadRequest());
    }

    @Test
    void pointsFromBeforeTheEmergencyStarted_areRejected() throws Exception {
        long id = start(tokenA);

        upload(tokenA, id, List.of(point(1, 1, Instant.now().minus(10, ChronoUnit.MINUTES))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Location time is before the emergency started"));
    }

    @Test
    void afterTheEmergencyEnds_pointsRecordedWhileActive_canStillBeUploadedDuringTheGrace() throws Exception {
        long id = start(tokenA);
        Instant whileActive = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        mockMvc.perform(put("/api/emergencies/" + id + "/resolve").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isOk());

        upload(tokenA, id, List.of(point(1, 1, whileActive))).andExpect(status().isCreated());
    }

    @Test
    void pointsRecordedAfterTheEmergencyEnded_areRejected() throws Exception {
        long id = start(tokenA);
        mockMvc.perform(put("/api/emergencies/" + id + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)));
        // Pretend it ended 10 minutes ago (still inside the 15 minute grace).
        moveTimesBack(id, 20, 10);

        upload(tokenA, id, List.of(point(1, 1, Instant.now())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Location time is after the emergency ended"));
    }

    @Test
    void afterTheGraceHasPassed_uploadsReturn409() throws Exception {
        long id = start(tokenA);
        mockMvc.perform(put("/api/emergencies/" + id + "/resolve").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)));
        // Started 30 minutes ago, ended 16 minutes ago: the 15 minute grace is over.
        moveTimesBack(id, 30, 16);

        upload(tokenA, id, List.of(point(1, 1, Instant.now().minus(20, ChronoUnit.MINUTES))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This emergency is no longer accepting locations"));
        // Reading what was stored still works.
        mockMvc.perform(get(listUrl(id)).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isOk());
    }

    // ---- limits and reads ----------------------------------------------------------------------

    @Test
    void oneEmergencyStoresAtMost1000Points_andTheListReturnsTheNewest100() throws Exception {
        long id = start(tokenA);
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        for (int batch = 0; batch < 20; batch++) {
            upload(tokenA, id, points(50, base.plusMillis(batch * 50L))).andExpect(status().isCreated());
        }

        upload(tokenA, id, List.of(point(1, 1, base.plusSeconds(30))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Location limit reached for this emergency"));
        // A re-sent point the server already has is still fine: nothing new would be stored.
        upload(tokenA, id, List.of(point(0, 0, base))).andExpect(status().isCreated());

        mockMvc.perform(get(listUrl(id)).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$", hasSize(100)))
                .andExpect(jsonPath("$[0].recordedAt").value(base.plusMillis(999).toString()));
    }

    @Test
    void latest_whenNothingWasRecorded_returns404() throws Exception {
        long id = start(tokenA);

        mockMvc.perform(get(latestUrl(id)).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No location recorded for this emergency"));
        mockMvc.perform(get(listUrl(id)).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ---- ownership and authentication ----------------------------------------------------------

    @Test
    void userB_cannotUploadToOrReadUserAsEmergencyLocations() throws Exception {
        long idOfA = start(tokenA);
        upload(tokenA, idOfA, List.of(point(1, 1, Instant.now()))).andExpect(status().isCreated());

        upload(tokenB, idOfA, List.of(point(2, 2, Instant.now().plusSeconds(1)))).andExpect(status().isNotFound());
        mockMvc.perform(get(latestUrl(idOfA)).header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Emergency not found"));
        mockMvc.perform(get(listUrl(idOfA)).header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(status().isNotFound());

        // A's data is unchanged.
        assertEquals(1, locationRepository.countByEmergencyId(idOfA));
    }

    @Test
    void anEmergencyThatDoesNotExist_returns404() throws Exception {
        upload(tokenA, 999_999, List.of(point(1, 1, Instant.now()))).andExpect(status().isNotFound());
        mockMvc.perform(get(latestUrl(999_999)).header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isNotFound());
    }

    @Test
    void everyLocationEndpointRequiresAToken() throws Exception {
        mockMvc.perform(post(listUrl(1)).contentType(MediaType.APPLICATION_JSON).content("{\"points\":[]}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(listUrl(1))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(latestUrl(1))).andExpect(status().isUnauthorized());
    }

    // ---- helpers -------------------------------------------------------------------------------

    private long start(String token) throws Exception {
        String body = mockMvc.perform(post("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private ResultActions upload(String token, long emergencyId, List<?> points) throws Exception {
        return mockMvc.perform(post(listUrl(emergencyId))
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("points", points))));
    }

    private static Map<String, Object> point(double latitude, double longitude, Instant recordedAt) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("latitude", latitude);
        point.put("longitude", longitude);
        point.put("accuracyMeters", 8.5);
        point.put("source", "FUSED");
        point.put("batteryPercent", 64);
        point.put("recordedAt", recordedAt.toString());
        return point;
    }

    private static Map<String, Object> with(Map<String, Object> point, String key, Object value) {
        point.put(key, value);
        return point;
    }

    /** count points one millisecond apart, starting at base. */
    private static List<Map<String, Object>> points(int count, Instant base) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            points.add(point(0, 0, base.plusMillis(i)));
        }
        return points;
    }

    /** Rewrites an ended emergency's times so it started and ended the given minutes ago. */
    private void moveTimesBack(long emergencyId, int startedMinutesAgo, int endedMinutesAgo) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update("update emergency_events set started_at = ?, ended_at = ? where id = ?",
                now.minusMinutes(startedMinutesAgo), now.minusMinutes(endedMinutesAgo), emergencyId);
    }

    private static String listUrl(long emergencyId) {
        return "/api/emergencies/" + emergencyId + "/locations";
    }

    private static String latestUrl(long emergencyId) {
        return listUrl(emergencyId) + "/latest";
    }
}
