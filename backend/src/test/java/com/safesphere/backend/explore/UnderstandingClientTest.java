package com.safesphere.backend.explore;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.config.ExploreUnderstandingProperties;
import com.safesphere.backend.config.ExploreUnderstandingProperties.Mode;
import com.safesphere.backend.explore.UnderstandingClient.Off;
import com.safesphere.backend.explore.UnderstandingClient.Outcome;
import com.safesphere.backend.explore.UnderstandingClient.Unavailable;
import com.safesphere.backend.explore.UnderstandingClient.Understood;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Phase 17G-3: the backend's only client of the understanding, against a local stub of the internal service. */
class UnderstandingClientTest {

    private static final String KEY = "internal-key-0123456789";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static StubUnderstandingService stub;

    @BeforeAll
    static void start() {
        stub = new StubUnderstandingService();
    }

    @AfterAll
    static void stop() {
        stub.close();
    }

    @BeforeEach
    void reset() {
        stub.reset();
    }

    private UnderstandingClient client(String url, Duration timeout) {
        return new UnderstandingClient(new ExploreUnderstandingProperties(Mode.ON, url, KEY, Duration.ofMillis(300), timeout,
                20, 300, Duration.ofMinutes(10), null), JSON);
    }

    private UnderstandingClient client() {
        return client(stub.url(), Duration.ofSeconds(2));
    }

    @Test
    void aGoodAnswer_becomesTheChips_andOnlyTheTextAndTheKeyAreSent() {
        stub.body = "{\"categories\":[\"FOOD_CAFES\"],\"facets\":[\"QUIET\",\"BUDGET_FRIENDLY\"],\"daypart\":\"EVENING\"}";
        Outcome outcome = client().understand("quiet cheap cafe for the evening");
        assertThat(outcome).isEqualTo(new Understood(List.of("FOOD_CAFES"), List.of("QUIET", "BUDGET_FRIENDLY"), "EVENING"));
        assertThat(stub.received).hasSize(1);
        assertThat(stub.received.get(0).key()).isEqualTo(KEY);
        assertThat(stub.received.get(0).body()).isEqualTo("{\"text\":\"quiet cheap cafe for the evening\"}");
        assertThat(stub.received.get(0).path()).isEqualTo("/v1/understand");
    }

    @Test
    void theAnswerIsReadAgainstTheClosedLists_unknownDropped_duplicatesRemoved_capped() {
        stub.body = "{\"categories\":[\"NOPE\",\"FOOD_CAFES\",\"FOOD_CAFES\",\"HIDDEN_GEMS\",\"CITY_LIFE\"],"
                + "\"facets\":[\"safe_place\",\"QUIET\",\"LIVELY\",\"SCENIC\",\"OPEN_LATE\",\"GOOD_ALONE\",7,null],"
                + "\"daypart\":\"MIDNIGHT\"}";
        Understood understood = (Understood) client().understand("x");
        assertThat(understood.categories()).containsExactly("FOOD_CAFES", "HIDDEN_GEMS");
        assertThat(understood.facets()).containsExactly("QUIET", "LIVELY", "SCENIC", "OPEN_LATE");
        assertThat(understood.daypart()).isNull();
    }

    @Test
    void lowerCaseOrPaddedCodes_areNotAccepted() {
        stub.body = "{\"categories\":[\"food_cafes\"],\"facets\":[\" QUIET\"],\"daypart\":\"evening\"}";
        Understood understood = (Understood) client().understand("x");
        assertThat(understood).isEqualTo(new Understood(List.of(), List.of(), null));
    }

    @Test
    void anAnswerThatIsNotAnObject_isUnavailable() {
        for (String junk : List.of("[]", "\"text\"", "null", "42", "not json", "")) {
            stub.body = junk;
            assertThat(client().understand("x")).as(junk).isInstanceOf(Unavailable.class);
        }
    }

    @Test
    void theServiceSayingOff_isOff_andAnyOtherRefusalIsUnavailable() {
        stub.status = 503;
        stub.body = "{\"error\":\"off\"}";
        assertThat(client().understand("x")).isInstanceOf(Off.class);
        for (int code : List.of(400, 401, 404, 429, 500, 502, 503)) {
            stub.status = code;
            stub.body = code == 503 ? "{\"error\":\"busy\"}" : "{\"error\":\"unavailable\"}";
            assertThat(client().understand("x")).as("status " + code).isInstanceOf(Unavailable.class);
        }
    }

    @Test
    void aSlowService_isUnavailable_notWaitedFor() {
        stub.delayMillis = 1500;
        long started = System.nanoTime();
        assertThat(client(stub.url(), Duration.ofMillis(300)).understand("x")).isInstanceOf(Unavailable.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1200));
    }

    @Test
    void aServiceThatIsNotThere_isUnavailable() {
        assertThat(client("http://127.0.0.1:1", Duration.ofSeconds(1)).understand("x")).isInstanceOf(Unavailable.class);
    }

    @Test
    void theHealthProbe_saysWhetherTheServiceHasAProviderKey() {
        assertThat(client().serviceHasKey()).isTrue();
        stub.health = "{\"status\":\"UP\",\"understanding\":\"OFF\"}";
        assertThat(client().serviceHasKey()).isFalse();
        stub.health = "not json";
        assertThat(client().serviceHasKey()).isFalse();
        assertThat(client("http://127.0.0.1:1", Duration.ofSeconds(1)).serviceHasKey()).isFalse();
    }
}
