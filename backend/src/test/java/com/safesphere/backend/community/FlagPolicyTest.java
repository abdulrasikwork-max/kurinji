package com.safesphere.backend.community;

import static com.safesphere.backend.community.entity.ContributionKind.CONFIRM;
import static com.safesphere.backend.community.entity.ContributionKind.REPORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.safesphere.backend.community.entity.FlagReason;
import com.safesphere.backend.community.entity.SignalCategory;
import com.safesphere.backend.community.service.FlagPolicy;
import com.safesphere.backend.community.service.FlagPolicy.FlagVote;
import com.safesphere.backend.community.service.FlagPolicy.Outcome;
import com.safesphere.backend.community.service.Independence.Linkage;
import com.safesphere.backend.community.service.SignalPolicy;
import com.safesphere.backend.community.service.SignalPolicy.Assessment;
import com.safesphere.backend.community.service.SignalPolicy.Vote;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlagPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 18, 0);

    private static Assessment supportedBy(int people) {
        List<Vote> votes = new ArrayList<>();
        for (int i = 0; i < people; i++) votes.add(new Vote(i == 0 ? REPORT : CONFIRM, NOW.minusHours(1), 2, 100L + i));
        return SignalPolicy.assess(SignalCategory.POOR_LIGHTING, votes, NOW, Linkage.NONE, Linkage.NONE);
    }

    private static FlagVote flag(long who, int weight) {
        return new FlagVote(FlagReason.TARGETS_SOMEONE, NOW.minusMinutes(5), weight, who, false);
    }

    @Test
    void oneFlag_opensACase_butHidesNothing() {
        Outcome outcome = FlagPolicy.evaluate(List.of(flag(1, 2)), supportedBy(1), Linkage.NONE, NOW);
        assertTrue(outcome.openCase());
        assertFalse(outcome.quarantine());
    }

    @Test
    void twoIndependentFlags_quarantineAWeaklySupportedSignal() {
        Outcome outcome = FlagPolicy.evaluate(List.of(flag(1, 2), flag(2, 2)), supportedBy(1), Linkage.NONE, NOW);
        assertTrue(outcome.quarantine());
        assertEquals(2, outcome.independentFlaggers());
    }

    @Test
    void aWellCorroboratedSignal_cannotBeSilencedByTwoFlags() {
        Outcome outcome = FlagPolicy.evaluate(List.of(flag(1, 2), flag(2, 2)), supportedBy(3), Linkage.NONE, NOW);
        assertTrue(outcome.openCase(), "it still goes to a human");
        assertFalse(outcome.quarantine(), "but stays visible meanwhile");
    }

    @Test
    void linkedFlaggers_countOnce() {
        Linkage group = (a, b) -> true;
        Outcome outcome = FlagPolicy.evaluate(List.of(flag(1, 2), flag(2, 2), flag(3, 2)), supportedBy(1), group, NOW);
        assertFalse(outcome.quarantine());
        assertEquals(1, outcome.independentFlaggers());
    }

    @Test
    void flagsOfNewOrRestrictedAccounts_orResolvedOnes_doNotCount() {
        FlagVote probation = new FlagVote(FlagReason.FALSE_INFORMATION, NOW.plusHours(20), 2, 1L, false);
        FlagVote restricted = flag(2, 0);
        FlagVote resolved = new FlagVote(FlagReason.FALSE_INFORMATION, NOW.minusDays(1), 2, 3L, true);
        Outcome outcome = FlagPolicy.evaluate(List.of(probation, restricted, resolved), supportedBy(1), Linkage.NONE, NOW);
        assertFalse(outcome.openCase());
        assertFalse(outcome.quarantine());
    }

    @Test
    void lowWeightFlaggers_needMorePeople() {
        assertFalse(FlagPolicy.evaluate(List.of(flag(1, 1), flag(2, 1)), supportedBy(1), Linkage.NONE, NOW).quarantine());
        assertTrue(FlagPolicy.evaluate(List.of(flag(1, 1), flag(2, 1), flag(3, 2)), supportedBy(1), Linkage.NONE, NOW)
                .quarantine());
    }
}
