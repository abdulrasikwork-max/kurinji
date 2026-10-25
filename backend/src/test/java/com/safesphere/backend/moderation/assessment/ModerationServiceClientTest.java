package com.safesphere.backend.moderation.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.config.ModerationAiProperties;
import com.safesphere.backend.config.ModerationAiProperties.ModelMode;
import com.safesphere.backend.moderation.assessment.Evidence.Answer;
import com.safesphere.backend.moderation.assessment.Evidence.Category;
import com.safesphere.backend.support.StubModerationService;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Phase 13E: the one client of the moderation service: what it sends, its timeout, and fail-closed reading. */
class ModerationServiceClientTest {

    static StubModerationService stub;
    static ModerationServiceClient client;
    static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void start() {
        stub = new StubModerationService();
        client = new ModerationServiceClient(properties(stub.url(), StubModerationService.KEY), JSON);
    }

    @AfterAll
    static void stop() {
        stub.stop();
    }

    static ModerationAiProperties properties(String url, String key) {
        return new ModerationAiProperties(true, url, key, Duration.ofMillis(300), Duration.ofMillis(400),
                ModelMode.OFF, null, null, null, null);
    }

    @Test
    void sendsOnlyTheKindAndTheText_withTheKey() {
        Optional<Answer> answer = client.assess("POST", "a nice walk [INSULT]");
        assertThat(answer).isPresent();
        StubModerationService.Seen seen = stub.seen.get(stub.seen.size() - 1);
        assertThat(seen.fields()).containsExactlyInAnyOrder("kind", "text");
        assertThat(seen.key()).isEqualTo(StubModerationService.KEY);
        assertThat(answer.get().items()).singleElement().satisfies(e -> {
            assertThat(e.category()).isEqualTo(Category.HARASSMENT);
            assertThat(e.start()).isEqualTo(12);
            assertThat(e.end()).isEqualTo(20);
        });
        assertThat(answer.get().lexiconVersion()).isEqualTo("lex-1");
    }

    @Test
    void errors_timeouts_andGarbage_areUnavailable() {
        assertThat(client.assess("POST", "[500]")).isEmpty();
        assertThat(client.assess("POST", "[GARBAGE]")).isEmpty();
        long started = System.nanoTime();
        assertThat(client.assess("POST", "[SLOW]")).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1400));
        stub.down = true;
        try {
            assertThat(client.assess("POST", "hello")).isEmpty();
        } finally {
            stub.down = false;
        }
        assertThat(new ModerationServiceClient(properties("http://127.0.0.1:1", StubModerationService.KEY), JSON)
                .assess("POST", "hello")).isEmpty();
    }

    @Test
    void withoutAKey_itNeverCalls() {
        int before = stub.seen.size();
        assertThat(new ModerationServiceClient(properties(stub.url(), "short"), JSON).assess("POST", "hi")).isEmpty();
        assertThat(stub.seen).hasSize(before);
    }

    @Test
    void unknownCodes_impossibleNumbers_andBadVersions_failClosed() throws Exception {
        assertThat(client.assess("POST", "x [UNKNOWN]").orElseThrow().items()).isEmpty();
        String bad = "{\"versions\":{\"normaliser\":\"norm-1\",\"lexicon\":\"LEX 1\"},\"evidence\":[]}";
        assertThat(ModerationServiceClient.parse(JSON.readTree(bad), 10)).isEmpty();
        String odd = "{\"versions\":{\"normaliser\":\"norm-1\",\"lexicon\":\"lex-1\",\"model\":\"-\"},\"evidence\":["
                + "{\"category\":\"THREAT\",\"severity\":3,\"start\":0,\"end\":99},"
                + "{\"category\":\"THREAT\",\"severity\":\"3\",\"start\":0,\"end\":2},"
                + "{\"category\":\"THREAT\",\"severity\":3,\"start\":4,\"end\":2},"
                + "{\"category\":\"SELF_HARM\",\"severity\":3,\"start\":0,\"end\":2},"
                + "{\"category\":\"HATE\",\"severity\":3,\"targeted\":true,\"start\":0,\"end\":2}],"
                + "\"modelScores\":{\"HATE\":1.5,\"THREAT\":0.5,\"NOPE\":0.9}}";
        Answer answer = ModerationServiceClient.parse(JSON.readTree(odd), 10).orElseThrow();
        assertThat(answer.items()).singleElement().satisfies(e -> assertThat(e.category()).isEqualTo(Category.HATE));
        assertThat(answer.modelScores()).containsOnlyKeys(Category.THREAT);
        assertThat(answer.selfHarm()).isFalse();
    }
}
