package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.social.policy.HandlePolicy;
import com.safesphere.backend.social.policy.HandlePolicy.Result;
import org.junit.jupiter.api.Test;

/** Phase 13A: handle format and reserved words (the same format as the V20 CHECK, verified on MySQL). */
class HandlePolicyTest {

    @Test
    void normalize_trims_dropsALeadingAt_andLowerCasesAscii() {
        assertThat(HandlePolicy.normalize("  @Asha.K_1 ")).isEqualTo("asha.k_1");
        assertThat(HandlePolicy.normalize(null)).isNull();
    }

    @Test
    void validHandles() {
        for (String handle : new String[] {"asha", "asha.k", "asha_k", "a12", "tamil.trails_2026", "abcdefghijklmnopqrst"}) {
            assertThat(HandlePolicy.check(handle)).as(handle).isEqualTo(Result.OK);
        }
    }

    @Test
    void invalidHandles() {
        for (String handle : new String[] {null, "", "ab", "abcdefghijklmnopqrstu", "1asha", "_asha", ".asha", "asha.",
                "as..ha", "Asha", "asha-k", "asha k", "ஆஷா", "аsha" /* Cyrillic а */}) {
            assertThat(HandlePolicy.check(handle)).as(String.valueOf(handle)).isEqualTo(Result.INVALID_FORMAT);
        }
    }

    @Test
    void reservedWords_alone_orDecoratedWithDigitsDotsAndUnderscores_areRefused() {
        for (String handle : new String[] {"admin", "admin_1", "a.d.m.i.n", "moderator", "support2", "kurinji",
                "safesphere", "official_", "sos_1"}) {
            assertThat(HandlePolicy.check(handle)).as(handle).isEqualTo(Result.RESERVED);
        }
        assertThat(HandlePolicy.check("adminton")).isEqualTo(Result.OK);
    }
}
