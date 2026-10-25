package com.safesphere.app.feature.social.ui

import com.safesphere.app.feature.social.domain.Audience
import com.safesphere.app.feature.social.domain.ButterflyState
import com.safesphere.app.feature.social.domain.City
import com.safesphere.app.feature.social.domain.Daypart
import com.safesphere.app.feature.social.domain.DiscoverReason
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.MediaRef
import com.safesphere.app.feature.social.domain.Post
import com.safesphere.app.feature.social.domain.PostType
import com.safesphere.app.feature.social.domain.Precision
import com.safesphere.app.feature.social.domain.TimeBand
import java.time.YearMonth

/** Where the postmark says the post is: exactly what the author chose to reveal (Phase 13B precision). */
sealed interface StampPlace {
    data class Exact(val name: String) : StampPlace
    data class Area(val gridRef: String) : StampPlace
    data class InCity(val city: City) : StampPlace
    data object Withheld : StampPlace
}

/**
 * Phase 13F (docs/PHASE13F_PLAN.md F13): what one experience card shows, decided without Android so it can be tested.
 * A collapsed post shows its collapsed line only: no headline, words, photo or tip (13E-D2 kept). The headline is the
 * first line of the body (posts have no title field); the rest is the excerpt.
 */
data class ExperienceCard(
    val id: String,
    val isAsk: Boolean,
    val category: Interest?,
    /** Null when collapsed. */
    val headline: String?,
    val excerpt: String?,
    val tip: String?,
    val photo: MediaRef?,
    /** Why it is collapsed, for the line shown instead of the words; null = not collapsed. */
    val collapse: CardCollapse?,
    val place: StampPlace,
    val month: YearMonth?,
    val daypart: Daypart?,
    val band: TimeBand,
    val authorName: String?,
    val own: Boolean,
    /** Own posts only: who can see it. */
    val audience: Audience?,
    val intent: ButterflyState?,
    val reasons: List<DiscoverReason>
)

/** A collapse by a person or the system (13D/13E), or by the viewer's own "Collapse strong language" (13E E20). */
sealed interface CardCollapse {
    data class ByModeration(val collapsed: com.safesphere.app.feature.social.domain.Collapsed) : CardCollapse
    data object StrongLanguage : CardCollapse
}

object ExperienceCards {

    const val HEADLINE_MAX = 90
    const val EXCERPT_MAX = 180

    fun of(post: Post, reasons: List<DiscoverReason> = emptyList(), collapseStrongLanguage: Boolean = false): ExperienceCard {
        val collapse = when {
            post.collapsed != null -> CardCollapse.ByModeration(post.collapsed)
            collapseStrongLanguage && post.strongLanguage && !post.own -> CardCollapse.StrongLanguage
            else -> null
        }
        val (headline, excerpt) = if (collapse != null) null to null else split(post.body)
        return ExperienceCard(
            id = post.publicId,
            isAsk = post.type == PostType.ASK,
            category = post.category,
            headline = headline,
            excerpt = excerpt,
            tip = if (collapse != null) null else post.tip?.trim()?.takeIf { it.isNotEmpty() },
            photo = if (collapse != null) null else post.media.firstOrNull(),
            collapse = collapse,
            place = placeOf(post),
            month = post.experiencedMonth,
            daypart = post.daypart,
            band = post.postedPeriod,
            authorName = post.author?.displayName,
            own = post.own,
            audience = if (post.own) post.audience else null,
            intent = post.yourButterfly,
            reasons = reasons
        )
    }

    fun placeOf(post: Post): StampPlace = when (post.precision) {
        Precision.EXACT -> post.placeName?.let { StampPlace.Exact(it) } ?: StampPlace.Withheld
        Precision.AREA -> post.areaCell?.let { StampPlace.Area(it) } ?: StampPlace.Withheld
        Precision.CITY -> post.city?.let { StampPlace.InCity(it) } ?: StampPlace.Withheld
        Precision.NONE -> StampPlace.Withheld
    }

    /** The first line (cut at a word near [HEADLINE_MAX]) is the headline; the rest, squeezed to one paragraph, the excerpt. */
    fun split(body: String): Pair<String, String?> {
        val text = body.trim()
        val firstLine = text.lineSequence().firstOrNull().orEmpty().trim()
        val restOfText = text.substringAfter('\n', "").trim()
        val headline: String
        var spill = ""
        if (firstLine.length <= HEADLINE_MAX) {
            headline = firstLine
        } else {
            val cut = firstLine.lastIndexOf(' ', HEADLINE_MAX).takeIf { it > HEADLINE_MAX / 2 } ?: HEADLINE_MAX
            headline = firstLine.take(cut).trimEnd() + "…"
            spill = firstLine.drop(cut).trim()
        }
        val rest = listOf(spill, restOfText).filter { it.isNotEmpty() }.joinToString(" ")
            .replace(Regex("\\s+"), " ").trim()
        val excerpt = when {
            rest.isEmpty() -> null
            rest.length <= EXCERPT_MAX -> rest
            else -> rest.take(EXCERPT_MAX).substringBeforeLast(' ').trimEnd() + "…"
        }
        return headline to excerpt
    }
}
