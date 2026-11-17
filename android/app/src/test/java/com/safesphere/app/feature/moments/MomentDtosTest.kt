package com.safesphere.app.feature.moments

import com.safesphere.app.feature.moments.data.remote.MineDto
import com.safesphere.app.feature.moments.data.remote.MomentAuthorDto
import com.safesphere.app.feature.moments.data.remote.MomentDto
import com.safesphere.app.feature.moments.data.remote.MomentPhotoDto
import com.safesphere.app.feature.moments.data.remote.MomentPlanDto
import com.safesphere.app.feature.moments.data.remote.NoticeDto
import com.safesphere.app.feature.moments.data.remote.QuestionDto
import com.safesphere.app.feature.moments.data.remote.TodayDto
import com.safesphere.app.feature.moments.data.remote.WhenDto
import com.safesphere.app.feature.moments.data.remote.uuid
import com.safesphere.app.feature.moments.domain.DayPart
import com.safesphere.app.feature.moments.domain.Moment
import com.safesphere.app.feature.moments.domain.MomentAudience
import com.safesphere.app.feature.moments.domain.MomentKind
import com.safesphere.app.feature.moments.domain.MomentPhoto
import com.safesphere.app.feature.moments.domain.MomentStatus
import com.safesphere.app.feature.moments.domain.NoticeState
import com.safesphere.app.feature.moments.domain.RoughDay
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase N4: how the phone reads moments. An answer it does not fully understand is dropped, never guessed; an id is a
 * lower-case UUID or nothing (ids go into URLs); and no model has a field for a count, a place, a viewer or, for other
 * people's moments, an exact time of sharing.
 */
class MomentDtosTest {

    private val id = "0a1b2c3d-0000-4000-8000-00000000000a"
    private val profile = "0a1b2c3d-0000-4000-8000-00000000000b"

    private fun say(
        id: String? = this.id,
        kind: String? = "SAY",
        audience: String? = "COMMUNITY",
        status: String? = "LIVE",
        text: String? = "Canteen coffee is back",
        author: MomentAuthorDto? = MomentAuthorDto(profile, "asha_k", "Asha K", null)
    ) = MomentDto(id = id, kind = kind, audience = audience, author = author, mine = false, text = text,
        `when` = WhenDto("TODAY", "MORNING"), status = status)

    @Test
    fun `a Say from someone else - rough time, no exact times`() {
        val moment = say().toDomain()!!
        assertEquals(MomentKind.SAY, moment.kind)
        assertEquals(MomentAudience.COMMUNITY, moment.audience)
        assertEquals(MomentStatus.LIVE, moment.status)
        assertEquals("Asha K", moment.author.name)
        assertEquals(RoughDay.TODAY, moment.shared?.day)
        assertEquals(DayPart.MORNING, moment.shared?.part)
        assertNull(moment.visibleAt)
        assertNull(moment.expiresAt)
        assertNull(moment.notice)
        assertFalse(moment.mine)
        assertFalse(moment.hiddenFromOthers)
    }

    @Test
    fun `anything this app does not know is dropped, never guessed`() {
        assertNull(say(kind = "POLL").toDomain())
        assertNull(say(audience = "EVERYONE").toDomain())
        assertNull(say(status = "ARCHIVED").toDomain())
        assertNull(say(id = "../../etc/passwd").toDomain())
        assertNull(say(id = id.uppercase()).toDomain())
        assertNull(say(author = MomentAuthorDto(profile, "asha_k", "  ", null)).toDomain())
        assertNull(say(author = null).toDomain())
        assertNull("no words and no photo", say(text = "  ").toDomain())
        val page = TodayDto(moments = listOf(say(), say(kind = "POLL"), say(id = null)), nextCursor = "not-an-id").toDomain()
        assertEquals(1, page.moments.size)
        assertNull("a cursor is an id or nothing", page.nextCursor)
    }

    @Test
    fun `an author without a profile is still shown by their real name`() {
        val moment = say(author = MomentAuthorDto(null, null, "Bala R", "not-a-key")).toDomain()!!
        assertEquals("Bala R", moment.author.name)
        assertNull(moment.author.profileId)
        assertNull(moment.author.handle)
        assertNull("a photo key that is not an id is no photo", moment.author.photo)
    }

