package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.social.policy.InviteCodes;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Phase 13A: invite codes are 26 Crockford base-32 characters, forgiving to retype, stored only as SHA-256. */
class InviteCodesTest {

    @Test
    void newCodes_are26CharactersOfTheAlphabet_andDoNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String code = InviteCodes.newCode();
            assertThat(code).hasSize(26).matches("[0-9A-HJKMNP-TV-Z]{26}");
            assertThat(seen.add(code)).isTrue();
        }
    }

    @Test
    void normalize_acceptsLowerCaseSpacesHyphensAndLookAlikes_butNotOtherLengthsOrCharacters() {
        String code = InviteCodes.newCode();
        String typed = (code.substring(0, 13) + "-" + code.substring(13)).toLowerCase();
        assertThat(InviteCodes.normalize(" " + typed + " ")).isEqualTo(code);
        assertThat(InviteCodes.normalize("O".repeat(26))).isEqualTo("0".repeat(26));
        assertThat(InviteCodes.normalize("il".repeat(13))).isEqualTo("1".repeat(26));
        assertThat(InviteCodes.normalize(code.substring(1))).isNull();
        assertThat(InviteCodes.normalize(code + "0")).isNull();
        assertThat(InviteCodes.normalize("U".repeat(26))).isNull();
        assertThat(InviteCodes.normalize(null)).isNull();
    }

    @Test
    void hash_isLowerCaseHexSha256_ofTheNormalisedCode() {
        // SHA-256("0" x 26), computed independently.
        assertThat(InviteCodes.hash("0".repeat(26))).matches("[0-9a-f]{64}");
        assertThat(InviteCodes.hash("0".repeat(26))).isEqualTo(InviteCodes.hash(InviteCodes.normalize("o".repeat(26))));
        assertThat(InviteCodes.hash("0".repeat(26))).isNotEqualTo(InviteCodes.hash("1".repeat(26)));
    }
}
