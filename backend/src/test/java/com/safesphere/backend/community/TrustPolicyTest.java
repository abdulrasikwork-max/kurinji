package com.safesphere.backend.community;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.safesphere.backend.community.service.TrustPolicy;
import com.safesphere.backend.community.service.TrustPolicy.Standing;
import com.safesphere.backend.community.service.TrustPolicy.Tier;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TrustPolicyTest {

    private static final Duration OLD = Duration.ofDays(45);
    private static final Duration YOUNG = Duration.ofDays(5);

    private static Standing standing(int corroborated, int refuted, int strikes) {
        return new Standing(corroborated, refuted, strikes, 0, 0, false);
    }

    @Test
    void everyoneStartsStandard() {
        assertEquals(Tier.STANDARD, TrustPolicy.tier(Standing.NEW, Duration.ZERO, true));
        assertEquals(2, Tier.STANDARD.weight());
    }

    @Test
    void restrictedOrDisabled_countsForNothing() {
        assertEquals(Tier.RESTRICTED, TrustPolicy.tier(new Standing(9, 0, 0, 0, 0, true), OLD, true));
        assertEquals(Tier.RESTRICTED, TrustPolicy.tier(standing(9, 0, 0), OLD, false));
        assertEquals(0, Tier.RESTRICTED.weight());
    }

    @Test
    void trusted_needsAgeCorroborationAndAGoodRatio() {
        assertEquals(Tier.TRUSTED, TrustPolicy.tier(standing(3, 1, 0), OLD, true));
        assertEquals(Tier.STANDARD, TrustPolicy.tier(standing(3, 1, 0), YOUNG, true), "30 days first");
        assertEquals(Tier.STANDARD, TrustPolicy.tier(standing(2, 0, 0), OLD, true), "3 corroborated reports first");
        assertEquals(Tier.STANDARD, TrustPolicy.tier(standing(5, 2, 0), OLD, true), "corroborated >= 3 x refuted");
    }

    @Test
    void aStrike_orRepeatedUnbackedReports_limit() {
        assertEquals(Tier.LIMITED, TrustPolicy.tier(standing(9, 0, 1), OLD, true), "a strike beats any history");
        assertEquals(Tier.LIMITED, TrustPolicy.tier(standing(1, 3, 0), OLD, true));
        assertEquals(Tier.STANDARD, TrustPolicy.tier(standing(3, 3, 0), OLD, true), "not more refuted than backed");
    }

    @Test
    void flagsOfSomeoneModeratorsKeepRejecting_weighHalf() {
        assertEquals(2, TrustPolicy.flagWeight(Tier.STANDARD, Standing.NEW));
        assertEquals(1, TrustPolicy.flagWeight(Tier.STANDARD, new Standing(0, 0, 0, 1, 3, false)));
        assertEquals(2, TrustPolicy.flagWeight(Tier.STANDARD, new Standing(0, 0, 0, 2, 3, false)));
        assertEquals(0, TrustPolicy.flagWeight(Tier.RESTRICTED, Standing.NEW));
    }
}
