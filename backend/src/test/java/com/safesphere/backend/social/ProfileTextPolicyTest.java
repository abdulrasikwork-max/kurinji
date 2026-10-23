package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.safesphere.backend.social.policy.ProfileLists;
import com.safesphere.backend.social.policy.ProfileTextPolicy;
import com.safesphere.backend.social.policy.ProfileTextPolicy.Result;
import com.safesphere.backend.social.policy.Visibility;
import java.text.Normalizer;
import java.util.List;
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
    void displayName_needs1to40Characters_countedAsCharacters_andNoLinks() {
        assertThat(ProfileTextPolicy.checkDisplayName("")).isEqualTo(Result.EMPTY);
        assertThat(ProfileTextPolicy.checkDisplayName("ஆஷா தேவி")).isEqualTo(Result.OK);
        assertThat(ProfileTextPolicy.checkDisplayName("a".repeat(40))).isEqualTo(Result.OK);
        assertThat(ProfileTextPolicy.checkDisplayName("a".repeat(41))).isEqualTo(Result.TOO_LONG);
        assertThat(ProfileTextPolicy.checkDisplayName("Asha shop.com")).isEqualTo(Result.CONTAINS_LINK);
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
    void city_isLettersOfAnyScript_withSpacesDotsApostrophesAndHyphens() {
        assertThat(ProfileTextPolicy.checkCity("Chennai")).isEqualTo(Result.OK);
        assertThat(ProfileTextPolicy.checkCity("சென்னை")).isEqualTo(Result.OK);
        assertThat(ProfileTextPolicy.checkCity("St. Thomas Mount")).isEqualTo(Result.OK);
        assertThat(ProfileTextPolicy.checkCity("Chennai 600001")).isEqualTo(Result.INVALID_CHARACTERS);
        assertThat(ProfileTextPolicy.checkCity("<script>")).isEqualTo(Result.INVALID_CHARACTERS);
    }

    @Test
    void lists_areClosed_orderedAndWithoutDuplicates() {
        assertThat(ProfileLists.encodeLanguages(List.of("en", "TA", "en"))).isEqualTo("ta,en");
        assertThat(ProfileLists.encodeInterests(List.of("hidden_gems", "FOOD_CAFES"))).isEqualTo("FOOD_CAFES,HIDDEN_GEMS");
        assertThat(ProfileLists.encodeLanguages(List.of())).isNull();
        assertThat(ProfileLists.decode("ta,en")).containsExactly("ta", "en");
        assertThat(ProfileLists.decode(null)).isEmpty();
        assertThatThrownBy(() -> ProfileLists.encodeLanguages(List.of("fr"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void visibility_ownerAlways_othersOnlyWhileActiveAndNotBlockedEitherWay() {
        assertThat(Visibility.canSeeProfile(new Visibility.Facts(true, false, false))).isTrue();
        assertThat(Visibility.canSeeProfile(new Visibility.Facts(false, false, true))).isTrue();
        assertThat(Visibility.canSeeProfile(new Visibility.Facts(false, true, true))).isFalse();
        assertThat(Visibility.canSeeProfile(new Visibility.Facts(false, false, false))).isFalse();
    }
}
