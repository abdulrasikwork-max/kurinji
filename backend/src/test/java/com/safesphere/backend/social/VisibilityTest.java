package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.social.entity.PostEnums.Audience;
import com.safesphere.backend.social.entity.PostEnums.CommentPolicy;
import com.safesphere.backend.social.entity.PostEnums.ModerationState;
import com.safesphere.backend.social.policy.Visibility;
import com.safesphere.backend.social.policy.Visibility.CommentFacts;
import com.safesphere.backend.social.policy.Visibility.Facts;
import com.safesphere.backend.social.policy.Visibility.PostFacts;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/** Phase 13A + 13B: the whole matrix of the one visibility rule for profiles, posts and perspectives. */
class VisibilityTest {

    private static final Facts SELF = new Facts(true, false, true);
    private static final Facts OTHER = new Facts(false, false, true);
    private static final Facts BLOCKED = new Facts(false, true, true);
    private static final Facts INACTIVE = new Facts(false, false, false);

    @Test
    void profiles() {
        assertThat(Visibility.canSeeProfile(SELF)).isTrue();
        assertThat(Visibility.canSeeProfile(OTHER)).isTrue();
        assertThat(Visibility.canSeeProfile(BLOCKED)).isFalse();
        assertThat(Visibility.canSeeProfile(INACTIVE)).isFalse();
    }

    @Test
    void posts_authorAlwaysSeesTheirOwn_evenOnlyMeAndDelayed() {
        for (Audience audience : Audience.values()) {
            assertThat(Visibility.canSeePost(new PostFacts(SELF, false, audience, false))).isTrue();
        }
    }

    @Test
    void posts_othersByAudienceConnectionAndDelay() {
        assertThat(see(OTHER, false, Audience.EVERYONE, true)).isTrue();
        assertThat(see(OTHER, false, Audience.CONNECTIONS, true)).isFalse();
        assertThat(see(OTHER, true, Audience.CONNECTIONS, true)).isTrue();
        assertThat(see(OTHER, true, Audience.ONLY_ME, true)).isFalse();
        assertThat(see(OTHER, true, Audience.EVERYONE, false)).isFalse();      // Q1 delay not over
        assertThat(see(BLOCKED, true, Audience.EVERYONE, true)).isFalse();
        assertThat(see(INACTIVE, true, Audience.EVERYONE, true)).isFalse();
    }

    @Test
    void comments_followThePolicy_andNeverOnOnlyMe() {
        assertThat(comment(OTHER, false, Audience.EVERYONE, CommentPolicy.EVERYONE)).isTrue();
        assertThat(comment(OTHER, false, Audience.EVERYONE, CommentPolicy.CONNECTIONS)).isFalse();
        assertThat(comment(OTHER, true, Audience.EVERYONE, CommentPolicy.CONNECTIONS)).isTrue();
        assertThat(comment(OTHER, true, Audience.EVERYONE, CommentPolicy.NOBODY)).isFalse();
        assertThat(comment(OTHER, false, Audience.CONNECTIONS, CommentPolicy.EVERYONE)).isFalse(); // cannot even see it
        assertThat(comment(BLOCKED, true, Audience.EVERYONE, CommentPolicy.EVERYONE)).isFalse();
        assertThat(comment(SELF, false, Audience.EVERYONE, CommentPolicy.NOBODY)).isTrue();         // the author answers
        assertThat(comment(SELF, false, Audience.ONLY_ME, CommentPolicy.NOBODY)).isFalse();
    }

    @Test
    void visibleAudiences_isTheSameRule() {
        assertThat(Visibility.visibleAudiences(OTHER, false)).containsExactly(Audience.EVERYONE);
        assertThat(Visibility.visibleAudiences(OTHER, true)).containsExactlyInAnyOrder(Audience.EVERYONE,
                Audience.CONNECTIONS);
        assertThat(Visibility.visibleAudiences(BLOCKED, true)).isEmpty();
        assertThat(Visibility.visibleAudiences(SELF, false)).isEqualTo(EnumSet.allOf(Audience.class));
    }

    /** Phase 13D (M7): held and removed content is its author's alone; collapsed stays visible, out of the Edition. */
    @Test
    void moderationStates_perViewer() {
        for (ModerationState state : ModerationState.values()) {
            assertThat(Visibility.canSeePost(new PostFacts(SELF, false, Audience.EVERYONE, true, state))).isTrue();
            boolean others = state == ModerationState.NONE || state == ModerationState.COLLAPSED;
            assertThat(Visibility.canSeePost(new PostFacts(OTHER, true, Audience.EVERYONE, true, state)))
                    .isEqualTo(others);
            assertThat(Visibility.showInLists(new PostFacts(OTHER, true, Audience.EVERYONE, true, state), false))
                    .isEqualTo(others);
            assertThat(Visibility.showInEdition(new PostFacts(OTHER, true, Audience.EVERYONE, true, state), false))
                    .isEqualTo(state == ModerationState.NONE);
            assertThat(Visibility.canComment(new PostFacts(SELF, false, Audience.EVERYONE, true, state),
                    CommentPolicy.EVERYONE)).isEqualTo(others);
        }
        assertThat(Visibility.showInEdition(new PostFacts(OTHER, true, Audience.EVERYONE, true, ModerationState.NONE),
                true)).isFalse(); // muted
        assertThat(Visibility.visibleStates(OTHER)).containsExactlyInAnyOrder(ModerationState.NONE,
                ModerationState.COLLAPSED);
        assertThat(Visibility.visibleStates(SELF)).isEqualTo(EnumSet.allOf(ModerationState.class));
        assertThat(Visibility.visibleStates(BLOCKED)).isEmpty();
    }

    /** Phase 13D: perspectives (hidden Q5, waiting Q6, moderation) by viewer. */
    @Test
    void perspectives_perViewer() {
        CommentFacts plain = new CommentFacts(OTHER, false, false, false, ModerationState.NONE);
        assertThat(Visibility.canSeeComment(plain)).isTrue();
        assertThat(Visibility.canSeeComment(new CommentFacts(BLOCKED, false, false, false, ModerationState.NONE)))
                .isFalse();
        // Hidden by the author / waiting for approval: the writer and the post's author only.
        for (boolean hidden : new boolean[] {true, false}) {
            CommentFacts stranger = new CommentFacts(OTHER, false, hidden, !hidden, ModerationState.NONE);
            assertThat(Visibility.canSeeComment(stranger)).isFalse();
            assertThat(Visibility.canSeeComment(new CommentFacts(OTHER, true, hidden, !hidden, ModerationState.NONE)))
                    .isTrue();
            assertThat(Visibility.canSeeComment(new CommentFacts(SELF, false, hidden, !hidden, ModerationState.NONE)))
                    .isTrue();
        }
        // Held / removed: the writer only, not even the post's author.
        for (ModerationState state : new ModerationState[] {ModerationState.HELD, ModerationState.REMOVED}) {
            assertThat(Visibility.canSeeComment(new CommentFacts(OTHER, true, false, false, state))).isFalse();
            assertThat(Visibility.canSeeComment(new CommentFacts(SELF, false, false, false, state))).isTrue();
        }
        assertThat(Visibility.canSeeComment(new CommentFacts(OTHER, false, false, false, ModerationState.COLLAPSED)))
                .isTrue();
    }

    private static boolean see(Facts people, boolean connected, Audience audience, boolean reached) {
        return Visibility.canSeePost(new PostFacts(people, connected, audience, reached));
    }

    private static boolean comment(Facts people, boolean connected, Audience audience, CommentPolicy policy) {
        return Visibility.canComment(new PostFacts(people, connected, audience, true), policy);
    }
}
