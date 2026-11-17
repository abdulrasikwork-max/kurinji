package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.social.policy.Visibility;
import com.safesphere.backend.social.policy.Visibility.Facts;
import org.junit.jupiter.api.Test;

/**
 * The one visibility rule for profiles (Phase 13A, N3). Moments have their own matrix in MomentVisibilityTest; the
 * rules for posts and perspectives left with them in N4.
 */
class VisibilityTest {

    private static final Facts SELF = new Facts(true, false, true, true);
    private static final Facts OTHER = new Facts(false, false, true, true);
    private static final Facts BLOCKED = new Facts(false, true, true, true);
    private static final Facts INACTIVE = new Facts(false, false, false, true);
    /** Phase N3: an active account outside the viewer's community (not verified, graduated, ended or banned). */
    private static final Facts OUTSIDE = new Facts(false, false, true, false);

    @Test
    void profiles() {
        assertThat(Visibility.canSeeProfile(SELF)).isTrue();
        assertThat(Visibility.canSeeProfile(OTHER)).isTrue();
        assertThat(Visibility.canSeeProfile(BLOCKED)).isFalse();
        assertThat(Visibility.canSeeProfile(INACTIVE)).isFalse();
        assertThat(Visibility.canSeeProfile(OUTSIDE)).isFalse();
        assertThat(Visibility.canSeeProfile(new Facts(true, false, true, false))).as("oneself, even unverified").isTrue();
        assertThat(Visibility.canSeeProfile(new Facts(true, true, false, false))).as("oneself, always").isTrue();
    }
}
