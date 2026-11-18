package com.safesphere.backend.circlealert;

import static com.safesphere.backend.support.TestAccounts.bearer;
import static com.safesphere.backend.support.TestAccounts.e164For;
import static com.safesphere.backend.support.TestAccounts.registerVerifiedAndLogin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.account.service.AccountEraser;
import com.safesphere.backend.circlealert.service.AlertDispatcher;
import com.safesphere.backend.circlealert.service.AlertQueue;
import com.safesphere.backend.support.StubSmsGatewayConfig;
import com.safesphere.backend.support.StubSmsGatewayConfig.StubSmsGateway;
import com.safesphere.backend.user.entity.UserRole;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * N6 (D20): the server texts the Safety Circle. End to end through the API with a stub SMS provider, on its own H2
 * database (the daily limits are counted in the database). The queue's timing is driven by moving `next_attempt_at`
 * and by calling the dispatcher directly (the scheduled job is off in tests).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubSmsGatewayConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:server-alerts;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "app.alerts.per-number=4",
        "app.alerts.rounds-per-person=5"})
class ServerAlertApiTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StubSmsGateway sms;
    @Autowired private AlertDispatcher dispatcher;
    @Autowired private AlertQueue queue;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private AccountEraser eraser;

    @BeforeEach
    void reset() {
        sms.reset();
    }

    private record Person(long id, String token, String phone) {
    }

    private Person person() throws Exception {
        String key = "alerts-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID();
        JsonNode answer = registerVerifiedAndLogin(mockMvc, objectMapper, "Asha Devi", key);
        return new Person(answer.get("user").get("id").asLong(), answer.get("accessToken").asText(), e164For(key));
    }

    /** A fresh Indian mobile number nobody else in this class uses. */
    private static String freshNumber() {
        return "+9198" + String.format("%08d", Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000_000L));
    }

    // ---- the choice -----------------------------------------------------------------------------------------------

    @Test
    void withoutAnExplicitChoice_nothingIsSent_andTheSettingsShowThePreview() throws Exception {
        Person p = person();
        contact(p, "Ravi", freshNumber(), 1);
        mockMvc.perform(get("/api/safety-alerts").header(HttpHeaders.AUTHORIZATION, bearer(p.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choice").value("UNSET"))
                .andExpect(jsonPath("$.preview").value(org.hamcrest.Matchers.startsWith("IRL SOS: Asha Devi pressed SOS at ")))
                .andExpect(jsonPath("$.contacts[0].automatic").value(true))
                .andExpect(jsonPath("$.testAvailable").value(true));

        long sos = startSos(p);
        location(p, sos);
        dispatcher.runOnce();
        assertThat(sms.sent()).isEmpty();
        alerts(p, sos).andExpect(jsonPath("$.choice").value("UNSET")).andExpect(jsonPath("$.alerts").isEmpty());
    }

    // ---- the first text --------------------------------------------------------------------------------------------

    @Test
    void theFirstText_waitsForTheFirstLocation_thenGoesWithAMapLink_andOtherNumbersAreTextYourself() throws Exception {
        Person p = chosen();
        String ravi = freshNumber();
        long raviId = contact(p, "Ravi", ravi, 1);
        long abroad = contact(p, "Uncle", "+14155550100", 2);

        long sos = startSos(p);
        dispatcher.runOnce();
        assertThat(sms.sent()).as("no location yet and the 20 s are not over").isEmpty();

        location(p, sos);
        dispatcher.runOnce();
        List<String> texts = sms.textsTo(ravi);
        assertThat(texts).hasSize(1);
        assertThat(texts.get(0)).startsWith("IRL SOS: Asha Devi pressed SOS at ").contains("maps.google.com/?q=10.81234,78.69012")
                .contains("Call " + p.phone().substring(3) + " now.");
        assertThat(sms.sent()).hasSize(1); // never the foreign number

        JsonNode answer = json(alerts(p, sos).andExpect(status().isOk()));
        assertThat(answer.get("choice").asText()).isEqualTo("ON");
        assertThat(state(answer, raviId).get("status").asText()).isEqualTo("SENT");
        assertThat(state(answer, raviId).get("contactName").asText()).isEqualTo("Ravi");
        assertThat(state(answer, abroad).get("status").asText()).isEqualTo("SKIPPED");
        assertThat(state(answer, abroad).get("reason").asText()).isEqualTo("NOT_INDIAN_MOBILE");

        dispatcher.runOnce();
        assertThat(sms.sent()).as("sent once").hasSize(1);
    }

    @Test
    void withoutALocation_theFirstTextGoesAfter20Seconds_andTheLocationFollowsOnce() throws Exception {
        Person p = chosen();
        String ravi = freshNumber();
        contact(p, "Ravi", ravi, 1);
        long sos = startSos(p);
        dispatcher.runOnce(); // plans
        makeDue(sos);
        dispatcher.runOnce();
        assertThat(sms.textsTo(ravi)).singleElement().asString().contains("Location not known yet.");

        location(p, sos);
        dispatcher.runOnce();
        dispatcher.runOnce();
        List<String> texts = sms.textsTo(ravi);
        assertThat(texts).hasSize(2);
        assertThat(texts.get(1)).startsWith("IRL SOS update: Asha Devi was here at ").contains("maps.google.com/?q=");
    }

    @Test
    void aFalseAlarmBeforeSending_sendsNothing_andNoClosingText() throws Exception {
        Person p = chosen();
        String ravi = freshNumber();
        long raviId = contact(p, "Ravi", ravi, 1);
        long sos = startSos(p);
        dispatcher.runOnce();
        end(p, sos, "cancel");
        location(p, sos); // a late point (allowed in the grace period) must not send anything either
        makeDue(sos);
        dispatcher.runOnce();
        dispatcher.runOnce();
        assertThat(sms.sent()).isEmpty();
        JsonNode answer = json(alerts(p, sos));
        assertThat(state(answer, raviId).get("status").asText()).isEqualTo("SKIPPED");
        assertThat(state(answer, raviId).get("reason").asText()).isEqualTo("ENDED");
    }

    @Test
    void imSafe_afterATextWentOut_sendsOneClosingText() throws Exception {
        Person p = chosen();
        String ravi = freshNumber();
        contact(p, "Ravi", ravi, 1);
        long sos = startSos(p);
        location(p, sos);
        dispatcher.runOnce();
        end(p, sos, "resolve");
        dispatcher.runOnce();
        dispatcher.runOnce();
        List<String> texts = sms.textsTo(ravi);
        assertThat(texts).hasSize(2);
        assertThat(texts.get(1)).startsWith("IRL: Asha Devi ended the SOS at ").contains("marked it I'm safe");
    }

    @Test
    void anSosEndedFromAnotherPhone_sendsNoClosingText() throws Exception {
        Person p = chosen();
        String ravi = freshNumber();
        contact(p, "Ravi", ravi, 1);
        long sos = startSos(p);
        location(p, sos);
        dispatcher.runOnce();
        jdbc.update("update emergency_events set status = 'RESOLVED', ended_at = ?, ended_elsewhere = true,"
                + " started_session = null where id = ?", LocalDateTime.now(ZoneOffset.UTC), sos);
        dispatcher.runOnce();
        assertThat(sms.textsTo(ravi)).hasSize(1);
    }

    @Test
    void anSosRecordedMoreThan30MinutesAfterItStarted_isNotTexted_butOneWithin30Minutes_isSentLate() throws Exception {
        Person p = chosen();
        String ravi = freshNumber();
        long raviId = contact(p, "Ravi", ravi, 1);
        long old = offlineSos(p, 40);
        dispatcher.runOnce();
        JsonNode answer = json(alerts(p, old));
        assertThat(state(answer, raviId).get("reason").asText()).isEqualTo("TOO_LATE");
        end(p, old, "resolve");

        long recent = offlineSos(p, 10);
        location(p, recent);
        dispatcher.runOnce();
        assertThat(sms.textsTo(ravi)).singleElement().asString().startsWith("IRL SOS (sent late): ");
        assertThat(state(json(alerts(p, recent)), raviId).get("late").asBoolean()).isTrue();
    }

    @Test
    void onlyTheFirstFiveContactsAreTexted_byPriority_eachNumberOnce() throws Exception {
        Person p = chosen();
        String shared = freshNumber();
        long first = contact(p, "A", shared, 1);
        long same = contact(p, "A again", "0" + shared.substring(3), 2); // the same mobile, typed differently
        for (int i = 0; i < 5; i++) contact(p, "C" + i, freshNumber(), 3);
        long sos = startSos(p);
        location(p, sos);
        dispatcher.runOnce();
        assertThat(sms.sent()).hasSize(5);
        JsonNode answer = json(alerts(p, sos));
        assertThat(state(answer, first).get("status").asText()).isEqualTo("SENT");
        assertThat(state(answer, same).get("reason").asText()).isEqualTo("DUPLICATE_NUMBER");
        long notFirstFive = 0;
        for (JsonNode a : answer.get("alerts")) if ("NOT_IN_FIRST_FIVE".equals(a.path("reason").asText())) notFirstFive++;
        assertThat(notFirstFive).isEqualTo(1);
    }

    // ---- provider failures and restarts ----------------------------------------------------------------------------

    @Test
    void aRefusal_isRetried_thenFailed_andTheManualTextStaysTheWay() throws Exception {
        Person p = chosen();
        long raviId = contact(p, "Ravi", freshNumber(), 1);
        long sos = startSos(p);
        location(p, sos);
        sms.refuse(true);
        dispatcher.runOnce();
        assertThat(state(json(alerts(p, sos)), raviId).get("status").asText()).isEqualTo("QUEUED");
        makeDue(sos);
        dispatcher.runOnce();
        makeDue(sos);
        dispatcher.runOnce();
        assertThat(sms.calls()).isEqualTo(3);
        assertThat(state(json(alerts(p, sos)), raviId).get("status").asText()).isEqualTo("FAILED");
        makeDue(sos);
        dispatcher.runOnce();
        assertThat(sms.calls()).as("no fourth attempt").isEqualTo(3);
    }

    @Test
    void aTextClaimedJustBeforeACrash_isTriedAgainLater_fromTheDatabase() throws Exception {
        Person p = chosen();
        String ravi = freshNumber();
        contact(p, "Ravi", ravi, 1);
        long sos = startSos(p);
        location(p, sos);
        queue.plan(now());
        long id = jdbc.queryForObject("select id from circle_alerts where emergency_id = ? and channel = 'SERVER_SMS'",
                Long.class, sos);
        assertThat(queue.claim(id, now())).isPresent(); // the process "dies" here: no provider call, no result
        dispatcher.runOnce();
        assertThat(sms.sent()).as("held for a while after a claim").isEmpty();
        makeDue(sos);
        dispatcher.runOnce();
        assertThat(sms.textsTo(ravi)).hasSize(1);
    }

    // ---- limits ----------------------------------------------------------------------------------------------------

    @Test
    void oneNumber_getsAtMostTheDailyLimit_acrossSenders() throws Exception {
        String shared = freshNumber();
        for (int i = 0; i < 5; i++) {
            Person p = chosen();
            long c = contact(p, "Ravi", shared, 1);
            long sos = startSos(p);
            location(p, sos);
            dispatcher.runOnce();
            if (i == 4) assertThat(state(json(alerts(p, sos)), c).get("reason").asText()).isEqualTo("NUMBER_LIMIT");
        }
        assertThat(sms.textsTo(shared)).hasSize(4);
    }

    // ---- ownership -------------------------------------------------------------------------------------------------

    @Test
    void anotherPersonsSosAlerts_areNotFound() throws Exception {
        Person a = chosen();
        Person b = person();
        long sos = startSos(a);
        mockMvc.perform(get("/api/emergencies/" + sos + "/alerts").header(HttpHeaders.AUTHORIZATION, bearer(b.token())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/emergencies/" + sos + "/alerts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/safety-alerts")).andExpect(status().isUnauthorized());
    }

    @Test
    void aPhoneCannotReportServerStates() throws Exception {
        Person p = chosen();
        long sos = startSos(p);
        for (String[] bad : List.of(new String[] {"SERVER_SMS", "SENT", "SOS_STARTED"},
                new String[] {"SMS_COMPOSER", "QUEUED", "SOS_STARTED"},
                new String[] {"SMS_COMPOSER", "SKIPPED", "SOS_STARTED"},
                new String[] {"SMS_COMPOSER", "COMPOSER_OPENED", "TEST"})) {
            Map<String, Object> report = Map.of("clientRef", UUID.randomUUID().toString(), "kind", bad[2],
                    "channel", bad[0], "status", bad[1], "occurredAt", Instant.now().toString());
            send(post("/api/emergencies/" + sos + "/circle-alerts"), p, Map.of("reports", List.of(report)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ---- Test my alerts ------------------------------------------------------------------------------------------

    @Test
    void testMyAlerts_textsTheOwnNumber_onceADay() throws Exception {
        Person p = person();
        send(post("/api/safety-alerts/test"), p, null).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.lastTest.status").value("QUEUED"))
                .andExpect(jsonPath("$.testAvailable").value(false));
        dispatcher.runOnce();
        assertThat(sms.textsTo(p.phone())).singleElement().asString().startsWith("IRL test, not an emergency");
        mockMvc.perform(get("/api/safety-alerts").header(HttpHeaders.AUTHORIZATION, bearer(p.token())))
                .andExpect(jsonPath("$.lastTest.status").value("SENT"));
        send(post("/api/safety-alerts/test"), p, null).andExpect(status().isTooManyRequests());
    }

    // ---- the stop list -------------------------------------------------------------------------------------------

    @Test
    void aStoppedNumber_isNeverTexted_andThePersonOnlyLearnsIrlCantTextIt() throws Exception {
        Person admin = person();
        var user = users.findById(admin.id()).orElseThrow();
        user.setRole(UserRole.ADMIN);
        users.saveAndFlush(user);
        Person p = chosen();
        String stopped = freshNumber();
        long c = contact(p, "Meera", stopped, 1);

        send(post("/api/admin/alert-stops"), p, Map.of("phoneNumber", stopped)).andExpect(status().isForbidden());
        send(post("/api/admin/alert-stops"), admin, Map.of("phoneNumber", "12345")).andExpect(status().isBadRequest());
        send(post("/api/admin/alert-stops"), admin, Map.of("phoneNumber", stopped.substring(3)))
                .andExpect(status().isNoContent());
        send(post("/api/admin/alert-stops/check"), admin, Map.of("phoneNumber", stopped))
                .andExpect(jsonPath("$.stopped").value(true));
        assertThat(jdbc.queryForObject("select count(*) from alert_stops where number_key like ?", Long.class,
                "%" + stopped.substring(3) + "%")).as("never the number itself").isZero();

        mockMvc.perform(get("/api/safety-alerts").header(HttpHeaders.AUTHORIZATION, bearer(p.token())))
                .andExpect(jsonPath("$.contacts[0].automatic").value(false))
                .andExpect(jsonPath("$.contacts[0].reason").value("NOT_AVAILABLE"));
        long sos = startSos(p);
        location(p, sos);
        dispatcher.runOnce();
        assertThat(sms.sent()).isEmpty();
        assertThat(state(json(alerts(p, sos)), c).get("reason").asText()).isEqualTo("NOT_AVAILABLE");

        send(post("/api/admin/alert-stops/lift"), admin, Map.of("phoneNumber", stopped)).andExpect(status().isNoContent());
        send(post("/api/admin/alert-stops/check"), admin, Map.of("phoneNumber", stopped))
                .andExpect(jsonPath("$.stopped").value(false));
    }

    // ---- erasure ----------------------------------------------------------------------------------------------------

    @Test
    void erasingAnAccount_removesItsAlertRowsAndChoice_andKeepsItsStopEntriesWithoutItsName() throws Exception {
        Person p = chosen();
        var user = users.findById(p.id()).orElseThrow();
        user.setRole(UserRole.ADMIN);
        users.saveAndFlush(user);
        send(post("/api/safety-alerts/test"), p, null).andExpect(status().isAccepted());
        send(post("/api/admin/alert-stops"), p, Map.of("phoneNumber", freshNumber())).andExpect(status().isNoContent());
        jdbc.update("update users set role = 'USER', deletion_requested_at = ?, deletion_due_at = ? where id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(8), LocalDateTime.now(ZoneOffset.UTC).minusDays(1), p.id());

        assertThat(eraser.eraseIfDue(p.id())).isIn(AccountEraser.Outcome.DELETED, AccountEraser.Outcome.ANONYMISED);

        assertThat(jdbc.queryForObject("select count(*) from circle_alerts where user_id = ?", Long.class, p.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from alert_settings where user_id = ?", Long.class, p.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from alert_stops where added_by = ?", Long.class, p.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from alert_stops where added_by is null", Long.class)).isPositive();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private Person chosen() throws Exception {
        Person p = person();
        send(put("/api/safety-alerts"), p, Map.of("on", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.choice").value("ON"));
        return p;
    }

    private long contact(Person p, String name, String phone, int priority) throws Exception {
        return json(send(post("/api/safety-contacts"), p, Map.of("name", name, "phoneNumber", phone,
                "relationship", "FRIEND", "priority", priority, "primary", false, "enabled", true))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private long startSos(Person p) throws Exception {
        return json(mockMvc.perform(post("/api/emergencies").header(HttpHeaders.AUTHORIZATION, bearer(p.token())))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private long offlineSos(Person p, int minutesAgo) throws Exception {
        return json(send(post("/api/emergencies"), p, Map.of("clientRef", UUID.randomUUID().toString(),
                "startedAt", Instant.now().minus(minutesAgo, ChronoUnit.MINUTES).toString()))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private void location(Person p, long sos) throws Exception {
        send(post("/api/emergencies/" + sos + "/locations"), p, Map.of("points", List.of(Map.of(
                "latitude", 10.812341, "longitude", 78.690123, "accuracyMeters", 8.0, "source", "FUSED",
                "recordedAt", Instant.now().toString())))).andExpect(status().isCreated());
    }

    private void end(Person p, long sos, String how) throws Exception {
        mockMvc.perform(put("/api/emergencies/" + sos + "/" + how).header(HttpHeaders.AUTHORIZATION, bearer(p.token())))
                .andExpect(status().isOk());
    }

    private ResultActions alerts(Person p, long sos) throws Exception {
        return mockMvc.perform(get("/api/emergencies/" + sos + "/alerts").header(HttpHeaders.AUTHORIZATION,
                bearer(p.token())));
    }

    private static JsonNode state(JsonNode answer, long contactId) {
        for (JsonNode a : answer.get("alerts")) {
            if (a.path("contactId").asLong() == contactId && "SOS_STARTED".equals(a.get("kind").asText())) return a;
        }
        throw new AssertionError("no first text for contact " + contactId + ": " + answer);
    }

    /** Pretends time has passed: every queued text of the SOS is due now. */
    private void makeDue(long sos) {
        jdbc.update("update circle_alerts set next_attempt_at = ? where emergency_id = ? and status = 'QUEUED'",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), sos);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               Person p, Object body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer(p.token()));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(request);
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
