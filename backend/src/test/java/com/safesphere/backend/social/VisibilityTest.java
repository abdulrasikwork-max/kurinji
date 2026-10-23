package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.social.entity.PostEnums.Audience;
import com.safesphere.backend.social.entity.PostEnums.CommentPolicy;
import com.safesphere.backend.social.policy.Visibility;
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

    private static boolean see(Facts people, boolean connected, Audience audience, boolean reached) {
        return Visibility.canSeePost(new PostFacts(people, connected, audience, reached));
    }

    private static boolean comment(Facts people, boolean connected, Audience audience, CommentPolicy policy) {
        return Visibility.canComment(new PostFacts(people, connected, audience, true), policy);
    }
}
