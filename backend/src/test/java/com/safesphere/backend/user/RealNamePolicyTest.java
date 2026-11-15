package com.safesphere.backend.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.user.service.RealNamePolicy;
import com.safesphere.backend.user.service.RealNamePolicy.Result;
import org.junit.jupiter.api.Test;

/** Phase N2: the real-name rule (the Android app keeps the same rule in RealNamePolicy.kt). */
class RealNamePolicyTest {

    private static Result check(String raw) {
        return RealNamePolicy.check(RealNamePolicy.clean(raw));
    }

    @Test
    void realNamesInAnyScript_areAccepted() {
        for (String name : new String[] {"Kavya Raman", "R. Abdul Rasik", "Mary-Ann D'Souza", "O’Neil Thomas",
                "கவியா ராமன்", "முத்துக்குமார்", "प्रिया शर्मा", "Lakshmi", "Al"}) {
            assertThat(check(name)).as(name).isEqualTo(Result.OK);
        }
    }

    @Test
    void cleaning_trimsAndJoinsSpaces_andNormalises() {
        assertThat(RealNamePolicy.clean("  Kavya \t  Raman  ")).isEqualTo("Kavya Raman");
        assertThat(RealNamePolicy.clean(null)).isEmpty();
        // "é" typed as e + combining accent becomes the single composed letter.
        assertThat(RealNamePolicy.clean("René")).isEqualTo("René");
    }

    @Test
    void digitsSymbolsEmojiAndOtherPunctuation_areNotAName() {
        for (String name : new String[] {"K4vya", "Kavya 😀", "Kavya_Raman", "Kavya@home", "Kavya!", "Kavya (K)",
                "Kavya, Raman", "Kavya\u0000"}) {
            assertThat(check(name)).as(name).isEqualTo(Result.NOT_A_NAME);
        }
    }

    @Test
    void lengthLimits_countCharactersNotBytes() {
        assertThat(check("K")).isEqualTo(Result.TOO_SHORT);
        assertThat(check("   ")).isEqualTo(Result.TOO_SHORT);
        assertThat(check(". -")).isEqualTo(Result.TOO_SHORT);
        assertThat(check("a".repeat(60))).isEqualTo(Result.OK);
        assertThat(check("a".repeat(61))).isEqualTo(Result.TOO_LONG);
        assertThat(check("க".repeat(60))).isEqualTo(Result.OK); // 60 Tamil letters = 180 bytes
    }

    @Test
    void everyRefusalHasWords() {
        for (Result result : Result.values()) {
            if (result != Result.OK) assertThat(RealNamePolicy.message(result)).isNotBlank();
        }
    }
}
