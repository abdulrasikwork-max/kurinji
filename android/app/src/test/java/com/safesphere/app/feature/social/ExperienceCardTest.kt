package com.safesphere.app.feature.social

import com.safesphere.app.feature.social.domain.Audience
import com.safesphere.app.feature.social.domain.ButterflyState
import com.safesphere.app.feature.social.domain.City
import com.safesphere.app.feature.social.domain.CityAreas
import com.safesphere.app.feature.social.domain.Collapsed
import com.safesphere.app.feature.social.domain.CommentPolicy
import com.safesphere.app.feature.social.domain.MediaRef
import com.safesphere.app.feature.social.domain.PersonSummary
import com.safesphere.app.feature.social.domain.Post
import com.safesphere.app.feature.social.domain.PostType
import com.safesphere.app.feature.social.domain.Precision
import com.safesphere.app.feature.social.domain.TimeBand
import com.safesphere.app.feature.social.ui.CardCollapse
import com.safesphere.app.feature.social.ui.ExperienceCards
import com.safesphere.app.feature.social.ui.StampPlace
import com.safesphere.app.core.ui.components.AvatarView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 16 (docs/PHASE16.md): what a feed card shows, decided without Android. (13F: postcards; replaced.) */
class ExperienceCardTest {

    private fun post(
        body: String = "Filter coffee at the stall behind the beach\nA steel tumbler and a bench facing the sea.",
        precision: Precision = Precision.EXACT,
        collapsed: Collapsed? = null,
        strong: Boolean = false,
        own: Boolean = false,
        type: PostType = PostType.EXPERIENCE,
        comments: Int = 0,
        areaCell: String = "tf3rq5"
    ) = Post(
        publicId = "p1", type = type, author = PersonSummary("a1", "asha", "Asha"), own = own, body = body,
        tip = "Go before 7.", category = null, precision = precision, placeName = "Elliot's Beach", areaCell = areaCell,
        city = City.CHENNAI, experiencedMonth = null, daypart = null, postedPeriod = TimeBand.THIS_WEEK, edited = false,
        media = listOf(MediaRef("m1", 1600, 1200)), audience = if (own) Audience.CONNECTIONS else null,
        commentPolicy = CommentPolicy.EVERYONE, canComment = true, visibleFrom = null, yourButterfly = ButterflyState.WANT,
        collapsed = collapsed, strongLanguage = strong, commentCount = comments
    )

    @Test
    fun `a collapsed post shows no words, photo or tip anywhere on its card`() {
        for (collapse in Collapsed.entries) {
            val card = ExperienceCards.of(post(collapsed = collapse))
            assertNull(card.thought)
            assertFalse(card.hasTip)
            assertNull(card.photo)
            assertEquals(CardCollapse.ByModeration(collapse), card.collapse)
        }
    }

    @Test
    fun `strong language collapses only for a viewer who chose it, and never the viewer's own post`() {
        assertNull(ExperienceCards.of(post(strong = true)).collapse)
        assertEquals(CardCollapse.StrongLanguage, ExperienceCards.of(post(strong = true), collapseStrongLanguage = true).collapse)
        assertNull(ExperienceCards.of(post(strong = true, own = true), collapseStrongLanguage = true).collapse)
    }

    @Test
    fun `the card says only what the author revealed about the place`() {
        assertEquals(StampPlace.Exact("Elliot's Beach"), ExperienceCards.of(post(precision = Precision.EXACT)).place)
        assertEquals(StampPlace.Area("tf3rq5"), ExperienceCards.of(post(precision = Precision.AREA)).place)
        assertEquals(StampPlace.InCity(City.CHENNAI), ExperienceCards.of(post(precision = Precision.CITY)).place)
        assertEquals(StampPlace.Withheld, ExperienceCards.of(post(precision = Precision.NONE)).place)
    }

    @Test
    fun `an area says its launch city from the area cell alone, on the phone, like the server`() {
        assertEquals(City.CHENNAI, CityAreas.cityOf("tf31fw"))       // Chennai's box (the 13F emulator walk-through)
        assertNull(CityAreas.cityOf("u4pruy"))                        // far from every launch city
        assertNull(CityAreas.cityOf("not-a-cell!"))
        assertNull(CityAreas.cityOf(null))
    }

    @Test
    fun `the thought is the post's own words, tidied, and cut at a word only when long`() {
        assertEquals("Filter coffee at the stall behind the beach\nA steel tumbler and a bench facing the sea.",
            ExperienceCards.of(post()).thought)
        assertEquals("One\nTwo", ExperienceCards.thought("  One\n\n\n  Two  "))
        val long = "word ".repeat(100).trim()
        val cut = ExperienceCards.thought(long)
        assertTrue(cut.endsWith("…") && cut.length <= ExperienceCards.THOUGHT_MAX + 1)
        assertTrue(long.startsWith(cut.removeSuffix("…")))
    }

    @Test
    fun `only someone else's Experience can be saved, and the comment count is never negative`() {
        assertTrue(ExperienceCards.of(post()).canSave)
        assertFalse(ExperienceCards.of(post(own = true)).canSave)
        assertFalse(ExperienceCards.of(post(type = PostType.ASK)).canSave)
        assertEquals(4, ExperienceCards.of(post(comments = 4)).commentCount)
        assertEquals(0, ExperienceCards.of(post(comments = -3)).commentCount)
    }

    @Test
    fun `own cards carry the audience, others' never do`() {
        assertEquals(Audience.CONNECTIONS, ExperienceCards.of(post(own = true)).audience)
        assertNull(ExperienceCards.of(post()).audience)
        assertTrue(ExperienceCards.of(post(type = PostType.ASK)).isAsk)
    }

    @Test
    fun `an avatar shows one whole first letter and a stable colour per handle`() {
        assertEquals("A", AvatarView.initialOf("  asha"))
        assertEquals("", AvatarView.initialOf(null))
        assertEquals("க", AvatarView.initialOf("கவிதா"))
        assertEquals(AvatarView.colourIndex("asha"), AvatarView.colourIndex("asha"))
        assertTrue(AvatarView.colourIndex("bala") in 0..5)
        assertEquals(0, AvatarView.colourIndex(null))
    }
}
