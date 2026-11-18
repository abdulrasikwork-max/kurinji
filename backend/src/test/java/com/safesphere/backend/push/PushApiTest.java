package com.safesphere.backend.push;

import static com.safesphere.backend.support.TestAccounts.bearer;
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
import com.safesphere.backend.account.service.AccountEraser;
import com.safesphere.backend.push.entity.PushMessage;
import com.safesphere.backend.push.entity.PushType;
import com.safesphere.backend.push.repository.PushDeviceRepository;
import com.safesphere.backend.push.repository.PushMessageRepository;
import com.safesphere.backend.push.service.PushCleanupJob;
import com.safesphere.backend.push.service.PushDispatcher;
import com.safesphere.backend.push.service.PushSender;
import com.safesphere.backend.push.service.PushSender.Outcome;
import com.safesphere.backend.push.service.PushService;
import com.safesphere.backend.support.CapturingOtpSenderConfig;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.StubPushSenderConfig;
import com.safesphere.backend.support.StubPushSenderConfig.StubPushSender;
import com.safesphere.backend.support.TestAccounts;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * N7-B1 (D26-D35): a phone's token belongs to its sign-in; one pointer per running sign-in; switches, batching, retries,
 * expiry, an invalid token, signing out, the clean-up and account deletion; logs without tokens.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Import({StubPushSenderConfig.class, MutableClockConfig.class, CapturingOtpSenderConfig.class})
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:push-api;MODE=MySQL;DB_CLOSE_DELAY=-1")
class PushApiTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StubPushSender sender;
    @Autowired private PushService push;
    @Autowired private PushDispatcher dispatcher;
    @Autowired private PushCleanupJob cleanup;
    @Autowired private PushDeviceRepository devices;
    @Autowired private PushMessageRepository messages;
    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountEraser eraser;

    @BeforeEach
    void reset() {
        sender.reset();
        clock.reset();
        // One queue for the whole class: every test starts with an empty one (the dispatcher works ALL due rows).
        jdbc.update("delete from push_messages");
    }

    private record Person(long id, String key, String token, String refresh) {
    }

    private Person person() throws Exception {
        String key = "push-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID();
        JsonNode answer = registerVerifiedAndLogin(mockMvc, objectMapper, "Asha Devi", key);
        return new Person(answer.get("user").get("id").asLong(), key, answer.get("accessToken").asText(),
                answer.get("refreshToken").asText());
    }

    /** The same person signed in on a second phone. */
    private Person secondPhone(Person p) throws Exception {
        JsonNode session = TestAccounts.signIn(mockMvc, objectMapper, p.key(), null);
        return new Person(p.id(), p.key(), session.get("accessToken").asText(), session.get("refreshToken").asText());
    }

    private static String fcmToken() {
        return "dGVzdA:APA91b" + UUID.randomUUID().toString().replace("-", "") + "x".repeat(80);
    }

    private void register(Person p, String fcmToken) throws Exception {
        mockMvc.perform(put("/api/push/device").header(HttpHeaders.AUTHORIZATION, bearer(p.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("token", fcmToken, "appVersion", "1.0.0"))))
                .andExpect(status().isNoContent());
    }

    private static String subject() {
        return UUID.randomUUID().toString();
    }

    private List<PushMessage> rows(long userId) {
        return messages.findByUserIdOrderByIdAsc(userId);
    }

    @Test
    void registering_needsASignIn_andAValidToken_andBindsTheTokenToThisSignInOnly() throws Exception {
        mockMvc.perform(put("/api/push/device").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("token", fcmToken()))))
                .andExpect(status().isUnauthorized());
        Person p = person();
        mockMvc.perform(put("/api/push/device").header(HttpHeaders.AUTHORIZATION, bearer(p.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("token", "not a token"))))
                .andExpect(status().isBadRequest());
        String token = fcmToken();
        register(p, token);
        register(p, token); // the same token again changes nothing
        assertThat(jdbc.queryForObject("select count(*) from push_devices where user_id = ?", Long.class, p.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select token_hash from push_devices where user_id = ?", String.class, p.id()))
                .matches("^[0-9a-f]{64}$");
        mockMvc.perform(delete("/api/push/device").header(HttpHeaders.AUTHORIZATION, bearer(p.token())))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from push_devices where user_id = ?", Long.class, p.id())).isZero();
    }

    @Test
    void settings_areTheCallersOwn_withDefaultsOnExceptStaff() throws Exception {
        Person a = person();
        Person b = person();
        mockMvc.perform(get("/api/push/settings").header(HttpHeaders.AUTHORIZATION, bearer(a.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replies").value(true)).andExpect(jsonPath("$.plans").value(true))
                .andExpect(jsonPath("$.planMessages").value(true)).andExpect(jsonPath("$.people").value(true))
                .andExpect(jsonPath("$.staff").value(false));
        mockMvc.perform(put("/api/push/settings").header(HttpHeaders.AUTHORIZATION, bearer(a.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("replies", false, "plans", true, "planMessages", false,
                                "people", true, "staff", false))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replies").value(false));
        mockMvc.perform(get("/api/push/settings").header(HttpHeaders.AUTHORIZATION, bearer(b.token())))
                .andExpect(jsonPath("$.replies").value(true));

        register(a, fcmToken());
        assertThat(push.notify(a.id(), PushType.REPLY_TURN, subject())).as("switched off: not even queued").isFalse();
        assertThat(push.notify(a.id(), PushType.PLAN_MESSAGE, subject())).isFalse();
        assertThat(push.notify(a.id(), PushType.PLAN_ADDED, subject())).isTrue();
        assertThat(push.notify(a.id(), PushType.CIRCLE_SOS, subject())).as("safety is never switched off in IRL").isTrue();
        assertThat(push.notify(a.id(), PushType.STAFF_WAITING, subject())).as("staff is off by default").isFalse();
    }

    @Test
    void onePointerReachesEveryRunningSignIn_withTheSameEventId_andNothingButTheTypeAndIds(CapturedOutput output)
            throws Exception {
        Person phone1 = person();
        Person phone2 = secondPhone(phone1);
        Person stranger = person();
        String t1 = fcmToken();
        String t2 = fcmToken();
        register(phone1, t1);
        register(phone2, t2);
        register(stranger, fcmToken());
        String about = subject();

        assertThat(push.notify(phone1.id(), PushType.REPLY_TURN, about)).isTrue();
        List<PushMessage> queued = rows(phone1.id());
        assertThat(queued).hasSize(2);
        assertThat(queued.get(0).getEventId()).isEqualTo(queued.get(1).getEventId());
        assertThat(dispatcher.runOnce()).isEqualTo(2);

        List<PushSender.Push> sent = sender.sent();
        assertThat(sent).extracting(PushSender.Push::token).containsExactlyInAnyOrder(t1, t2);
        for (PushSender.Push p : sent) {
            assertThat(p.data()).containsOnlyKeys("t", "s", "m", "v");
            assertThat(p.data()).containsEntry("t", "REPLY_TURN").containsEntry("s", about)
                    .containsEntry("m", queued.get(0).getEventId());
            assertThat(p.collapseKey()).isEqualTo("reply-" + about);
            assertThat(p.highPriority()).isFalse();
        }
        assertThat(rows(phone1.id())).allMatch(m -> m.getStatus() == PushMessage.Status.SENT && m.getSentAt() != null);
        assertThat(rows(stranger.id())).isEmpty();
        assertThat(output.getAll()).doesNotContain(t1).doesNotContain(t2).doesNotContain(about)
                .contains("Pushes: 2 accepted");
    }

    @Test
    void aTokenMovesToTheNewSignIn_soAHandedOverPhoneNeverGetsTheFirstPersonsPushes() throws Exception {
        Person first = person();
        Person second = person();
        String shared = fcmToken();
        register(first, shared);
        register(second, shared);
        assertThat(jdbc.queryForObject("select count(*) from push_devices where user_id = ?", Long.class, first.id())).isZero();
        assertThat(push.notify(first.id(), PushType.REPLY_TURN, subject())).isFalse();
        assertThat(push.notify(second.id(), PushType.REPLY_TURN, subject())).isTrue();
    }

    @Test
    void severalImInWithinTheWindow_becomeOnePush_sentWhenTheWindowEnds() throws Exception {
        Person author = person();
        register(author, fcmToken());
        String plan = subject();
        assertThat(push.notify(author.id(), PushType.PLAN_ASK, plan)).isTrue();
        assertThat(push.notify(author.id(), PushType.PLAN_ASK, plan)).as("batched into the waiting one").isFalse();
        assertThat(dispatcher.runOnce()).as("waits for the window").isZero();
        clock.advance(Duration.ofMinutes(2).plusSeconds(1));
        assertThat(dispatcher.runOnce()).isEqualTo(1);
        assertThat(push.notify(author.id(), PushType.PLAN_ASK, plan)).as("a new window after it went out").isTrue();
    }

    @Test
    void anInvalidTokenRemovesThePhone_retriesBackOff_andAMessageNeverGoesOutLate() throws Exception {
        Person p = person();
        register(p, fcmToken());
        push.notify(p.id(), PushType.REPLY_TURN, subject());
        sender.answer(Outcome.of(PushSender.Result.UNREGISTERED, "404/UNREGISTERED"));
        dispatcher.runOnce();
        assertThat(devices.findActiveByUserId(p.id(), LocalDateTime.now(ZoneOffset.UTC))).isEmpty();
        assertThat(push.notify(p.id(), PushType.REPLY_TURN, subject())).isFalse();

        register(p, fcmToken());
        push.notify(p.id(), PushType.MORE_ADDED, subject());
        for (int i = 0; i < 5; i++) sender.answer(Outcome.of(PushSender.Result.RETRY, "503/UNAVAILABLE"));
        for (int i = 0; i < 5; i++) {
            dispatcher.runOnce();
            clock.advance(Duration.ofMinutes(11));
        }
        PushMessage gaveUp = rows(p.id()).stream().filter(m -> m.getType() == PushType.MORE_ADDED).findFirst().orElseThrow();
        assertThat(gaveUp.getStatus()).isEqualTo(PushMessage.Status.FAILED);
        assertThat(gaveUp.getSkipReason()).isEqualTo("GAVE_UP");
        assertThat(sender.sent()).isEmpty();

        clock.reset();
        push.notify(p.id(), PushType.OMW_REMINDER, "42");
        clock.advance(Duration.ofMinutes(11));
        assertThat(dispatcher.runOnce()).isZero();
        PushMessage late = rows(p.id()).stream().filter(m -> m.getType() == PushType.OMW_REMINDER).findFirst().orElseThrow();
        assertThat(late.getStatus()).isEqualTo(PushMessage.Status.EXPIRED);
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void signingOut_stopsPushes_evenOnesAlreadyQueued_andTheCleanUpRemovesThePhone() throws Exception {
        Person p = person();
        register(p, fcmToken());
        push.notify(p.id(), PushType.REPLY_TURN, subject());
        mockMvc.perform(post("/api/auth/sign-out").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", p.refresh()))))
                .andExpect(status().isNoContent());
        assertThat(dispatcher.runOnce()).isZero();
        assertThat(rows(p.id()).get(0).getStatus()).isEqualTo(PushMessage.Status.SKIPPED);
        assertThat(rows(p.id()).get(0).getSkipReason()).isEqualTo("SESSION_ENDED");
        assertThat(push.notify(p.id(), PushType.REPLY_TURN, subject())).isFalse();

        assertThat(cleanup.run()[0]).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from push_devices where user_id = ?", Long.class, p.id())).isZero();
        clock.advance(Duration.ofDays(8));
        cleanup.run();
        assertThat(rows(p.id())).isEmpty();
    }

    @Test
    void onMyWayPushesCanWaitForTheirTime_andBeForgottenWhenTheWalkEnds() throws Exception {
        Person p = person();
        register(p, fcmToken());
        push.notifyAt(p.id(), PushType.OMW_REMINDER, "77", clock.instant().plus(Duration.ofMinutes(30)));
        assertThat(dispatcher.runOnce()).isZero();
        assertThat(push.forget(p.id(), PushType.OMW_REMINDER, "77")).isEqualTo(1);
        clock.advance(Duration.ofMinutes(31));
        assertThat(dispatcher.runOnce()).isZero();
        push.notifyAt(p.id(), PushType.OMW_REMINDER, "78", clock.instant().plus(Duration.ofMinutes(5)));
        clock.advance(Duration.ofMinutes(5));
        assertThat(dispatcher.runOnce()).isEqualTo(1);
        assertThat(sender.sent().get(0).highPriority()).isTrue();
    }

    @Test
    void erasingAnAccount_removesItsPhonesQueueAndSwitches() throws Exception {
        Person p = person();
        register(p, fcmToken());
        push.notify(p.id(), PushType.REPLY_TURN, subject());
        mockMvc.perform(put("/api/push/settings").header(HttpHeaders.AUTHORIZATION, bearer(p.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("replies", true, "plans", true, "planMessages", true,
                        "people", true, "staff", false)))).andExpect(status().isOk());
        jdbc.update("update users set deletion_requested_at = ?, deletion_due_at = ? where id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(8), LocalDateTime.now(ZoneOffset.UTC).minusDays(1), p.id());
        assertThat(eraser.eraseIfDue(p.id())).isIn(AccountEraser.Outcome.DELETED, AccountEraser.Outcome.ANONYMISED);
        for (String table : List.of("push_devices", "push_messages", "push_settings")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where user_id = ?", Long.class, p.id()))
                    .as(table).isZero();
        }
    }
}
