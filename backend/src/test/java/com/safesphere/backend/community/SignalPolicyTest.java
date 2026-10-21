package com.safesphere.backend.community;

import static com.safesphere.backend.community.entity.ContributionKind.CONFIRM;
import static com.safesphere.backend.community.entity.ContributionKind.NO_LONGER;
import static com.safesphere.backend.community.entity.ContributionKind.REPORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.safesphere.backend.community.entity.ContributionKind;
import com.safesphere.backend.community.entity.SignalCategory;
import com.safesphere.backend.community.service.Independence.Linkage;
import com.safesphere.backend.community.service.SignalPolicy;
import com.safesphere.backend.community.service.SignalPolicy.Assessment;
import com.safesphere.backend.community.service.SignalPolicy.Confidence;
import com.safesphere.backend.community.service.SignalPolicy.Freshness;
import com.safesphere.backend.community.service.SignalPolicy.Precision;
import com.safesphere.backend.community.service.SignalPolicy.Reason;
import com.safesphere.backend.community.service.SignalPolicy.Support;
import com.safesphere.backend.community.service.SignalPolicy.Vote;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SignalPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 18, 0);

    private static Vote vote(ContributionKind kind, Duration ago) {
        return new Vote(kind, NOW.minus(ago));
    }

    private static Assessment assess(SignalCategory category, Vote... votes) {
        return SignalPolicy.assess(category, List.of(votes), NOW);
    }

    @Test
    void oneReport_isVisibleOnlyAtAreaPrecision() {
        Assessment a = assess(SignalCategory.POOR_LIGHTING, vote(REPORT, Duration.ofHours(1)));
        assertTrue(a.visible());
        assertEquals(Precision.AREA, a.precision());
        assertEquals(Support.SINGLE, a.support());
        assertEquals(Freshness.TODAY, a.freshness());
        assertFalse(a.disputed());
    }

    @Test
    void secondPerson_earnsBlockPrecision() {
        Assessment a = assess(SignalCategory.POOR_LIGHTING, vote(REPORT, Duration.ofHours(1)), vote(CONFIRM, Duration.ofMinutes(5)));
        assertEquals(Precision.BLOCK, a.precision());
        assertEquals(Support.SEVERAL, a.support());
    }

    @Test
    void supportIsABand_neverACount() {
        List<Vote> votes = new ArrayList<>();
        for (int i = 0; i < 4; i++) votes.add(vote(CONFIRM, Duration.ofHours(i + 1)));
        assertEquals(Support.SEVERAL, SignalPolicy.assess(SignalCategory.HELP_POINT, votes, NOW).support());
        votes.add(vote(REPORT, Duration.ofHours(9)));
        assertEquals(Support.MANY, SignalPolicy.assess(SignalCategory.HELP_POINT, votes, NOW).support());
    }

    @Test
    void contributionNotCountingYet_isInvisibleButKeepsTheRowAlive() {
        Vote pending = new Vote(REPORT, NOW.plusMinutes(12)); // publication delay not over
        Assessment a = assess(SignalCategory.HARASSMENT, pending);
        assertFalse(a.visible());
        assertFalse(a.expired());
    }

    @Test
    void pendingConfirmation_doesNotRaisePrecisionEarly() {
        Assessment a = assess(SignalCategory.POOR_LIGHTING, vote(REPORT, Duration.ofHours(1)), new Vote(CONFIRM, NOW.plusMinutes(5)));
        assertEquals(Precision.AREA, a.precision());
        assertEquals(1, a.supporters());
    }

    @Test
    void incidentsFadeAfterThreeDays_conditionsLast() {
        Vote old = vote(REPORT, Duration.ofDays(3).plusSeconds(1));
        assertFalse(assess(SignalCategory.FOLLOWING, old).visible());
        assertTrue(assess(SignalCategory.FOLLOWING, old).expired());
        assertTrue(assess(SignalCategory.ISOLATED_STRETCH, old).visible());
        assertTrue(assess(SignalCategory.ISOLATED_STRETCH, vote(REPORT, Duration.ofDays(31))).expired());
        assertTrue(assess(SignalCategory.WELL_LIT_BUSY, vote(REPORT, Duration.ofDays(59))).visible());
    }

    @Test
    void aConfirmationKeepsATrueSignalAlive() {
        Assessment a = assess(SignalCategory.HARASSMENT, vote(REPORT, Duration.ofDays(4)), vote(CONFIRM, Duration.ofDays(1)));
        assertTrue(a.visible());
        assertEquals(1, a.supporters());
        assertEquals(Precision.AREA, a.precision(), "the expired report no longer counts towards precision");
        assertEquals(Freshness.THIS_WEEK, a.freshness());
    }

    @Test
    void oneNoLonger_onlyMarksItDisputed() {
        Assessment a = assess(SignalCategory.POOR_LIGHTING, vote(REPORT, Duration.ofDays(2)), vote(NO_LONGER, Duration.ofHours(1)));
        assertTrue(a.visible());
        assertTrue(a.disputed());
        assertFalse(a.retired());
    }

    @Test
    void twoNoLongerAgainstOneReport_retireIt() {
        Assessment a = assess(SignalCategory.POOR_LIGHTING, vote(REPORT, Duration.ofDays(2)),
                vote(NO_LONGER, Duration.ofHours(2)), vote(NO_LONGER, Duration.ofHours(1)));
        assertTrue(a.retired());
        assertFalse(a.visible());
    }

    @Test
    void manySupporters_outweighTwoNoLonger() {
        Assessment a = assess(SignalCategory.POOR_LIGHTING, vote(REPORT, Duration.ofDays(2)), vote(CONFIRM, Duration.ofDays(1)),
                vote(CONFIRM, Duration.ofHours(5)), vote(NO_LONGER, Duration.ofHours(2)), vote(NO_LONGER, Duration.ofHours(1)));
        assertFalse(a.retired());
        assertTrue(a.visible());
    }

    @Test
    void freshnessBands() {
        assertEquals(Freshness.THIS_WEEK, assess(SignalCategory.POOR_LIGHTING, vote(REPORT, Duration.ofDays(2))).freshness());
        assertEquals(Freshness.EARLIER, assess(SignalCategory.POOR_LIGHTING, vote(REPORT, Duration.ofDays(8))).freshness());
    }

    @Test
    void noVotes_isExpired() {
        assertTrue(SignalPolicy.assess(SignalCategory.HELP_POINT, List.of(), NOW).expired());
    }

    // ---- Phase 7: weights, independence, confidence -------------------------------------------------------------

    private static Vote person(ContributionKind kind, long who, int weight) {
        return new Vote(kind, NOW.minusHours(1), weight, who);
    }

    private static Assessment assessLinked(Linkage support, Linkage dispute, Vote... votes) {
        return SignalPolicy.assess(SignalCategory.POOR_LIGHTING, List.of(votes), NOW, support, dispute);
    }

    @Test
    void confidence_growsWithIndependentPeople_neverFromOnePerson() {
        assertEquals(Confidence.LOW, assessLinked(Linkage.NONE, Linkage.NONE, person(REPORT, 1, 3)).confidence(),
                "even a trusted person's lone report is LOW");
        assertEquals(Confidence.MODERATE, assessLinked(Linkage.NONE, Linkage.NONE,
                person(REPORT, 1, 2), person(CONFIRM, 2, 2)).confidence());
        assertEquals(Confidence.MODERATE, assessLinked(Linkage.NONE, Linkage.NONE,
                person(REPORT, 1, 2), person(CONFIRM, 2, 2), person(CONFIRM, 3, 2)).confidence(),
                "three standard people = 6 half-points, below HIGH");
        assertEquals(Confidence.HIGH, assessLinked(Linkage.NONE, Linkage.NONE,
                person(REPORT, 1, 3), person(CONFIRM, 2, 2), person(CONFIRM, 3, 2)).confidence(),
                "an established contributor among three tips it to HIGH");
        assertEquals(Confidence.HIGH, assessLinked(Linkage.NONE, Linkage.NONE, person(REPORT, 1, 2),
                person(CONFIRM, 2, 2), person(CONFIRM, 3, 2), person(CONFIRM, 4, 2)).confidence());
    }

    @Test
    void establishedContributors_isAReasonOnlyWithSeveralPeople() {
        assertFalse(assessLinked(Linkage.NONE, Linkage.NONE, person(REPORT, 1, 3)).reasons()
                .contains(Reason.ESTABLISHED_CONTRIBUTORS), "a lone person's tier is never revealed");
        assertTrue(assessLinked(Linkage.NONE, Linkage.NONE, person(REPORT, 1, 3), person(CONFIRM, 2, 2)).reasons()
                .contains(Reason.ESTABLISHED_CONTRIBUTORS));
    }

    @Test
    void linkedAccounts_countOnce_forPrecisionSupportAndConfidence() {
        Linkage puppets = (a, b) -> (a == 1 && b == 2) || (a == 2 && b == 1);
        Assessment a = assessLinked(puppets, Linkage.NONE, person(REPORT, 1, 2), person(CONFIRM, 2, 2));
        assertTrue(a.visible());
        assertEquals(1, a.supporters());
        assertEquals(Precision.AREA, a.precision(), "a puppet cannot unlock the block cell (k = 2 independent people)");
        assertEquals(Support.SINGLE, a.support());
        assertEquals(Confidence.LOW, a.confidence());
        assertTrue(a.reasons().contains(Reason.LINKED_SUPPORT_COUNTED_ONCE));

        Assessment withStranger = assessLinked(puppets, Linkage.NONE, person(REPORT, 1, 2), person(CONFIRM, 2, 2),
                person(CONFIRM, 3, 2));
        assertEquals(2, withStranger.supporters());
        assertEquals(Precision.BLOCK, withStranger.precision());
    }

    @Test
    void theSamePersonTwice_neverCountsTwice() {
        Assessment a = assessLinked(Linkage.NONE, Linkage.NONE, person(REPORT, 7, 2), person(CONFIRM, 7, 2));
        assertEquals(1, a.supporters());
        assertEquals(Precision.AREA, a.precision());
    }

    @Test
    void limitedPersonsLoneReport_waitsForSupport_andRestrictedCountsForNothing() {
        assertFalse(assessLinked(Linkage.NONE, Linkage.NONE, person(REPORT, 1, 1)).visible(),
                "LIMITED (1 half-point) alone is not shown");
        assertTrue(assessLinked(Linkage.NONE, Linkage.NONE, person(REPORT, 1, 1), person(CONFIRM, 2, 1)).visible());
        Assessment restricted = assessLinked(Linkage.NONE, Linkage.NONE, person(REPORT, 1, 0));
        assertFalse(restricted.visible());
        assertFalse(restricted.expired(), "the row stays until its lifetime ends");
        assertEquals(0, restricted.supporters());
    }

    @Test
    void coordinatedNoLonger_fromOneGroup_cannotRetire() {
        Linkage group = (a, b) -> a >= 10 && b >= 10;
        Assessment a = assessLinked(Linkage.NONE, group, person(REPORT, 1, 2),
                person(NO_LONGER, 10, 2), person(NO_LONGER, 11, 2), person(NO_LONGER, 12, 2));
        assertFalse(a.retired(), "three linked accounts are one independent disputer");
        assertTrue(a.visible());
        assertEquals(1, a.disputers());
        assertTrue(a.disputed());
    }

    @Test
    void trustedSupport_resistsTwoLimitedDisputers_butNotTwoStandard() {
        assertFalse(assessLinked(Linkage.NONE, Linkage.NONE, person(REPORT, 1, 3),
                person(NO_LONGER, 2, 1), person(NO_LONGER, 3, 1)).retired());
        assertTrue(assessLinked(Linkage.NONE, Linkage.NONE, person(REPORT, 1, 3),
                person(NO_LONGER, 2, 2), person(NO_LONGER, 3, 2)).retired());
    }

    // ---- Phase 8: independent freshness ------------------------------------------------------------------------------

    @Test
    void independentFreshness_ignoresLinkedSupport() {
        Linkage puppets = (a, b) -> (a == 1 && b == 2) || (a == 2 && b == 1);
        Vote owner = new Vote(REPORT, NOW.minusDays(2), 2, 1L);
        Vote puppetToday = new Vote(CONFIRM, NOW.minusHours(1), 3, 2L); // heavier, newer, linked
        Assessment a = assessLinked(puppets, Linkage.NONE, owner, puppetToday);
        assertEquals(Freshness.TODAY, a.freshness(), "the radar's freshness is unchanged");
        assertEquals(Freshness.THIS_WEEK, a.independentFreshness(),
                "a linked account cannot make the signal look fresher than its group's first contribution");

        Vote stranger = new Vote(CONFIRM, NOW.minusHours(2), 2, 3L);
        assertEquals(Freshness.TODAY, assessLinked(puppets, Linkage.NONE, owner, puppetToday, stranger)
                .independentFreshness(), "an independent person seeing it today does make it today");
    }

    @Test
    void independentFreshness_equalsFreshnessWithoutLinks() {
        Assessment a = assess(SignalCategory.POOR_LIGHTING, vote(REPORT, Duration.ofDays(9)), vote(CONFIRM, Duration.ofDays(2)));
        assertEquals(a.freshness(), a.independentFreshness());
        assertEquals(Freshness.THIS_WEEK, a.independentFreshness());
        Assessment pending = assess(SignalCategory.POOR_LIGHTING, vote(REPORT, Duration.ofDays(9)), new Vote(CONFIRM, NOW.plusMinutes(5)));
        assertEquals(Freshness.EARLIER, pending.independentFreshness(), "support that does not count yet adds nothing");
    }

    @Test
    void disputesPullConfidenceDown() {
        Assessment a = assessLinked(Linkage.NONE, Linkage.NONE, person(REPORT, 1, 2), person(CONFIRM, 2, 2),
                person(NO_LONGER, 3, 2));
        assertEquals(Confidence.LOW, a.confidence());
        assertTrue(a.reasons().contains(Reason.DISPUTED));
    }
}
