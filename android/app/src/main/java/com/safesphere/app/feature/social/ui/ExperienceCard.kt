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

/** Where a post says it is: exactly what the author chose to reveal (Phase 13B precision). */
sealed interface StampPlace {
    data class Exact(val name: String) : StampPlace
    data class Area(val gridRef: String) : StampPlace
    data class InCity(val city: City) : StampPlace
    data object Withheld : StampPlace
}

/**
 * Phase 16 (docs/PHASE16.md): what one feed card shows, decided without Android so it can be tested. Person first,
 * then the photo, then their own words (the "thought"), where, and the actions. A collapsed post shows its collapsed
 * line only: no words, photo or tip (13E-D2 kept). [commentCount] is the one public number (U1).
 */
data class ExperienceCard(
    val id: String,
    val isAsk: Boolean,
    val category: Interest?,
    /** The post's own words, tidied for a card; null when collapsed. */
    val thought: String?,
    /** Whether a tip is inside (the card says so; the tip itself is on the post). Never true when collapsed. */
    val hasTip: Boolean,
    val photo: MediaRef?,
    /** Why it is collapsed, for the line shown instead of the words; null = not collapsed. */
    val collapse: CardCollapse?,
    val place: StampPlace,
    val month: YearMonth?,
    val daypart: Daypart?,
    val band: TimeBand,
    val authorName: String?,
    val authorHandle: String?,
    val own: Boolean,
    /** Own posts only: who can see it. */
    val audience: Audience?,
    /** The viewer's own save state (never anyone else's). */
    val intent: ButterflyState?,
    /** Only someone else's Experience can be saved (13C: never your own post, never a Question). */
    val canSave: Boolean,
    val commentCount: Int,
    val reasons: List<DiscoverReason>
)

/** A collapse by a person or the system (13D/13E), or by the viewer's own "Collapse strong language" (13E E20). */
sealed interface CardCollapse {
    data class ByModeration(val collapsed: com.safesphere.app.feature.social.domain.Collapsed) : CardCollapse
    data object StrongLanguage : CardCollapse
}

object ExperienceCards {

    /** Enough words for four lines on a phone; the card cuts at a word and the post shows everything. */
    const val THOUGHT_MAX = 320

    fun of(post: Post, reasons: List<DiscoverReason> = emptyList(), collapseStrongLanguage: Boolean = false): ExperienceCard {
        val collapse = when {
            post.collapsed != null -> CardCollapse.ByModeration(post.collapsed)
            collapseStrongLanguage && post.strongLanguage && !post.own -> CardCollapse.StrongLanguage
            else -> null
        }
        return ExperienceCard(
            id = post.publicId,
            isAsk = post.type == PostType.ASK,
            category = post.category,
            thought = if (collapse != null) null else thought(post.body),
            hasTip = collapse == null && !post.tip.isNullOrBlank(),
            photo = if (collapse != null) null else post.media.firstOrNull(),
            collapse = collapse,
            place = placeOf(post),
            month = post.experiencedMonth,
            daypart = post.daypart,
            band = post.postedPeriod,
            authorName = post.author?.displayName,
            authorHandle = post.author?.handle,
            own = post.own,
            audience = if (post.own) post.audience else null,
            intent = post.yourButterfly,
            canSave = post.type == PostType.EXPERIENCE && !post.own,
            commentCount = post.commentCount.coerceAtLeast(0),
            reasons = reasons
        )
    }

    fun placeOf(post: Post): StampPlace = when (post.precision) {
        Precision.EXACT -> post.placeName?.let { StampPlace.Exact(it) } ?: StampPlace.Withheld
        Precision.AREA -> post.areaCell?.let { StampPlace.Area(it) } ?: StampPlace.Withheld
        Precision.CITY -> post.city?.let { StampPlace.InCity(it) } ?: StampPlace.Withheld
        Precision.NONE -> StampPlace.Withheld
    }

    /** The post's words for a card: blank lines squeezed, cut at a word near [THOUGHT_MAX] with "…". */
    fun thought(body: String): String {
        val text = body.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        if (text.length <= THOUGHT_MAX) return text
        val cut = text.lastIndexOf(' ', THOUGHT_MAX).takeIf { it > THOUGHT_MAX / 2 } ?: THOUGHT_MAX
        return text.take(cut).trimEnd() + "…"
    }
}
