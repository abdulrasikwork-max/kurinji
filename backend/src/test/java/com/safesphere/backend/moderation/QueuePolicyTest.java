package com.safesphere.backend.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.moderation.entity.ModerationEnums.CaseOpenedBy;
import com.safesphere.backend.moderation.service.QueuePolicy;
import com.safesphere.backend.moderation.service.QueuePolicy.Facts;
import com.safesphere.backend.social.entity.ContentModeration.ModerationReason;
import com.safesphere.backend.social.entity.ContentModeration.ModerationSource;
import com.safesphere.backend.social.entity.ContentModeration.ModerationState;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** Phase N4 (M4, N-69): the three tiers of the moderators' queue and the 12-hour rule. Pure. */
class QueuePolicyTest {

    private static final LocalDateTime OPENED = LocalDateTime.of(2026, 11, 16, 9, 0);
    private static final LocalDateTime SOON = OPENED.plusMinutes(5);

    private static Facts aboutMe() {
        return new Facts(CaseOpenedBy.ABOUT_ME, ModerationState.HELD, ModerationSource.SYSTEM, ModerationReason.ABOUT_ME,
                3, OPENED);
    }

    private static Facts heldSevere() {
        return new Facts(CaseOpenedBy.SYSTEM, ModerationState.HELD, ModerationSource.SYSTEM, ModerationReason.THREAT, 0,
                OPENED);
    }

    private static Facts reported(int score) {
        return new Facts(CaseOpenedBy.REPORTS, ModerationState.NONE, null, null, score, OPENED);
    }

    private static Facts photoHold() {
        return new Facts(CaseOpenedBy.SYSTEM, ModerationState.HELD, ModerationSource.SYSTEM,
                ModerationReason.NEW_MEMBER_PHOTO, 0, OPENED);
    }

    private static Facts reviewRequest() {
        return new Facts(CaseOpenedBy.AUTHOR_REQUEST, ModerationState.COLLAPSED, ModerationSource.SYSTEM,
                ModerationReason.HARASSMENT, 0, OPENED);
    }

    @Test
    void theTiers() {
        assertThat(QueuePolicy.baseTier(aboutMe())).isEqualTo(1);
        assertThat(QueuePolicy.baseTier(heldSevere())).isEqualTo(1);
        assertThat(QueuePolicy.baseTier(reported(1))).isEqualTo(2);
        assertThat(QueuePolicy.baseTier(reported(9))).isEqualTo(2);
        assertThat(QueuePolicy.baseTier(photoHold())).isEqualTo(3);
        assertThat(QueuePolicy.baseTier(reviewRequest())).isEqualTo(3);
        // A person case with reports is tier 2; one without anything (never happens) is tier 3.
        assertThat(QueuePolicy.baseTier(new Facts(CaseOpenedBy.REPORTS, null, null, null, 2, OPENED))).isEqualTo(2);
        assertThat(QueuePolicy.baseTier(new Facts(CaseOpenedBy.REPORTS, null, null, null, 0, OPENED))).isEqualTo(3);
    }

    @Test
    void aboutMe_joinedToAnOlderCase_isStillFirst() {
        // The case was opened by reports; a later "This is about me" marked the moment.
        Facts joined = new Facts(CaseOpenedBy.REPORTS, ModerationState.HELD, ModerationSource.SYSTEM,
                ModerationReason.ABOUT_ME, 1, OPENED);
        assertThat(QueuePolicy.baseTier(joined)).isEqualTo(1);
    }

    @Test
    void aHoldThatWaitsForTheCheck_isNotSevere() {
        Facts waiting = new Facts(CaseOpenedBy.AUTHOR_REQUEST, ModerationState.HELD, ModerationSource.SYSTEM,
                ModerationReason.AWAITING_CHECK, 0, OPENED);
        assertThat(QueuePolicy.baseTier(waiting)).isEqualTo(3);
        // A moderator's own hold is not "a rule was maybe wrong": the reports decide its tier.
        Facts byModerator = new Facts(CaseOpenedBy.REPORTS, ModerationState.HELD, ModerationSource.MODERATOR,
                ModerationReason.NEEDS_REVIEW, 3, OPENED);
        assertThat(QueuePolicy.baseTier(byModerator)).isEqualTo(2);
    }

    @Test
    void theTier_alwaysOutweighsTheScore() {
        assertThat(QueuePolicy.priority(heldSevere(), SOON)).isGreaterThan(QueuePolicy.priority(reported(900), SOON));
        assertThat(QueuePolicy.priority(reported(1), SOON)).isGreaterThan(QueuePolicy.priority(photoHold(), SOON));
        // Inside a tier the reports order the cases.
        assertThat(QueuePolicy.priority(reported(9), SOON)).isGreaterThan(QueuePolicy.priority(reported(3), SOON));
        // An absurd score cannot jump a tier.
        assertThat(QueuePolicy.priority(reported(1_000_000), SOON)).isLessThan(QueuePolicy.priority(aboutMe(), SOON));
    }

    @Test
    void after12Hours_aCaseMovesUpOneTier_neverAboveTheFirst() {
        LocalDateTime atTwelve = OPENED.plusHours(12);
        LocalDateTime justAfter = atTwelve.plusNanos(1000);
        assertThat(QueuePolicy.tier(photoHold(), atTwelve)).isEqualTo(3);     // exactly 12 hours: not yet
        assertThat(QueuePolicy.tier(photoHold(), justAfter)).isEqualTo(2);
        assertThat(QueuePolicy.tier(reported(1), justAfter)).isEqualTo(1);
        assertThat(QueuePolicy.tier(aboutMe(), justAfter)).isEqualTo(1);
        // It moves up once, not once every 12 hours.
        assertThat(QueuePolicy.tier(photoHold(), OPENED.plusDays(30))).isEqualTo(2);
        // A photo that waited 13 hours comes before a fresh report with the same score.
        assertThat(QueuePolicy.priority(photoHold(), justAfter)).isEqualTo(QueuePolicy.priority(reported(0), SOON) + 1000);
    }

    @Test
    void waitingTime_inWholeHours() {
        assertThat(QueuePolicy.waitingHours(OPENED, OPENED)).isZero();
        assertThat(QueuePolicy.waitingHours(OPENED, OPENED.plusMinutes(59))).isZero();
        assertThat(QueuePolicy.waitingHours(OPENED, OPENED.plusHours(13).plusMinutes(1))).isEqualTo(13);
        assertThat(QueuePolicy.waitingHours(OPENED, OPENED.minusHours(1))).isZero(); // a clock that went back
    }
}
