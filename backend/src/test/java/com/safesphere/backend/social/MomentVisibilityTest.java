package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.social.entity.MomentEnums.Audience;
import com.safesphere.backend.social.entity.PostEnums.ModerationState;
import com.safesphere.backend.social.policy.Visibility;
import com.safesphere.backend.social.policy.Visibility.Facts;
import com.safesphere.backend.social.policy.Visibility.MomentFacts;
import org.junit.jupiter.api.Test;

/**
 * Phase N4: the one visibility rule, for moments (Visibility.canSeeMoment / showOnToday), from every point of view:
 * the author, a connection, a member who is not connected, someone blocked, someone outside the community.
 */
class MomentVisibilityTest {

    private static final Facts SELF = new Facts(true, false, true, true);
    private static final Facts MEMBER = new Facts(false, false, true, true);
    private static final Facts BLOCKED = new Facts(false, true, true, true);
    private static final Facts AUTHOR_GONE = new Facts(false, false, false, true);
    private static final Facts OUTSIDE = new Facts(false, false, true, false);

    private static MomentFacts live(Facts people, boolean connected, Audience audience) {
        return new MomentFacts(people, connected, audience, true, true, false, ModerationState.NONE);
    }

    @Test
    void aCommunityMoment_isForEveryActiveMemberOfTheSameCommunity() {
        assertThat(Visibility.canSeeMoment(live(SELF, false, Audience.COMMUNITY))).isTrue();
        assertThat(Visibility.canSeeMoment(live(MEMBER, true, Audience.COMMUNITY))).isTrue();
        assertThat(Visibility.canSeeMoment(live(MEMBER, false, Audience.COMMUNITY))).isTrue();
        assertThat(Visibility.canSeeMoment(live(BLOCKED, true, Audience.COMMUNITY))).isFalse();
        assertThat(Visibility.canSeeMoment(live(AUTHOR_GONE, true, Audience.COMMUNITY))).isFalse();
        // Not verified, graduated, banned, or another community: never (N-46: both need active membership).
        assertThat(Visibility.canSeeMoment(live(OUTSIDE, true, Audience.COMMUNITY))).isFalse();
    }

    @Test
    void aMyPeopleMoment_isForMutualConnectionsOnly() {
        assertThat(Visibility.canSeeMoment(live(SELF, false, Audience.MY_PEOPLE))).isTrue();
        assertThat(Visibility.canSeeMoment(live(MEMBER, true, Audience.MY_PEOPLE))).isTrue();
        assertThat(Visibility.canSeeMoment(live(MEMBER, false, Audience.MY_PEOPLE))).isFalse();
        assertThat(Visibility.canSeeMoment(live(BLOCKED, true, Audience.MY_PEOPLE))).isFalse();
        assertThat(Visibility.canSeeMoment(live(OUTSIDE, true, Audience.MY_PEOPLE))).isFalse();
    }

    @Test
    void beforeItStarts_onlyTheAuthorSeesIt() {
        MomentFacts waiting = new MomentFacts(MEMBER, true, Audience.COMMUNITY, false, false, false,
                ModerationState.NONE);
        assertThat(Visibility.canSeeMoment(waiting)).isFalse();
        assertThat(Visibility.showOnToday(waiting, false)).isFalse();
        MomentFacts own = new MomentFacts(SELF, false, Audience.COMMUNITY, false, false, false, ModerationState.HELD);
        assertThat(Visibility.canSeeMoment(own)).isTrue();          // no shadow states: the author always sees it
        assertThat(Visibility.showOnToday(own, false)).isFalse();   // but the wall shows what everyone gets
    }

    @Test
    void after24Hours_itIsGone_unlessTheAuthorKeepsIt() {
        MomentFacts ended = new MomentFacts(MEMBER, false, Audience.COMMUNITY, true, false, false, ModerationState.NONE);
        MomentFacts kept = new MomentFacts(MEMBER, false, Audience.COMMUNITY, true, false, true, ModerationState.NONE);
        assertThat(Visibility.canSeeMoment(ended)).isFalse();
        assertThat(Visibility.canSeeMoment(kept)).isTrue();         // on the profile
        // N-48: never back on Today, for anyone, the author included.
        assertThat(Visibility.showOnToday(kept, false)).isFalse();
        assertThat(Visibility.showOnToday(new MomentFacts(SELF, false, Audience.COMMUNITY, true, false, true,
                ModerationState.NONE), false)).isFalse();
        // A kept My people moment stays with my people.
        assertThat(Visibility.canSeeMoment(new MomentFacts(MEMBER, false, Audience.MY_PEOPLE, true, false, true,
                ModerationState.NONE))).isFalse();
        assertThat(Visibility.canSeeMoment(new MomentFacts(MEMBER, true, Audience.MY_PEOPLE, true, false, true,
                ModerationState.NONE))).isTrue();
    }

    @Test
    void heldAndRemovedMoments_areSeenByTheirAuthorOnly_collapsedOnesByEveryone() {
        for (ModerationState state : ModerationState.values()) {
            MomentFacts other = new MomentFacts(MEMBER, true, Audience.COMMUNITY, true, true, true, state);
            MomentFacts own = new MomentFacts(SELF, false, Audience.COMMUNITY, true, true, false, state);
            boolean othersSee = state == ModerationState.NONE || state == ModerationState.COLLAPSED;
            assertThat(Visibility.canSeeMoment(other)).as(state.name()).isEqualTo(othersSee);
            assertThat(Visibility.showOnToday(other, false)).as(state.name()).isEqualTo(othersSee);
            assertThat(Visibility.canSeeMoment(own)).as(state.name()).isTrue();
            assertThat(Visibility.showOnToday(own, false)).as(state.name()).isEqualTo(othersSee);
        }
        assertThat(Visibility.todayStates()).containsExactlyInAnyOrder(ModerationState.NONE, ModerationState.COLLAPSED);
    }

    @Test
    void aMute_hidesFromTodayOnly() {
        MomentFacts facts = live(MEMBER, true, Audience.COMMUNITY);
        assertThat(Visibility.showOnToday(facts, false)).isTrue();
        assertThat(Visibility.showOnToday(facts, true)).isFalse();
        assertThat(Visibility.canSeeMoment(facts)).isTrue();        // opened deliberately: still readable
        assertThat(Visibility.showOnToday(live(SELF, false, Audience.COMMUNITY), true)).isTrue();
    }

    @Test
    void today_showsOnlyWhatTheViewerMaySee() {
        assertThat(Visibility.showOnToday(live(BLOCKED, true, Audience.COMMUNITY), false)).isFalse();
        assertThat(Visibility.showOnToday(live(OUTSIDE, true, Audience.COMMUNITY), false)).isFalse();
        assertThat(Visibility.showOnToday(live(MEMBER, false, Audience.MY_PEOPLE), false)).isFalse();
        assertThat(Visibility.showOnToday(live(MEMBER, true, Audience.MY_PEOPLE), false)).isTrue();
    }
}
