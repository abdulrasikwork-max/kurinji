package com.safesphere.backend.circlealert;

import static com.safesphere.backend.support.TestAccounts.bearer;
import static com.safesphere.backend.support.TestAccounts.e164For;
import static com.safesphere.backend.support.TestAccounts.registerVerifiedAndLogin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.circlealert.service.AlertDispatcher;
import com.safesphere.backend.circlealert.service.AlertQueue;
import com.safesphere.backend.support.StubPushSenderConfig;
import com.safesphere.backend.support.StubSmsGatewayConfig.StubSmsGateway;
import com.safesphere.backend.support.StubSmsGatewayConfig;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * N7-B3 (D36 = N-128, D37 = N-129): the Safety Circle in the app and On my way's server reminder, end to end through
 * the API with stub SMS and push senders on their own H2 database. The owner learns nothing before the member accepts;
 * the member's SOS push is the twin of their contact's first text; the member's view is 404 for anyone else.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({StubSmsGatewayConfig.class, StubPushSenderConfig.class})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:circle-in-app;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "app.alerts.rounds-per-person=5"})
class CircleInAppApiTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StubSmsGateway sms;
    @Autowired private AlertDispatcher dispatcher;
    @Autowired private AlertQueue queue;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        sms.reset();
    }

    private record Person(long id, String token, String phone) {
    }

    private Person person(String name) throws Exception {
        String key = "circle-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID();
        JsonNode answer = registerVerifiedAndLogin(mockMvc, objectMapper, name, key);
        Person p = new Person(answer.get("user").get("id").asLong(), answer.get("accessToken").asText(), e164For(key));
        send(put("/api/push/device"), p, Map.of("token", "ci:" + UUID.randomUUID() + "z".repeat(60)))
                .andExpect(status().isNoContent());
        return p;
    }

    private static String freshNumber() {
        return "+9197" + String.format("%08d", Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000_000L));
    }

    // ---- links -----------------------------------------------------------------------------------------------------

    @Test
    void addingAMembersNumber_asksOnlyThem_andTheOwnerSeesNothingUntilTheyAccept() throws Exception {
        Person owner = person("Asha Devi");
        Person member = person("Bala Murugan");
        long contactId = contact(owner, "Bala", member.phone());

        assertThat(contactJson(owner, contactId).get("inApp").asBoolean()).isFalse();
        JsonNode requests = json(send(get("/api/circle/requests"), member, null).andExpect(status().isOk()));
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).get("ownerName").asText()).isEqualTo("Asha Devi");
        assertThat(requests.get(0).size()).as("an id and a name, nothing else").isEqualTo(2);
        String id = requests.get(0).get("id").asText();
        assertThat(pushes(member)).containsExactly("CIRCLE_REQUEST " + id);
        assertThat(pushes(owner)).isEmpty();

        Person stranger = person("Chitra");
        send(post("/api/circle/requests/" + id + "/accept"), stranger, null).andExpect(status().isNotFound());
        send(post("/api/circle/requests/" + id + "/accept"), member, null).andExpect(status().isNoContent());
        assertThat(contactJson(owner, contactId).get("inApp").asBoolean()).isTrue();
        send(get("/api/safety-contacts"), owner, null).andExpect(jsonPath("$[0].inApp").value(true));
        assertThat(json(send(get("/api/circle/requests"), member, null))).isEmpty();
        JsonNode in = json(send(get("/api/circle/in"), member, null).andExpect(status().isOk()));
        assertThat(in).hasSize(1);

        send(delete("/api/circle/in/" + in.get(0).get("id").asText()), member, null).andExpect(status().isNoContent());
        assertThat(contactJson(owner, contactId).get("inApp").asBoolean()).as("they left").isFalse();
    }

    @Test
    void aNumberWithoutIrl_yourOwnNumber_aChangedNumber_andDontAskAgain_makeNoRequest() throws Exception {
        Person owner = person("Asha Devi");
        Person member = person("Bala Murugan");
        contact(owner, "Ravi", freshNumber());
        contact(owner, "Me", owner.phone());
        assertThat(pushes(owner)).isEmpty();

        long contactId = contact(owner, "Bala", member.phone());
        String id = json(send(get("/api/circle/requests"), member, null)).get(0).get("id").asText();
        send(post("/api/circle/requests/" + id + "/accept"), member, null).andExpect(status().isNoContent());
        editNumber(owner, contactId, "Bala", freshNumber());
        assertThat(contactJson(owner, contactId).get("inApp").asBoolean()).as("another number: the link ended").isFalse();
        assertThat(json(send(get("/api/circle/in"), member, null))).isEmpty();

        editNumber(owner, contactId, "Bala", member.phone());
        String again = json(send(get("/api/circle/requests"), member, null)).get(0).get("id").asText();
        send(post("/api/circle/requests/" + again + "/decline"), member, Map.of("dontAskAgain", true))
                .andExpect(status().isNoContent());
        send(delete("/api/safety-contacts/" + contactId), owner, null).andExpect(status().isNoContent());
        contact(owner, "Bala again", member.phone());
        assertThat(json(send(get("/api/circle/requests"), member, null))).as("don't ask again holds").isEmpty();
    }

    @Test
    void requestsAreLimitedPerOwner_silently() throws Exception {
        Person owner = person("Asha Devi");
        long last = 0;
        for (int i = 0; i < 10; i++) {
            String name = "Friend " + (char) ('A' + i);
            last = contact(owner, name, person(name).phone());
        }
        send(delete("/api/safety-contacts/" + last), owner, null).andExpect(status().isNoContent()); // the Circle holds 10
        Person eleventh = person("Late Friend");
        contact(owner, "Late", eleventh.phone());
        assertThat(json(send(get("/api/circle/requests"), eleventh, null))).isEmpty();
        assertThat(pushes(eleventh)).isEmpty();
    }

    // ---- the SOS -----------------------------------------------------------------------------------------------------

    @Test
    void anSos_tellsAnAcceptedMemberWithItsFirstText_theyCanReadIt_andTheEndSendsOneUpdate() throws Exception {
        Person owner = chosen("Asha Devi");
        Person member = person("Bala Murugan");
        Person pending = person("Chitra");
        accepted(owner, member);
        contact(owner, "Chitra", pending.phone()); // never accepted

        long sos = startSos(owner);
        dispatcher.runOnce();
        assertThat(kinds(member)).as("nothing before the first location or 20 s").doesNotContain("CIRCLE_SOS");
        send(get("/api/circle/alerts/" + sos), member, null).andExpect(status().isNotFound());

        location(owner, sos);
        dispatcher.runOnce();
        assertThat(sms.textsTo(member.phone())).hasSize(1);
        assertThat(pushes(member)).contains("CIRCLE_SOS " + sos);
        assertThat(kinds(pending)).doesNotContain("CIRCLE_SOS");

        JsonNode view = json(send(get("/api/circle/alerts/" + sos), member, null).andExpect(status().isOk()));
        assertThat(view.get("name").asText()).isEqualTo("Asha Devi");
        assertThat(view.get("phone").asText()).isEqualTo(owner.phone());
        assertThat(view.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(view.get("mapLink").asText()).isEqualTo("https://maps.google.com/?q=10.81234,78.69012");
        send(get("/api/circle/alerts/" + sos), pending, null).andExpect(status().isNotFound());
        send(get("/api/circle/alerts/" + sos), person("Stranger"), null).andExpect(status().isNotFound());
        send(get("/api/circle/alerts/" + sos), owner, null).andExpect(status().isNotFound());

        dispatcher.runOnce();
        assertThat(kinds(member).stream().filter("CIRCLE_SOS"::equals)).as("once per SOS").hasSize(1);

        end(owner, sos, "resolve");
        assertThat(kinds(member).stream().filter("CIRCLE_SOS"::equals)).as("one end update").hasSize(2);
        send(get("/api/circle/alerts/" + sos), member, null).andExpect(jsonPath("$.status").value("SAFE"));

        jdbc.update("update emergency_events set ended_at = ? where id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusHours(25), sos);
        send(get("/api/circle/alerts/" + sos), member, null).andExpect(status().isNotFound()); // 24 h after the end
    }

    @Test
    void aFalseAlarmBeforeTheFirstText_tellsTheMemberNothing_andAnSosEndedElsewhereSendsNoUpdate() throws Exception {
        Person owner = chosen("Asha Devi");
        Person member = person("Bala Murugan");
        accepted(owner, member);

        long falseAlarm = startSos(owner);
        end(owner, falseAlarm, "cancel");
        location(owner, startSos(owner)); // a second SOS for the elsewhere case below
        assertThat(kinds(member)).doesNotContain("CIRCLE_SOS");
        dispatcher.runOnce();
        long second = jdbc.queryForObject("select max(id) from emergency_events where user_id = ?", Long.class, owner.id());
        assertThat(pushes(member)).containsExactly("CIRCLE_REQUEST " + linkId(owner), "CIRCLE_SOS " + second);
        send(get("/api/circle/alerts/" + falseAlarm), member, null).andExpect(status().isNotFound());

        jdbc.update("update emergency_events set status = 'RESOLVED', ended_elsewhere = true, ended_at = ? where id = ?",
                LocalDateTime.now(ZoneOffset.UTC), second);
        queue.sosEnded(second);
        assertThat(kinds(member).stream().filter("CIRCLE_SOS"::equals)).as("ended elsewhere: no update").hasSize(1);
        send(get("/api/circle/alerts/" + second), member, null).andExpect(jsonPath("$.status").value("ENDED"));
    }

    // ---- On my way ---------------------------------------------------------------------------------------------------

    @Test
    void onMyWay_queuesTheDueTimeAndTheNudge_moreTimeReplacesThem_andImThereRemovesThem() throws Exception {
        Person walker = person("Asha Devi");
        JsonNode walk = json(send(post("/api/journeys"), walker, Map.of("checkInIntervalMinutes", 10))
                .andExpect(status().isCreated()));
        long id = walk.get("id").asLong();
        Instant due = Instant.parse(walk.get("nextCheckInDueAt").asText());
        assertThat(reminders(walker, id)).containsExactly(due, due.plus(Duration.ofMinutes(5)));

        JsonNode later = json(send(post("/api/journeys/" + id + "/check-ins"), walker, null).andExpect(status().isOk()));
        Instant newDue = Instant.parse(later.get("nextCheckInDueAt").asText());
        assertThat(reminders(walker, id)).containsExactly(newDue, newDue.plus(Duration.ofMinutes(5)));

        send(put("/api/journeys/" + id + "/complete"), walker, null).andExpect(status().isOk());
        assertThat(reminders(walker, id)).isEmpty();

        long second = json(send(post("/api/journeys"), walker, Map.of("checkInIntervalMinutes", 10))
                .andExpect(status().isCreated())).get("id").asLong();
        startSos(walker);
        assertThat(reminders(walker, second)).as("an SOS ends the walk and its reminders").isEmpty();
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    private Person chosen(String name) throws Exception {
        Person p = person(name);
        send(put("/api/safety-alerts"), p, Map.of("on", true)).andExpect(status().isOk());
        return p;
    }

    private void accepted(Person owner, Person member) throws Exception {
        contact(owner, "Member " + member.id(), member.phone());
        String id = json(send(get("/api/circle/requests"), member, null)).get(0).get("id").asText();
        send(post("/api/circle/requests/" + id + "/accept"), member, null).andExpect(status().isNoContent());
    }

    private String linkId(Person owner) {
        return jdbc.queryForObject("select public_id from circle_links where owner_user_id = ?", String.class, owner.id());
    }

    private long contact(Person p, String name, String phone) throws Exception {
        return json(send(post("/api/safety-contacts"), p, Map.of("name", name, "phoneNumber", phone,
                "relationship", "FRIEND", "priority", 1, "primary", false, "enabled", true))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private void editNumber(Person p, long id, String name, String phone) throws Exception {
        send(put("/api/safety-contacts/" + id), p, Map.of("name", name, "phoneNumber", phone,
                "relationship", "FRIEND", "priority", 1, "primary", false, "enabled", true)).andExpect(status().isOk());
    }

    private JsonNode contactJson(Person p, long id) throws Exception {
        return json(send(get("/api/safety-contacts/" + id), p, null).andExpect(status().isOk()));
    }

    private long startSos(Person p) throws Exception {
        return json(send(post("/api/emergencies"), p, null).andExpect(status().isCreated())).get("id").asLong();
    }

    private void location(Person p, long sos) throws Exception {
        send(post("/api/emergencies/" + sos + "/locations"), p, Map.of("points", List.of(Map.of(
                "latitude", 10.812341, "longitude", 78.690123, "accuracyMeters", 8.0, "source", "FUSED",
                "recordedAt", Instant.now().toString())))).andExpect(status().isCreated());
    }

    private void end(Person p, long sos, String how) throws Exception {
        send(put("/api/emergencies/" + sos + "/" + how), p, null).andExpect(status().isOk());
    }

    private List<String> pushes(Person p) {
        return jdbc.queryForList("select type || ' ' || subject from push_messages where user_id = ? order by id",
                String.class, p.id());
    }

    private List<String> kinds(Person p) {
        return pushes(p).stream().map(s -> s.split(" ")[0]).toList();
    }

    private List<Instant> reminders(Person p, long walk) {
        return jdbc.queryForList("select not_before from push_messages where user_id = ? and type = 'OMW_REMINDER'"
                        + " and subject = ? and status = 'QUEUED' order by not_before", Timestamp.class, p.id(),
                String.valueOf(walk)).stream().map(t -> t.toLocalDateTime().toInstant(ZoneOffset.UTC)).toList();
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Person p, Object body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer(p.token()));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(request);
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
