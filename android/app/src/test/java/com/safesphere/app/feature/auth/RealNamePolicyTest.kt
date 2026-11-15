package com.safesphere.app.feature.auth

import com.safesphere.app.feature.auth.domain.RealNamePolicy
import com.safesphere.app.feature.auth.domain.RealNamePolicy.Result
import org.junit.Assert.assertEquals
import org.junit.Test

/** Phase N2: the phone's copy of the real-name rule; the same cases as the backend's RealNamePolicyTest. */
class RealNamePolicyTest {

    private fun check(raw: String) = RealNamePolicy.check(RealNamePolicy.clean(raw))

    @Test
    fun `real names in any script are accepted`() {
        for (name in listOf("Kavya Raman", "R. Abdul Rasik", "Mary-Ann D'Souza", "O’Neil Thomas",
                "கவியா ராமன்", "முத்துக்குமார்", "प्रिया शर्मा", "Lakshmi", "Al")) {
            assertEquals(name, Result.OK, check(name))
        }
    }

    @Test
    fun `cleaning trims and joins spaces and normalises`() {
        assertEquals("Kavya Raman", RealNamePolicy.clean("  Kavya \t  Raman  "))
        assertEquals("", RealNamePolicy.clean(null))
        assertEquals("Ren\u00e9", RealNamePolicy.clean("Rene\u0301"))
    }

    @Test
    fun `digits, symbols, emoji and other punctuation are not a name`() {
        for (name in listOf("K4vya", "Kavya 😀", "Kavya_Raman", "Kavya@home", "Kavya!", "Kavya (K)", "Kavya, Raman")) {
            assertEquals(name, Result.NOT_A_NAME, check(name))
        }
    }

    @Test
    fun `length limits count characters, not bytes`() {
        assertEquals(Result.TOO_SHORT, check("K"))
        assertEquals(Result.TOO_SHORT, check("   "))
        assertEquals(Result.TOO_SHORT, check(". -"))
        assertEquals(Result.OK, check("a".repeat(60)))
        assertEquals(Result.TOO_LONG, check("a".repeat(61)))
        assertEquals(Result.OK, check("க".repeat(60)))
    }
}
