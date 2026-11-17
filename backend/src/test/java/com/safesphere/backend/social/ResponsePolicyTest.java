package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.social.entity.MomentEnums.Kind;
import com.safesphere.backend.social.entity.ResponseEnums.SignalKind;
import com.safesphere.backend.social.policy.ResponsePolicy;
import com.safesphere.backend.social.policy.ResponsePolicy.Band;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** Phase N5: the locked rules of responses and plans at their edges (D14, D16, D18). */
class ResponsePolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 17, 10, 0);

    @Test
    void theSameBand_isAWordForTheOtherPeople_neverANumber() {
        assertThat(ResponsePolicy.band(0)).isNull();
        assertThat(ResponsePolicy.band(-1)).isNull();
        assertThat(ResponsePolicy.band(1)).isEqualTo(Band.SOMEONE);
        assertThat(ResponsePolicy.band(2)).isEqualTo(Band.A_FEW);
        assertThat(ResponsePolicy.band(5)).isEqualTo(Band.A_FEW);
        assertThat(ResponsePolicy.band(6)).isEqualTo(Band.MANY);
        assertThat(ResponsePolicy.band(10_000)).isEqualTo(Band.MANY);
    }

    @Test
    void sameAndSayMore_areOnASayOnly() {
        for (SignalKind signal : SignalKind.values()) {
            assertThat(ResponsePolicy.fits(signal, Kind.SAY)).isTrue();
            assertThat(ResponsePolicy.fits(signal, Kind.WHOS_IN)).isFalse();
        }
    }

    @Test
    void asks_closeAtThePlanTime_whenCalledOff_andWhenTheMomentLeavesToday() {
        assertThat(ResponsePolicy.asksOpen(true, false, NOW.plusMinutes(1), NOW)).isTrue();
        assertThat(ResponsePolicy.asksOpen(true, false, NOW, NOW)).isFalse();            // the time has come
        assertThat(ResponsePolicy.asksOpen(true, false, NOW.minusMinutes(1), NOW)).isFalse();
        assertThat(ResponsePolicy.asksOpen(true, false, null, NOW)).isTrue();            // no time: while on Today
        assertThat(ResponsePolicy.asksOpen(false, false, null, NOW)).isFalse();
        assertThat(ResponsePolicy.asksOpen(true, true, NOW.plusHours(2), NOW)).isFalse();
    }

    @Test
    void aPlan_closesADayAfterItsTime_orADayAfterTheMomentLeavesToday_orAtOnceWhenCalledOff() {
        LocalDateTime plan = NOW.plusHours(3);
        LocalDateTime end = NOW.plusHours(20);
        assertThat(ResponsePolicy.planClosesAt(plan, end, null)).isEqualTo(plan.plusHours(24));
        assertThat(ResponsePolicy.planClosesAt(null, end, null)).isEqualTo(end.plusHours(24));
        assertThat(ResponsePolicy.planClosesAt(plan, end, NOW)).isEqualTo(NOW);
        assertThat(ResponsePolicy.replyClosesAt(end)).isEqualTo(end.plusHours(24));
    }

    @Test
    void thisHappened_opensAtThePlanTime_andEndsWhenThePlanCloses() {
        LocalDateTime closes = NOW.plusHours(24);
        assertThat(ResponsePolicy.happenedOpen(NOW.plusMinutes(1), closes, false, NOW)).isFalse();
        assertThat(ResponsePolicy.happenedOpen(NOW, closes, false, NOW)).isTrue();
        assertThat(ResponsePolicy.happenedOpen(null, closes, false, NOW)).isTrue();
        assertThat(ResponsePolicy.happenedOpen(NOW.minusHours(1), NOW, false, NOW)).isFalse();   // closed exactly now
        assertThat(ResponsePolicy.happenedOpen(NOW.minusHours(1), closes, true, NOW)).isFalse(); // called off
    }
}
