package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.safesphere.backend.social.policy.ProfileTextPolicy;
import com.safesphere.backend.social.policy.ProfileTextPolicy.Result;
import com.safesphere.backend.social.policy.Visibility;
import java.text.Normalizer;
import org.junit.jupiter.api.Test;

/** Phase 13A: cleaning and checking profile text, the closed lists, and the visibility rule for profiles. */
class ProfileTextPolicyTest {

    @Test
    void clean_removesInvisibleAndControlCharacters_collapsesSpaces_andTrims() {
        assertThat(ProfileTextPolicy.clean("  As​ha ‍  K\t\n ")).isEqualTo("Asha K");
        assertThat(ProfileTextPolicy.clean("A\u0000b‮c")).isEqualTo("Abc");
        assertThat(ProfileTextPolicy.clean(null)).isNull();
    }

    @Test
    void clean_normalisesToNfc_soTamilTextIsStoredOneWay() {
        String decomposed = Normalizer.normalize("கொ", Normalizer.Form.NFD);
        assertThat(ProfileTextPolicy.clean(decomposed)).isEqualTo(Normalizer.normalize("கொ", Normalizer.Form.NFC));
    }

    @Test
    void bio_isOptional_atMost160_andNoLinks() {
        assertThat(ProfileTextPolicy.checkBio(null)).isEqualTo(Result.OK);
        assertThat(ProfileTextPolicy.checkBio("Quiet cafés and long walks by the sea.")).isEqualTo(Result.OK);
        assertThat(ProfileTextPolicy.checkBio("x".repeat(161))).isEqualTo(Result.TOO_LONG);
        assertThat(ProfileTextPolicy.checkBio("follow me at https://example.org")).isEqualTo(Result.CONTAINS_LINK);
        assertThat(ProfileTextPolicy.checkBio("see www.example")).isEqualTo(Result.CONTAINS_LINK);
    }

    @Test
    void visibility_ownerAlways_othersOnlyWhileActiveAndNotBlockedEitherWay() {
        assertThat(Visibility.canSeeProfile(new Visibility.Facts(true, false, false, false))).isTrue();
        assertThat(Visibility.canSeeProfile(new Visibility.Facts(false, false, true, true))).isTrue();
        assertThat(Visibility.canSeeProfile(new Visibility.Facts(false, true, true, true))).isFalse();
        assertThat(Visibility.canSeeProfile(new Visibility.Facts(false, false, false, true))).isFalse();
    }
}
