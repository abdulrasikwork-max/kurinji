package com.safesphere.backend.explore;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.config.ExploreUnderstandingProperties;
import com.safesphere.backend.config.ExploreUnderstandingProperties.Mode;
import com.safesphere.backend.explore.UnderstandingGate.Verdict;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Phase 17G-3: our own caps and the breaker, with a fake clock (nothing is called). */
class UnderstandingGateTest {

    static final class FakeClock extends Clock {
        Instant now = Instant.parse("2026-11-03T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final FakeClock clock = new FakeClock();

    private UnderstandingGate gate(int perUser, int total) {
        return new UnderstandingGate(new ExploreUnderstandingProperties(Mode.ON, null, "k".repeat(16), null, null, perUser,
                total, Duration.ofMinutes(10), null), clock);
    }

    @Test
    void aPersonIsAllowedTheirDailyCap_thenRefused_andOthersAreUnaffected() {
        UnderstandingGate gate = gate(3, 100);
        for (int i = 0; i < 3; i++) assertThat(gate.tryAcquire(1)).isEqualTo(Verdict.ALLOWED);
        assertThat(gate.tryAcquire(1)).isEqualTo(Verdict.USER_CAP);
        assertThat(gate.tryAcquire(1)).as("a refusal counts nothing and stays refused").isEqualTo(Verdict.USER_CAP);
        assertThat(gate.tryAcquire(2)).isEqualTo(Verdict.ALLOWED);
    }

    @Test
    void theGlobalCap_stopsEveryone() {
        UnderstandingGate gate = gate(10, 4);
        for (int user = 1; user <= 4; user++) assertThat(gate.tryAcquire(user)).isEqualTo(Verdict.ALLOWED);
        assertThat(gate.tryAcquire(5)).isEqualTo(Verdict.GLOBAL_CAP);
        assertThat(gate.tryAcquire(1)).isEqualTo(Verdict.GLOBAL_CAP);
    }

    @Test
    void theCountersStartAgainOnTheNextUtcDay() {
        UnderstandingGate gate = gate(1, 1);
        assertThat(gate.tryAcquire(1)).isEqualTo(Verdict.ALLOWED);
        assertThat(gate.tryAcquire(1)).isEqualTo(Verdict.GLOBAL_CAP);
        clock.now = Instant.parse("2026-11-03T23:59:59Z");
        assertThat(gate.tryAcquire(1)).as("still the same day").isEqualTo(Verdict.GLOBAL_CAP);
        clock.now = Instant.parse("2026-11-04T00:00:01Z");
        assertThat(gate.tryAcquire(1)).isEqualTo(Verdict.ALLOWED);
    }

    @Test
    void aFailureOpensTheBreakerForTenMinutes_thenOneCallIsAllowedAgain() {
        UnderstandingGate gate = gate(20, 300);
        assertThat(gate.tryAcquire(1)).isEqualTo(Verdict.ALLOWED);
        gate.failed();
        assertThat(gate.paused()).isTrue();
        assertThat(gate.tryAcquire(1)).isEqualTo(Verdict.PAUSED);
        assertThat(gate.tryAcquire(2)).as("for everyone").isEqualTo(Verdict.PAUSED);
        clock.now = clock.now.plus(Duration.ofMinutes(9));
        assertThat(gate.tryAcquire(1)).isEqualTo(Verdict.PAUSED);
        clock.now = clock.now.plus(Duration.ofMinutes(2));
        assertThat(gate.paused()).isFalse();
        assertThat(gate.tryAcquire(1)).isEqualTo(Verdict.ALLOWED);
        gate.failed();
        assertThat(gate.tryAcquire(1)).as("a second failure pauses again").isEqualTo(Verdict.PAUSED);
    }

    @Test
    void pausedCallsAreNotCounted() {
        UnderstandingGate gate = gate(1, 300);
        gate.failed();
        for (int i = 0; i < 5; i++) assertThat(gate.tryAcquire(1)).isEqualTo(Verdict.PAUSED);
        clock.now = clock.now.plus(Duration.ofMinutes(11));
        assertThat(gate.tryAcquire(1)).as("the person's one call of the day is still unused").isEqualTo(Verdict.ALLOWED);
    }

    @Test
    void aZeroCap_refusesEverything() {
        assertThat(gate(0, 300).tryAcquire(1)).isEqualTo(Verdict.USER_CAP);
        assertThat(gate(20, 0).tryAcquire(1)).isEqualTo(Verdict.GLOBAL_CAP);
    }
}
