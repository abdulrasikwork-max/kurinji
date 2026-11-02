package com.safesphere.app.feature.social

import com.safesphere.app.core.location.GeoHash
import com.safesphere.app.feature.social.data.PhotoPreparer
import com.safesphere.app.feature.social.domain.Audience
import com.safesphere.app.feature.social.domain.City
import com.safesphere.app.feature.social.domain.CommentPolicy
import com.safesphere.app.feature.social.domain.ComposerIssue
import com.safesphere.app.feature.social.domain.ComposerPolicy
import com.safesphere.app.feature.social.domain.PostDraft
import com.safesphere.app.feature.social.domain.PostType
import com.safesphere.app.feature.social.domain.Precision
import com.safesphere.app.feature.social.domain.PrivatePlacePolicy
import com.safesphere.app.feature.social.domain.TimeBand
import com.safesphere.app.feature.social.ui.TimeBandTexts
import com.safesphere.app.testing.FakeExperienceRepository
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 13B: ComposerPolicy (mirror of the server's PostPolicy), PrivatePlacePolicy, TimeBandTexts, photo sizes. */
class ExperiencePolicyTest {

    private val month = YearMonth.of(2026, 10)
    private val place = FakeExperienceRepository.place()

    private fun draft(
        type: PostType = PostType.EXPERIENCE, body: String = "Went early.", tip: String = "", precision: Precision? = Precision.NONE,
        withPlace: Boolean = false, city: City? = null, month: YearMonth? = this.month, audience: Audience? = Audience.EVERYONE,
        photos: Int = 0
    ) = PostDraft(type, body, tip, null, if (withPlace) place else null, precision, city, month, null, audience,
        CommentPolicy.EVERYONE, List(photos) { "m$it" })

    @Test
    fun `composer - a complete experience has no issues`() {
        assertTrue(ComposerPolicy.issues(draft(), month, false).isEmpty())
    }

    @Test
    fun `composer - lengths, months and the audience mirror the server`() {
        assertEquals(listOf(ComposerIssue.BODY_EMPTY), ComposerPolicy.issues(draft(body = "  "), month, false))
        assertEquals(listOf(ComposerIssue.BODY_TOO_LONG), ComposerPolicy.issues(draft(body = "x".repeat(5001)), month, false))
        assertTrue(ComposerPolicy.issues(draft(body = "x".repeat(5000)), month, false).isEmpty())
        assertEquals(listOf(ComposerIssue.TIP_TOO_LONG), ComposerPolicy.issues(draft(tip = "t".repeat(301)), month, false))
        assertEquals(listOf(ComposerIssue.NO_MONTH), ComposerPolicy.issues(draft(month = null), month, false))
        assertEquals(listOf(ComposerIssue.MONTH_IN_FUTURE), ComposerPolicy.issues(draft(month = month.plusMonths(1)), month, false))
        assertEquals(listOf(ComposerIssue.NO_AUDIENCE), ComposerPolicy.issues(draft(audience = null), month, false))
        assertEquals(listOf(ComposerIssue.TOO_MANY_PHOTOS), ComposerPolicy.issues(draft(photos = 7), month, false))
        // Tamil text counts characters, not bytes.
        assertTrue(ComposerPolicy.issues(draft(body = "மெ".repeat(2500)), month, false).isEmpty())
    }

    @Test
    fun `composer - an ask needs a real question and has no month`() {
        assertEquals(listOf(ComposerIssue.ASK_TOO_SHORT), ComposerPolicy.issues(draft(type = PostType.ASK, body = "Is it ok?", month = null), month, false))
        assertTrue(ComposerPolicy.issues(draft(type = PostType.ASK, body = "Anyone been at night?", month = null), month, false).isEmpty())
        assertEquals(listOf(ComposerIssue.ASK_TOO_LONG), ComposerPolicy.issues(draft(type = PostType.ASK, body = "x".repeat(1001)), month, false))
    }

    @Test
    fun `composer - where - place precisions need a place that is not private, city needs a city`() {
        assertEquals(listOf(ComposerIssue.NO_PRECISION), ComposerPolicy.issues(draft(precision = null), month, false))
        assertEquals(listOf(ComposerIssue.NO_PLACE), ComposerPolicy.issues(draft(precision = Precision.EXACT), month, false))
        assertTrue(ComposerPolicy.issues(draft(precision = Precision.AREA, withPlace = true), month, false).isEmpty())
        assertEquals(listOf(ComposerIssue.PRIVATE_PLACE),
            ComposerPolicy.issues(draft(precision = Precision.EXACT, withPlace = true), month, placeIsPrivate = true))
        assertEquals(listOf(ComposerIssue.NO_CITY), ComposerPolicy.issues(draft(precision = Precision.CITY), month, false))
        assertTrue(ComposerPolicy.issues(draft(precision = Precision.CITY, city = com.safesphere.app.feature.social.domain.Cities.find("MADURAI")), month, true).isEmpty())
        assertEquals(Precision.entries.toList(), ComposerPolicy.allowedPrecisions(place, false))
        assertEquals(listOf(Precision.CITY, Precision.NONE), ComposerPolicy.allowedPrecisions(place, true))
        assertEquals(listOf(Precision.CITY, Precision.NONE), ComposerPolicy.allowedPrecisions(null, false))
    }

    @Test
    fun `composer - only an exact place is delayed, only me takes no perspectives, months offered newest first`() {
        assertTrue(ComposerPolicy.isDelayed(Precision.EXACT))
        Precision.entries.filter { it != Precision.EXACT }.forEach { assertFalse(ComposerPolicy.isDelayed(it)) }
        assertEquals(CommentPolicy.NOBODY, ComposerPolicy.effectiveCommentPolicy(Audience.ONLY_ME, CommentPolicy.EVERYONE))
        assertEquals(CommentPolicy.CONNECTIONS, ComposerPolicy.effectiveCommentPolicy(Audience.EVERYONE, CommentPolicy.CONNECTIONS))
        val months = ComposerPolicy.monthsOffered(month)
        assertEquals(24, months.size)
        assertEquals(month, months.first())
        assertEquals(month.minusMonths(23), months.last())
    }

    @Test
    fun `private places - the same cell and its eight neighbours are private, further away is not`() {
        val home = GeoHash.encode(13.0500, 80.2824, GeoHash.BLOCK)
        val around = PrivatePlacePolicy.neighbourhood(home)
        assertEquals(9, around.size)
        assertTrue(PrivatePlacePolicy.isPrivate(home, listOf(home)))
        around.forEach { assertTrue("neighbour $it", PrivatePlacePolicy.isPrivate(it, listOf(home))) }
        val farAway = GeoHash.encode(13.0500, 80.2924, GeoHash.BLOCK)   // ~1 km east
        assertFalse(PrivatePlacePolicy.isPrivate(farAway, listOf(home)))
        assertFalse(PrivatePlacePolicy.isPrivate(home, emptyList()))
        assertFalse(PrivatePlacePolicy.isPrivate(null, listOf(home)))
        assertFalse(PrivatePlacePolicy.isPrivate(home.take(6), listOf(home)))         // not a 7-character cell
    }

    @Test
    fun `time bands - each band has its own words`() {
        val texts = TimeBand.entries.map(TimeBandTexts::of)
        assertEquals(TimeBand.entries.size, texts.toSet().size)
    }

    @Test
    fun `photos - 17C the longer side ends at most 3072 px at quality 92, never scaled up`() {
        assertEquals(3072, PhotoPreparer.MAX_SIDE)
        assertEquals(92, PhotoPreparer.QUALITY)
        assertEquals(92, PhotoPreparer.QUALITIES.first())                   // lower only to fit the server's 5 MB
        assertEquals(3072 to 2304, PhotoPreparer.targetSize(4096, 3072))
        assertEquals(2304 to 3072, PhotoPreparer.targetSize(3072 * 3, 3072 * 4))
        assertEquals(1000 to 800, PhotoPreparer.targetSize(1000, 800))
        assertEquals(3072 to 3072, PhotoPreparer.targetSize(3072, 3072))
        assertEquals(1, PhotoPreparer.sampleSize(3000, 2000))
        assertEquals(1, PhotoPreparer.sampleSize(4096, 3072))               // 12.6 MP decoded whole: full detail
        assertEquals(2, PhotoPreparer.sampleSize(9000, 6000))
    }

    @Test
    fun `photos - 17C at most 16 million pixels are decoded, so a huge camera photo cannot run out of memory`() {
        assertEquals(2, PhotoPreparer.sampleSize(6000, 4500))               // 27 MP -> 6.75 MP
        assertEquals(2, PhotoPreparer.sampleSize(8160, 6120))               // 50 MP -> 12.5 MP, still >= 3072 px
        assertEquals(4, PhotoPreparer.sampleSize(16320, 12240))             // 200 MP -> 12.5 MP
        for ((w, h) in listOf(4000 to 3000, 6000 to 4500, 8160 to 6120, 12000 to 9000, 16320 to 12240, 20000 to 400)) {
            val s = PhotoPreparer.sampleSize(w, h)
            assertTrue("$w x $h", (w / s).toLong() * (h / s) <= PhotoPreparer.MAX_DECODED_PIXELS)
        }
    }

}