    @Test
    fun `a photo alone is a moment, and its shape is kept within sane bounds`() {
        val moment = say(text = null).copy(photo = MomentPhotoDto(1153, 2048)).toDomain()!!
        assertNull(moment.text)
        assertEquals(MomentPhoto.MAX_RATIO, moment.photo!!.ratio, 0.0001f)
        assertEquals(MomentPhoto.MIN_RATIO, MomentPhoto(4000, 100).ratio, 0.0001f)
        assertEquals(0.75f, MomentPhoto(1200, 900).ratio, 0.0001f)
        assertNull("a photo without a size is no photo", say().copy(photo = MomentPhotoDto(0, 10)).toDomain()!!.photo)
    }

    @Test
    fun `a plan belongs to a Who is in only, with the exact time the author wrote`() {
        val plan = MomentPlanDto("2026-11-17T12:30:00Z", " Canteen ")
        val whosIn = say(kind = "WHOS_IN").copy(plan = plan).toDomain()!!
        assertEquals(Instant.parse("2026-11-17T12:30:00Z"), whosIn.plan?.at)
        assertEquals("Canteen", whosIn.plan?.where)
        assertNull(say().copy(plan = plan).toDomain()!!.plan)
        assertNull(say(kind = "WHOS_IN").copy(plan = MomentPlanDto("soon", " ")).toDomain()!!.plan)
    }

    @Test
    fun `the author is told the state of their own moment`() {
        val held = say(status = "WAITING").copy(mine = true, `when` = null,
            notice = NoticeDto("HELD", "NEW_MEMBER_PHOTO", "SYSTEM", false, null)).toDomain()!!
        assertTrue(held.mine)
        assertNull(held.shared)
        assertEquals(NoticeState.HELD, held.notice?.state)
        assertEquals("NEW_MEMBER_PHOTO", held.notice?.reason)
        assertEquals(false, held.notice?.byPerson)
        assertTrue(held.hiddenFromOthers)

        val removed = say().copy(mine = true, notice = NoticeDto("REMOVED", "HATE", "MODERATOR", false, id)).toDomain()!!
        assertEquals(true, removed.notice?.byPerson)
        assertEquals(id, removed.notice?.decisionId)
        assertTrue(removed.hiddenFromOthers)

        val byReports = say().copy(mine = true, notice = NoticeDto("COLLAPSED", null, null, false, null)).toDomain()!!
        assertNull(byReports.notice?.reason)
        assertNull(byReports.notice?.byPerson)
        assertFalse("collapsed is still shown, behind a tap", byReports.hiddenFromOthers)
    }

    @Test
    fun `an unknown notice is read as the most cautious one and a strange reason is not shown`() {
        val notice = say().copy(mine = true, notice = NoticeDto("QUARANTINED", "<b>rude</b>", "ROBOT", null, "x"))
            .toDomain()!!.notice!!
        assertEquals(NoticeState.HELD, notice.state)
        assertNull(notice.reason)
        assertNull(notice.byPerson)
        assertFalse(notice.reviewable)
        assertNull(notice.decisionId)
    }

    @Test
    fun `the question, kept and the support line`() {
        val moment = say().copy(question = QuestionDto(profile, " What made you smile? "), kept = true,
            supportSuggested = true, collapsed = true).toDomain()!!
        assertEquals("What made you smile?", moment.question?.text)
        assertTrue(moment.kept)
        assertTrue(moment.supportSuggested)
        assertTrue(moment.collapsed)
        assertNull(QuestionDto("nope", "text").toDomain())
        assertNull(QuestionDto(profile, " ").toDomain())
        assertEquals(6, MineDto().toDomain().keptMax)
        assertEquals(4, MineDto(keptMax = 4).toDomain().keptMax)
    }

    @Test
    fun `an id is a lower-case UUID or nothing`() {
        assertNotNull(uuid(id))
        assertNull(uuid(null))
        assertNull(uuid(""))
        assertNull(uuid("$id/../x"))
        assertNull(uuid(" $id"))
    }

    @Test
    fun `no moment model has a field for a count, a place, a viewer or a rank`() {
        val forbidden = Regex("(?i)(count|total|views?$|viewer|seen|read|lat|lng|cell|place|city|distance|online|rank|" +
            "score|likes?$|createdAt)")
        val names = Moment::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }
        assertTrue(names.size >= 15)
        assertEquals(emptyList<String>(), names.filter { forbidden.containsMatchIn(it) })
    }
}
