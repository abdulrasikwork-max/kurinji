package com.safesphere.app.feature.social

import com.safesphere.app.feature.social.data.remote.AgeConfirmationDto
import com.safesphere.app.feature.social.data.remote.ConnectResultDto
import com.safesphere.app.feature.social.data.remote.NewInviteDto
import com.safesphere.app.feature.social.data.remote.OwnProfileDto
import com.safesphere.app.feature.social.data.remote.PersonProfileDto
import com.safesphere.app.feature.social.data.remote.PersonSummaryDto
import com.safesphere.app.feature.social.data.remote.ProfileRequestDto
import com.safesphere.app.feature.social.data.remote.RedeemDto
import com.safesphere.app.feature.social.domain.ConnectStatus
import com.safesphere.app.feature.social.domain.ProfileInput
import com.safesphere.app.feature.social.domain.Relationship
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase 13A: what the phone sends and how it reads the answers (unknown values fail closed, secrets never printed). */
class SocialDtosTest {

    @Test
    fun `profile request - trimmed, an empty bio sent as none, and nothing but handle and bio (N-51)`() {
        val dto = ProfileRequestDto.from(ProfileInput(" asha_k ", "  "))
        assertEquals("asha_k", dto.handle)
        assertNull(dto.bio)
        // City, languages and interests left the profile in N4: the request has exactly two fields.
        assertEquals(setOf("handle", "bio"), ProfileRequestDto::class.java.declaredFields.map { it.name }
            .filterNot { it == "Companion" }.toSet())
    }

    @Test
    fun `unknown codes from a newer server are dropped or read as the most cautious value`() {
        val person = PersonProfileDto("id", "bala", "Bala", programme = "BTECH_IT", graduationYear = 2027,
            relationship = "BEST_FRIENDS").toDomain()
        assertEquals("BTECH_IT", person.programme)
        assertEquals(2027, person.graduationYear)
        // A programme that is not a code, or a year that cannot be one, is left out (never shown as free text).
        val odd = PersonProfileDto("id", "bala", "Bala", programme = "B.Tech <b>IT</b>", graduationYear = 12).toDomain()
        assertNull(odd.programme)
        assertNull(odd.graduationYear)
        assertEquals(Relationship.NONE, person.relationship)

        val own = OwnProfileDto("id", "asha", "Asha", handleChangeAvailableAt = "not-a-time").toDomain()
        assertNull(own.handleChangeAvailableAt)

        val result = ConnectResultDto("SOMETHING", PersonSummaryDto("id", "bala", "Bala")).toDomain()
        assertEquals(ConnectStatus.REQUESTED, result.status) // only CONNECTED means connected
    }

    @Test
    fun `invite codes and the date of birth never appear in printed objects`() {
        val code = "0123456789ABCDEFGHJKMNPQRS"
        assertFalse(NewInviteDto("i", code, "2026-10-29T10:00:00Z").toString().contains(code))
        assertFalse(NewInviteDto("i", code, "2026-10-29T10:00:00Z").toDomain().toString().contains(code))
        assertFalse(RedeemDto(code).toString().contains(code))
        assertFalse(AgeConfirmationDto("1998-05-14").toString().contains("1998"))
    }
}
