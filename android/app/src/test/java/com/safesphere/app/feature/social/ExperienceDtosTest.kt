package com.safesphere.app.feature.social

import com.google.gson.Gson
import com.safesphere.app.feature.social.data.remote.CommentDto
import com.safesphere.app.feature.social.data.remote.PlaceDto
import com.safesphere.app.feature.social.data.remote.PlaceRequestDto
import com.safesphere.app.feature.social.data.remote.PostDto
import com.safesphere.app.feature.social.data.remote.PostRequestDto
import com.safesphere.app.feature.social.domain.Audience
import com.safesphere.app.feature.social.domain.CommentPolicy
import com.safesphere.app.feature.social.domain.PostDraft
import com.safesphere.app.feature.social.domain.PostType
import com.safesphere.app.feature.social.domain.Precision
import com.safesphere.app.feature.social.domain.TimeBand
import com.safesphere.app.testing.FakeExperienceRepository
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 13B: the DTOs fail closed and the requests send only what the chosen precision allows. */
class ExperienceDtosTest {

    private val gson = Gson()

    @Test
    fun `a post with unknown codes is read as the most private, least capable post`() {
        val post = gson.fromJson("""{"publicId":"p","type":"EXPERIENCE","precision":"STREET","postedPeriod":"TODAY",
            "commentPolicy":"APPROVE","canComment":null,"audience":"FRIENDS"}""", PostDto::class.java).toDomain()!!
        assertEquals(Precision.NONE, post.precision)
        assertEquals(TimeBand.EARLIER, post.postedPeriod)
        assertEquals(CommentPolicy.NOBODY, post.commentPolicy)
        assertFalse(post.canComment)
        assertNull(post.audience)
        assertFalse(post.own)
    }

    @Test
    fun `items missing what they need are dropped`() {
        assertNull(gson.fromJson("""{"type":"EXPERIENCE"}""", PostDto::class.java).toDomain())
        assertNull(gson.fromJson("""{"publicId":"p","type":"TAKE"}""", PostDto::class.java).toDomain())
        assertNull(gson.fromJson("""{"publicId":"c","kind":"LIKE"}""", CommentDto::class.java).toDomain())
        assertNull(gson.fromJson("""{"publicId":"x","name":"Cafe","category":"SHOPPING","cell":"tf2c1xz","areaCell":"tf2c1x"}""",
            PlaceDto::class.java).toDomain())
    }

    @Test
    fun `a hidden perspective stays hidden and not helpful unless the server says otherwise`() {
        val c = gson.fromJson("""{"publicId":"c","kind":"ANSWER"}""", CommentDto::class.java).toDomain()!!
        assertFalse(c.hidden)
        assertFalse(c.canHide)
        assertFalse(c.canMarkHelped)
    }

    @Test
    fun `the request sends the place only for EXACT or AREA, the city only for CITY, no month or tip for an Ask`() {
        val place = FakeExperienceRepository.place()
        fun draft(type: PostType, precision: Precision, audience: Audience = Audience.EVERYONE) = PostDraft(type, " Body ",
            " Tip ", null, place, precision, com.safesphere.app.feature.social.domain.City.CHENNAI, YearMonth.of(2026, 10),
            null, audience, CommentPolicy.EVERYONE, emptyList())
        val area = PostRequestDto.from(draft(PostType.EXPERIENCE, Precision.AREA))
        assertEquals(place.publicId, area.placeId)
        assertNull(area.city)
        assertEquals("2026-10", area.experiencedMonth)
        assertEquals("Tip", area.tip)
        val city = PostRequestDto.from(draft(PostType.EXPERIENCE, Precision.CITY))
        assertNull(city.placeId)
        assertEquals("CHENNAI", city.city)
        val none = PostRequestDto.from(draft(PostType.EXPERIENCE, Precision.NONE))
        assertNull(none.placeId)
        assertNull(none.city)
        val ask = PostRequestDto.from(draft(PostType.ASK, Precision.NONE))
        assertNull(ask.experiencedMonth)
        assertNull(ask.tip)
        assertEquals("NOBODY", PostRequestDto.from(draft(PostType.EXPERIENCE, Precision.NONE, Audience.ONLY_ME)).commentPolicy)
        // No coordinate field exists in any request.
        val json = gson.toJson(area)
        assertFalse(json.contains("latitude") || json.contains("longitude"))
    }

    @Test
    fun `place cells never appear in logs`() {
        assertFalse(PlaceRequestDto("Cafe", "FOOD_CAFES", "tf2c1xz").toString().contains("tf2c1xz"))
        assertFalse(PlaceDto("p", "Cafe", "FOOD_CAFES", "tf2c1xz", "tf2c1x").toString().contains("tf2c1x"))
        assertTrue(com.safesphere.app.feature.social.domain.PrivatePlace("1",
            com.safesphere.app.feature.social.domain.PrivatePlaceKind.HOME, "tf2c1xz").toString().contains("<hidden>"))
    }
}
