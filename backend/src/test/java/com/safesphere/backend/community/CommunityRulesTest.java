package com.safesphere.backend.community;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.community.entity.CommunityEnums.Programme;
import com.safesphere.backend.community.service.CommunityRules;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Phase N3: the pure verification rules (roll numbers, codes, graduation years and the end of a membership). */
class CommunityRulesTest {

    @Test
    void rollNumbers_areNormalisedButNoFormatIsAssumed() {
        assertThat(CommunityRules.normaliseRoll(" e123-6003 ")).isEqualTo("E1236003");
        assertThat(CommunityRules.normaliseRoll("E 123/6003.")).isEqualTo("E1236003");
        assertThat(CommunityRules.normaliseRoll("   ")).isNull();
        assertThat(CommunityRules.normaliseRoll(null)).isNull();
        // MIET's own format (owner: like E1236003) and others are accepted; no 12-digit register number is required.
        assertThat(CommunityRules.isRoll("E1236003")).isTrue();
        assertThat(CommunityRules.isRoll("21IT045")).isTrue();
        assertThat(CommunityRules.isRoll("811521205001")).isTrue();
        assertThat(CommunityRules.isRoll("ABCDEFG")).as("needs a digit").isFalse();
        assertThat(CommunityRules.isRoll("E12")).as("too short").isFalse();
        assertThat(CommunityRules.isRoll("E1234567890123456")).as("too long").isFalse();
        assertThat(CommunityRules.isRoll("E123#6003")).isFalse();
        assertThat(CommunityRules.isRoll(null)).isFalse();
    }

    @Test
    void codes_useAnUnambiguousAlphabet_andAreNormalisedFromWhatPeopleType() {
        SecureRandom random = new SecureRandom();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String code = CommunityRules.newCode(random);
            assertThat(code).hasSize(8).doesNotContain("0", "O", "1", "I", "L");
            assertThat(CommunityRules.normaliseCode(CommunityRules.display(code).toLowerCase())).isEqualTo(code);
            seen.add(code);
        }
        assertThat(seen).hasSizeGreaterThan(495);
        assertThat(CommunityRules.display("ABCD2345")).isEqualTo("ABCD-2345");
        assertThat(CommunityRules.normaliseCode("abcd 2345")).isEqualTo("ABCD2345");
        assertThat(CommunityRules.normaliseCode("ABCD-234O")).as("O is not guessed as 0").isNull();
        assertThat(CommunityRules.normaliseCode("ABC")).isNull();
        assertThat(CommunityRules.normaliseCode(null)).isNull();
    }

    @Test
    void aMembershipEnds_atTheEndOf31JulyInIndia() {
        // Midnight 1 August in India = 18:30 UTC on 31 July.
        assertThat(CommunityRules.endsAt(2027)).isEqualTo(LocalDateTime.of(2027, 7, 31, 18, 30));
    }

    @Test
    void graduationYears_fromThisYearUntilItsEnd_toTheProgrammesUsualLength() {
        LocalDateTime november = LocalDateTime.of(2026, 11, 16, 6, 0);
        assertThat(CommunityRules.firstYear(november)).isEqualTo(2027);
        assertThat(CommunityRules.lastYear(Programme.BTECH_IT, november)).isEqualTo(2030);
        assertThat(CommunityRules.lastYear(Programme.MBA, november)).isEqualTo(2028);
        assertThat(CommunityRules.yearAllowed(Programme.BE_CSE, 2026, november)).isFalse();
        assertThat(CommunityRules.yearAllowed(Programme.BE_CSE, 2030, november)).isTrue();
        assertThat(CommunityRules.yearAllowed(Programme.ME_VLSI, 2029, november)).isFalse();

        LocalDateTime june = LocalDateTime.of(2027, 6, 1, 6, 0);
        assertThat(CommunityRules.firstYear(june)).as("before 31 July the final year still counts").isEqualTo(2027);
        LocalDateTime lastMinute = LocalDateTime.of(2027, 7, 31, 18, 29);
        assertThat(CommunityRules.firstYear(lastMinute)).isEqualTo(2027);
        LocalDateTime after = LocalDateTime.of(2027, 7, 31, 18, 30);
        assertThat(CommunityRules.firstYear(after)).isEqualTo(2028);
    }

    @Test
    void theProgrammeList_isTheOwnersList() {
        assertThat(Programme.values()).hasSize(14);
        assertThat(Programme.BE_CSE.label()).isEqualTo("B.E. Computer Science & Engineering");
        assertThat(Programme.BTECH_AIDS.label()).isEqualTo("B.Tech Artificial Intelligence & Data Science");
        assertThat(Programme.MBA.years()).isEqualTo(2);
        assertThat(Programme.BE_BME.years()).isEqualTo(4);
    }
}
