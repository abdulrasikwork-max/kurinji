package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.social.policy.PlaceNamePolicy;
import com.safesphere.backend.social.policy.PlaceNamePolicy.Result;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** Phase 13B: place names are cleaned, de-duplicated by a normalised form, and never name someone's private place. */
class PlaceNamePolicyTest {

    @Test
    void ordinaryNames_areAccepted_inAnyScript() {
        assertThat(check("Marina Beach")).isEqualTo(Result.OK);
        assertThat(check("மெரினா கடற்கரை")).isEqualTo(Result.OK);
        assertThat(check("Murugan Idli Shop, T. Nagar")).isEqualTo(Result.OK);
        assertThat(check("Block 2 Food Court")).isEqualTo(Result.OK);
        assertThat(check("Home Bakers Cafe")).isEqualTo(Result.OK);     // "home" inside a real name is fine
    }

    @Test
    void lengthsAndLinks() {
        assertThat(check("A")).isEqualTo(Result.TOO_SHORT);
        assertThat(check("   ")).isEqualTo(Result.TOO_SHORT);
        assertThat(check("x".repeat(60))).isEqualTo(Result.OK);
        assertThat(check("x".repeat(61))).isEqualTo(Result.TOO_LONG);
        assertThat(check("Best cafe www.example.com")).isEqualTo(Result.CONTAINS_LINK);
        assertThat(check("!!")).isEqualTo(Result.NO_LETTERS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Home", "my home", "My House", "hostel room", "My hostel", "our flat", "en veedu",
            "Enga Veedu", "வீடு", "my office", "my PG near station", "Door no 12", "flat no 4b", "Room 204"})
    void privatePlaces_areRefused(String name) {
        assertThat(check(name)).isEqualTo(Result.PRIVATE_PLACE);
    }

    @Test
    void normalisedName_makesSpellingsOnePlace() {
        assertThat(PlaceNamePolicy.normalize("Marina  Beach")).isEqualTo("marina beach");
        assertThat(PlaceNamePolicy.normalize("Marina-Beach!")).isEqualTo("marina beach");
        assertThat(PlaceNamePolicy.normalize(PlaceNamePolicy.clean(" MARINA​ BEACH "))).isEqualTo("marina beach");
        assertThat(PlaceNamePolicy.normalize("மெரினா கடற்கரை")).isEqualTo("மெரினா கடற்கரை"); // vowel signs kept
    }

    private static Result check(String raw) {
        return PlaceNamePolicy.check(PlaceNamePolicy.clean(raw));
    }
}
