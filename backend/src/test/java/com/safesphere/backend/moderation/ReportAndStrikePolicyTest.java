package com.safesphere.backend.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.community.service.Independence;
import com.safesphere.backend.moderation.service.ReportPolicy;
import com.safesphere.backend.moderation.service.ReportPolicy.Tally;
import com.safesphere.backend.moderation.service.ReportPolicy.Vote;
import com.safesphere.backend.moderation.service.StrikePolicy;
import com.safesphere.backend.moderation.service.StrikePolicy.Step;
import com.safesphere.backend.moderation.service.StrikePolicy.Strike;
import com.safesphere.backend.social.entity.PostEnums.ModerationReason;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 13D: the pure rules behind reports (M5, M6) and strikes (M10). */
class ReportAndStrikePolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 24, 12, 0);

    private static Vote vote(long who, ModerationReason reason) {
        return new Vote(who, reason, NOW.minusHours(1), false, false);
    }

    @Test
    void counting_skipsResolved_probation_andPausedReporters() {
        Tally tally = ReportPolicy.tally(List.of(
                vote(1, ModerationReason.SPAM),
                new Vote(2, ModerationReason.SPAM, NOW.plusHours(5), false, false),   // still on probation
                new Vote(3, ModerationReason.SPAM, NOW.minusDays(1), true, false),    // already decided
                new Vote(4, ModerationReason.SPAM, NOW.minusDays(1), false, true)),   // reporter paused
                Independence.Linkage.NONE, NOW);
        assertThat(tally.open()).isEqualTo(3);
        assertThat(tally.counting()).isEqualTo(1);
        assertThat(tally.independent()).isEqualTo(1);
        assertThat(tally.reasons().get(ModerationReason.SPAM)).isEqualTo(3);
    }

    @Test
    void collapse_needsThreeIndependentReporters() {
        List<Vote> three = List.of(vote(1, ModerationReason.HARASSMENT), vote(2, ModerationReason.HARASSMENT),
                vote(3, ModerationReason.HATE));
        assertThat(ReportPolicy.collapses(ReportPolicy.tally(three, Independence.Linkage.NONE, NOW), 3)).isTrue();
        assertThat(ReportPolicy.collapses(ReportPolicy.tally(three.subList(0, 2), Independence.Linkage.NONE, NOW), 3))
                .isFalse();
        Independence.Linkage oneAndTwo = (a, b) -> (a == 1 && b == 2) || (a == 2 && b == 1);
        Tally linked = ReportPolicy.tally(three, oneAndTwo, NOW);
        assertThat(linked.counting()).isEqualTo(3);
        assertThat(linked.independent()).isEqualTo(2);
        assertThat(ReportPolicy.collapses(linked, 3)).isFalse();
    }

    @Test
    void priority_putsSeverityFirst() {
        Tally threat = ReportPolicy.tally(List.of(vote(1, ModerationReason.THREAT)), Independence.Linkage.NONE, NOW);
        Tally spam = ReportPolicy.tally(List.of(vote(1, ModerationReason.SPAM), vote(2, ModerationReason.SPAM)),
                Independence.Linkage.NONE, NOW);
        assertThat(threat.topReason()).isEqualTo(ModerationReason.THREAT);
        assertThat(ReportPolicy.priority(threat)).isGreaterThan(ReportPolicy.priority(spam));
        Tally mixed = ReportPolicy.tally(List.of(vote(1, ModerationReason.SPAM), vote(2, ModerationReason.DOXXING)),
                Independence.Linkage.NONE, NOW);
        assertThat(mixed.topReason()).isEqualTo(ModerationReason.DOXXING);
        assertThat(ReportPolicy.priority(ReportPolicy.tally(List.of(), Independence.Linkage.NONE, NOW))).isZero();
    }

    @Test
    void strikes_halveEvery90Days() {
        assertThat(StrikePolicy.points(List.of(new Strike(2, NOW)), NOW)).isEqualTo(2.0);
        assertThat(StrikePolicy.points(List.of(new Strike(2, NOW.minusDays(90))), NOW)).isEqualTo(1.0, within());
        assertThat(StrikePolicy.points(List.of(new Strike(3, NOW.minusDays(180)), new Strike(1, NOW)), NOW))
                .isEqualTo(1.75, within());
        assertThat(StrikePolicy.points(List.of(new Strike(3, NOW.plusDays(1))), NOW)).isEqualTo(3.0);
    }

    @Test
    void suggestions_areGraduated() {
        assertThat(StrikePolicy.suggest(0, ModerationReason.SPAM)).isEqualTo(Step.WARN);
        assertThat(StrikePolicy.suggest(0, ModerationReason.HARASSMENT)).isEqualTo(Step.WARN);
        assertThat(StrikePolicy.suggest(0, ModerationReason.THREAT)).isEqualTo(Step.PAUSE_COMMENTS);
        assertThat(StrikePolicy.suggest(2, ModerationReason.HARASSMENT)).isEqualTo(Step.PAUSE_COMMENTS);
        assertThat(StrikePolicy.suggest(3, ModerationReason.HARASSMENT)).isEqualTo(Step.PAUSE_POSTING);
        assertThat(StrikePolicy.suggest(5, ModerationReason.THREAT)).isEqualTo(Step.SUSPEND);
        assertThat(Step.PAUSE_COMMENTS.length().toDays()).isEqualTo(3);
        assertThat(Step.PAUSE_POSTING.length().toDays()).isEqualTo(7);
        assertThat(Step.SUSPEND.length().toDays()).isEqualTo(30);
        assertThat(StrikePolicy.weight(ModerationReason.SEXUAL_HARASSMENT)).isEqualTo(3);
        assertThat(StrikePolicy.weight(ModerationReason.IMPERSONATION)).isEqualTo(2);
        assertThat(StrikePolicy.weight(ModerationReason.OTHER)).isEqualTo(1);
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(1e-9);
    }
}
