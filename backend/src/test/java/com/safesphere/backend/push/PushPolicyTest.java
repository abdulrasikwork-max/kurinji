package com.safesphere.backend.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.safesphere.backend.push.entity.PushSetting;
import com.safesphere.backend.push.entity.PushType;
import com.safesphere.backend.push.service.PushPolicy;
import com.safesphere.backend.push.service.PushSender.Push;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** N7 (D26, D29-D31, D35): the pure push rules, the closed list of kinds and the pointer payload. */
class PushPolicyTest {

    @Test
    void theKindsAreTheApprovedFourteen_andTheMigrationAllowsExactlyThem() throws Exception {
        assertThat(Arrays.stream(PushType.values()).map(Enum::name)).containsExactly(
                "PLAN_ASK", "PLAN_ADDED", "PLAN_MESSAGE", "PLAN_CALLED_OFF", "REPLY_TURN", "MORE_ADDED", "PEOPLE_REQUEST",
                "ACCOUNT_SIGN_IN", "MEMBERSHIP_DECIDED", "CONTENT_DECIDED", "CIRCLE_REQUEST", "CIRCLE_SOS", "OMW_REMINDER",
                "STAFF_WAITING");
        String v38 = Files.readString(Path.of("src/main/resources/db/migration/V38__n7_push_and_circle_links.sql"),
                StandardCharsets.UTF_8);
        Matcher check = Pattern.compile("ck_push_messages_type CHECK \\(type IN \\(([^)]*)\\)\\)", Pattern.DOTALL).matcher(v38);
        assertThat(check.find()).isTrue();
        assertThat(Arrays.stream(check.group(1).split(",")).map(s -> s.trim().replace("'", "")))
                .containsExactly(Arrays.stream(PushType.values()).map(Enum::name).toArray(String[]::new));
        // Never: nothing about Same, Say more, new moments, the daily question, digests or coming back.
        for (PushType type : PushType.values()) {
            assertThat(java.util.List.of(type.name().split("_"))).doesNotContain("SAME", "SAY", "TODAY", "QUESTION",
                    "DIGEST", "MISS", "COUNT", "NEW", "TRENDING");
        }
    }

    @Test
    void onlySosAndOnMyWayAreHighPriority_andEachKindLivesOnlyAsLongAsItIsUseful() {
        for (PushType type : PushType.values()) {
            assertThat(type.highPriority()).as(type.name()).isEqualTo(type == PushType.CIRCLE_SOS || type == PushType.OMW_REMINDER);
        }
        assertThat(PushType.CIRCLE_SOS.lifetime()).isEqualTo(Duration.ofMinutes(30));
        assertThat(PushType.OMW_REMINDER.lifetime()).isEqualTo(Duration.ofMinutes(10));
        assertThat(PushType.PLAN_MESSAGE.lifetime()).isEqualTo(Duration.ofHours(24));
        assertThat(PushPolicy.SOCIAL).containsExactlyInAnyOrder(PushType.PLAN_ASK, PushType.PLAN_ADDED,
                PushType.PLAN_MESSAGE, PushType.PLAN_CALLED_OFF, PushType.REPLY_TURN, PushType.MORE_ADDED,
                PushType.PEOPLE_REQUEST);
    }

    @Test
    void switches_defaultsAreOnExceptStaff_andSafetyAndAccountCannotBeSwitchedOff() {
        for (PushType type : PushType.values()) {
            assertThat(PushPolicy.switchedOn(type, null)).as(type.name()).isEqualTo(type != PushType.STAFF_WAITING);
        }
        PushSetting allOff = new PushSetting(1L, LocalDateTime.now());
        allOff.change(false, false, false, false, false, LocalDateTime.now());
        for (PushType type : PushType.values()) {
            boolean expected = type.toggle() == PushType.Switch.NONE;
            assertThat(PushPolicy.switchedOn(type, allOff)).as(type.name()).isEqualTo(expected);
        }
        PushSetting onlyMessagesOff = new PushSetting(1L, LocalDateTime.now());
        onlyMessagesOff.change(true, true, false, true, false, LocalDateTime.now());
        assertThat(PushPolicy.switchedOn(PushType.PLAN_MESSAGE, onlyMessagesOff)).isFalse();
        assertThat(PushPolicy.switchedOn(PushType.PLAN_ADDED, onlyMessagesOff)).isTrue();
    }

    @Test
    void antiNoise_batchingCapsAndRetries() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 18, 10, 0);
        assertThat(PushPolicy.notBefore(PushType.PLAN_ASK, now, Duration.ofMinutes(2))).isEqualTo(now.plusMinutes(2));
        assertThat(PushPolicy.notBefore(PushType.REPLY_TURN, now, Duration.ofMinutes(2))).isEqualTo(now);
        assertThat(PushPolicy.capReached(5, 19, 6, 20)).isFalse();
        assertThat(PushPolicy.capReached(6, 6, 6, 20)).isTrue();
        assertThat(PushPolicy.capReached(0, 20, 6, 20)).isTrue();
        assertThat(PushPolicy.retryDelay(1, Duration.ofSeconds(20), null)).isEqualTo(Duration.ofSeconds(20));
        assertThat(PushPolicy.retryDelay(3, Duration.ofSeconds(20), null)).isEqualTo(Duration.ofSeconds(60));
        assertThat(PushPolicy.retryDelay(1, Duration.ofSeconds(20), Duration.ofSeconds(45))).isEqualTo(Duration.ofSeconds(45));
        assertThat(PushPolicy.retryDelay(50, Duration.ofSeconds(20), null)).isEqualTo(Duration.ofMinutes(10));
        assertThat(PushPolicy.ttlLeft(now, now.plusMinutes(5))).isEqualTo(300);
        assertThat(PushPolicy.ttlLeft(now, now)).isEqualTo(1);
        assertThat(PushPolicy.collapseKey(PushType.PLAN_MESSAGE, "abc")).isEqualTo("plan-abc");
        assertThat(PushPolicy.collapseKey(PushType.PLAN_ASK, "abc")).as("one thread per plan").isEqualTo("plan-abc");
    }

    @Test
    void thePayloadIsAPointer_typeSubjectEventVersion_andNothingElse() {
        Push push = new Push("tok", PushType.REPLY_TURN, "0a1b2c3d-0000-4000-8000-00000000000c",
                "11111111-2222-4333-8444-555555555555", false, 3600, "reply-x");
        assertThat(push.data()).containsOnlyKeys("t", "s", "m", "v");
        assertThat(push.data()).containsEntry("t", "REPLY_TURN").containsEntry("v", "1");
        assertThat(push.toString()).doesNotContain("tok");
        PushPolicy.requireSubject("123");
        PushPolicy.requireSubject("0a1b2c3d-0000-4000-8000-00000000000c");
        for (String bad : new String[] {null, "", "Asha Devi", "hello world", "a/b", "x".repeat(65), "chai at 4?"}) {
            assertThatThrownBy(() -> PushPolicy.requireSubject(bad)).as(String.valueOf(bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(PushPolicy.TOKEN.matcher("dGVzdA:APA91b" + "x".repeat(100)).matches()).isTrue();
        assertThat(PushPolicy.TOKEN.matcher("short").matches()).isFalse();
        assertThat(PushPolicy.TOKEN.matcher("x".repeat(40) + " <script>").matches()).isFalse();
    }
}
