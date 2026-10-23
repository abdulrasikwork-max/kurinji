package com.safesphere.app.feature.social

import com.safesphere.app.feature.social.domain.AgePolicy
import com.safesphere.app.feature.social.domain.HandlePolicy
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.Language
import com.safesphere.app.feature.social.domain.ProfileInput
import com.safesphere.app.feature.social.domain.ProfileInputPolicy
import com.safesphere.app.feature.social.domain.ProfileInputPolicy.Problem
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 13A: the phone's copies of the server rules (AgePolicy, HandlePolicy, profile text). The cases are the same as
 * the backend's AgePolicyTest / HandlePolicyTest, so the two cannot drift apart without a failing test on one side.
 */
class SocialPolicyTest {

    private val today = LocalDate.of(2026, 10, 22)

    @Test
    fun `age - 18th birthday today is adult, the day before is not`() {
        assertEquals(AgePolicy.Result.ADULT, AgePolicy.check(LocalDate.of(2008, 10, 22), today))
        assertEquals(AgePolicy.Result.UNDER_AGE, AgePolicy.check(LocalDate.of(2008, 10, 23), today))
    }

    @Test
    fun `age - 29 February turns 18 on 1 March in a year without 29 February`() {
        val born = LocalDate.of(2008, 2, 29)
        assertEquals(AgePolicy.Result.UNDER_AGE, AgePolicy.check(born, LocalDate.of(2026, 2, 28)))
        assertEquals(AgePolicy.Result.ADULT, AgePolicy.check(born, LocalDate.of(2026, 3, 1)))
    }

    @Test
    fun `age - future, missing and over 120 years are invalid`() {
        assertEquals(AgePolicy.Result.INVALID, AgePolicy.check(today.plusDays(1), today))
        assertEquals(AgePolicy.Result.INVALID, AgePolicy.check(null, today))
        assertEquals(AgePolicy.Result.INVALID, AgePolicy.check(today.minusYears(121), today))
        assertEquals(AgePolicy.Result.ADULT, AgePolicy.check(today.minusYears(120), today))
        assertEquals(LocalDate.of(2008, 10, 22), AgePolicy.latestAdultBirthDate(today))
    }

    @Test
    fun `handle - normalised, valid and invalid formats as on the server`() {
        assertEquals("asha.k_1", HandlePolicy.normalize("  @Asha.K_1 "))
        for (ok in listOf("asha", "asha.k", "asha_k", "a12", "tamil.trails_2026", "abcdefghijklmnopqrst")) {
            assertEquals(ok, HandlePolicy.Result.OK, HandlePolicy.check(ok))
        }
        for (bad in listOf("", "ab", "abcdefghijklmnopqrstu", "1asha", "_asha", ".asha", "asha.", "as..ha", "Asha",
                "asha-k", "asha k", "ஆஷா", "аsha")) {
            assertEquals(bad, HandlePolicy.Result.INVALID_FORMAT, HandlePolicy.check(bad))
        }
    }

    @Test
    fun `handle - reserved words alone or decorated are refused`() {
        for (reserved in listOf("admin", "admin_1", "a.d.m.i.n", "moderator", "support2", "kurinji", "safesphere",
                "official_", "sos_1")) {
            assertEquals(reserved, HandlePolicy.Result.RESERVED, HandlePolicy.check(reserved))
        }
        assertEquals(HandlePolicy.Result.OK, HandlePolicy.check("adminton"))
    }

    @Test
    fun `profile form - every field checked, Tamil text measured in characters`() {
        val good = ProfileInput("asha_k", "ஆஷா தேவி", "கடற்கரை நடை", setOf(Language.TA), setOf(Interest.CITY_LIFE), "சென்னை")
        assertFalse(ProfileInputPolicy.check(good).any)

        val bad = ProfileInputPolicy.check(
            ProfileInput("1asha", " ", "x".repeat(161), emptySet(), emptySet(), "Chennai 600001")
        )
        assertEquals(Problem.HANDLE_FORMAT, bad.handle)
        assertEquals(Problem.NAME_REQUIRED, bad.displayName)
        assertEquals(Problem.BIO_TOO_LONG, bad.bio)
        assertEquals(Problem.CITY_INVALID, bad.city)
        assertTrue(bad.any)
    }

    @Test
    fun `profile form - links, long names and reserved handles`() {
        val problems = ProfileInputPolicy.check(
            ProfileInput("admin", "a".repeat(41), "see https://example.org", emptySet(), emptySet(), "")
        )
        assertEquals(Problem.HANDLE_UNAVAILABLE, problems.handle)
        assertEquals(Problem.NAME_TOO_LONG, problems.displayName)
        assertEquals(Problem.LINK, problems.bio)
        assertNull(problems.city)
        assertEquals(Problem.LINK, ProfileInputPolicy.check(
            ProfileInput("asha_k", "Asha shop.com", "", emptySet(), emptySet(), "")).displayName)
    }
}
