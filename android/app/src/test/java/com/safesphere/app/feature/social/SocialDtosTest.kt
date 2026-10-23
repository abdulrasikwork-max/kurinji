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
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.Language
import com.safesphere.app.feature.social.domain.ProfileInput
import com.safesphere.app.feature.social.domain.Relationship
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase 13A: what the phone sends and how it reads the answers (unknown values fail closed, secrets never printed). */
class SocialDtosTest {

    @Test
    fun `profile request - trimmed, empty optional fields sent as none, closed codes`() {
        val dto = ProfileRequestDto.from(
            ProfileInput(" asha_k ", " Asha ", "  ", setOf(Language.TA, Language.EN), setOf(Interest.HIDDEN_GEMS), " ")
        )
        assertEquals("asha_k", dto.handle)
        assertEquals("Asha", dto.displayName)
        assertNull(dto.bio)
        assertNull(dto.city)
        assertEquals(setOf("ta", "en"), dto.languages.toSet())
        assertEquals(listOf("HIDDEN_GEMS"), dto.interests)
    }

    @Test
    fun `unknown codes from a newer server are dropped or read as the most cautious value`() {
        val person = PersonProfileDto("id", "bala", "Bala", languages = listOf("ta", "xx"),
            interests = listOf("CITY_LIFE", "SOMETHING_NEW"), relationship = "BEST_FRIENDS").toDomain()
        assertEquals(listOf(Language.TA), person.languages)
        assertEquals(listOf(Interest.CITY_LIFE), person.interests)
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
