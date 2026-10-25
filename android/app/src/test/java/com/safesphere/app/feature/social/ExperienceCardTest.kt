package com.safesphere.app.feature.social

import com.safesphere.app.feature.social.domain.Audience
import com.safesphere.app.feature.social.domain.ButterflyState
import com.safesphere.app.feature.social.domain.City
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
import com.safesphere.app.feature.social.ui.PostPageText
import com.safesphere.app.feature.social.ui.StampPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 13F (F13): what a postcard shows, decided without Android. */
class ExperienceCardTest {

    private fun post(
        body: String = "Filter coffee at the stall behind the beach\nA steel tumbler and a bench facing the sea.",
        precision: Precision = Precision.EXACT,
        collapsed: Collapsed? = null,
        strong: Boolean = false,
        own: Boolean = false,
        type: PostType = PostType.EXPERIENCE
    ) = Post(
        publicId = "p1", type = type, author = PersonSummary("a1", "asha", "Asha"), own = own, body = body,
        tip = "Go before 7.", category = null, precision = precision, placeName = "Elliot's Beach", areaCell = "tf3rq5",
        city = City.CHENNAI, experiencedMonth = null, daypart = null, postedPeriod = TimeBand.THIS_WEEK, edited = false,
        media = listOf(MediaRef("m1", 1600, 1200)), audience = if (own) Audience.CONNECTIONS else null,
        commentPolicy = CommentPolicy.EVERYONE, canComment = true, visibleFrom = null, yourButterfly = ButterflyState.WANT,
        collapsed = collapsed, strongLanguage = strong
    )

    @Test
    fun `a collapsed post shows no words, photo or tip anywhere on its card`() {
        for (collapse in Collapsed.entries) {
            val card = ExperienceCards.of(post(collapsed = collapse))
            assertNull(card.headline)
            assertNull(card.excerpt)
            assertNull(card.tip)
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
    fun `the postmark says only what the author revealed`() {
        assertEquals(StampPlace.Exact("Elliot's Beach"), ExperienceCards.of(post(precision = Precision.EXACT)).place)
        assertEquals(StampPlace.Area("tf3rq5"), ExperienceCards.of(post(precision = Precision.AREA)).place)
        assertEquals(StampPlace.InCity(City.CHENNAI), ExperienceCards.of(post(precision = Precision.CITY)).place)
        assertEquals(StampPlace.Withheld, ExperienceCards.of(post(precision = Precision.NONE)).place)
    }

    @Test
    fun `the first line is the headline, the rest the excerpt, and long text is cut at a word`() {
        val card = ExperienceCards.of(post())
        assertEquals("Filter coffee at the stall behind the beach", card.headline)
        assertEquals("A steel tumbler and a bench facing the sea.", card.excerpt)
        val long = "word ".repeat(40).trim()
        val (headline, excerpt) = ExperienceCards.split(long)
        assertTrue(headline.endsWith("…") && headline.length <= ExperienceCards.HEADLINE_MAX + 1)
        assertTrue(excerpt!!.isNotEmpty())
    }

    @Test
    fun `own cards carry the audience, others' never do`() {
        assertEquals(Audience.CONNECTIONS, ExperienceCards.of(post(own = true)).audience)
        assertNull(ExperienceCards.of(post()).audience)
        assertTrue(ExperienceCards.of(post(type = PostType.ASK)).isAsk)
    }

    @Test
    fun `the post page never drops a word`() {
        for (body in listOf("One line only", "Headline\nand the rest of it", "x".repeat(200), "Short\n\nTwo paragraphs\nhere")) {
            val (headline, rest) = PostPageText.split(body)
            assertEquals(body.trim().replace("\n", " ").replace(Regex("\\s+"), " "),
                listOfNotNull(headline, rest.ifEmpty { null }).joinToString(" ").replace("\n", " ").replace(Regex("\\s+"), " "))
        }
    }
}
