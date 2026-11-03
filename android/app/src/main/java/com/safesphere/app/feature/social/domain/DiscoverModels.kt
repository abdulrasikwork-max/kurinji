package com.safesphere.app.feature.social.domain

import java.time.LocalDate

/**
 * Phase 13C: Discover as the app sees it; Phase 17E (D-3): the butterfly is a reaction and Save a private bookmark.
 * Nothing here is stored on the phone; every screen loads what it shows. No model carries a count except
 * [ButterflyTally], which only the author of a post gets.
 */

/** What the author of an Experience may learn about its butterflies (reactions): a number only from 3 people up (C2). */
data class ButterflyTally(val count: Int?, val fewerThanThree: Boolean) {
    /** A tally the app can trust: a number of at least 3, or "fewer than 3" without a number. */
    val isConsistent: Boolean get() = if (fewerThanThree) count == null else count != null && count >= FLOOR

    companion object {
        const val FLOOR = 3
    }
}

/** Why a post was picked ("Why am I seeing this?"). Unknown codes from a newer server are dropped. */
enum class DiscoverReason {
    IN_THIS_CITY, RECENT, HAS_PLACE, HAS_WHEN, HAS_TIP, MATCHES_YOUR_INTERESTS, BEEN_HERE_TOO, OPEN_ASK,
    THEME_OF_THE_WEEK, FROM_A_CONNECTION,
    /** Phase 16 (U2): someone commented recently ("Talking now"). */
    NEW_PERSPECTIVES
}

data class DiscoverItem(val post: Post, val reasons: List<DiscoverReason>)

/** Phase 16 (U2): TALKING = "Talking now", ordered by the newest comment, never by totals. */
enum class EditionSectionKind { CITY, ASKS, THEME, TALKING }

data class EditionSection(val kind: EditionSectionKind, val items: List<DiscoverItem>)

/** The weekly Edition for one city. It always ends; there is no "more". */
data class Edition(val city: City, val weekOf: LocalDate?, val theme: Interest?, val sections: List<EditionSection>) {
    val isEmpty: Boolean get() = sections.all { it.items.isEmpty() }
}

data class DiscoverPage(val items: List<DiscoverItem>, val nextCursor: String?)

/** Phase 17E: one saved post as the owner may see it now, and when it was saved (a band). */
data class SavedItem(val post: Post, val savedPeriod: TimeBand)

data class SavedPage(val items: List<SavedItem>, val nextCursor: String?)

/**
 * Phase 17G-2 (U5): the caller's OWN record that they went to a place: [rating] is their own 1-5 rating, or null (went, not rated).
 * Nobody else's visit is ever delivered to the phone.
 */
data class Visit(val rating: Int?) {
    companion object {
        const val MIN = 1
        const val MAX = 5

        fun isValid(value: Int) = value in MIN..MAX
    }
}

/**
 * Phase 17G-2 (U5): the one public rating of a place, as the server's TEXT ("4.3"). It is only read as text and shown with the words
 * "from people who went"; there is no count, no distribution and nothing is ever sorted by it. Anything that is not one digit, a dot
 * and one digit (1.0 to 5.9) is dropped: unreadable means "no rating", never a guess.
 */
object PlaceRating {
    private val SHAPE = Regex("[1-5]\\.[0-9]")

    fun clean(text: String?): String? = text?.takeIf { SHAPE.matches(it) }
}

/** [rating] is the public average text or null; [yourVisit] is the caller's own record or null. Neither orders [items]. */
data class PlacePage(
    val place: Place,
    val items: List<Post>,
    val nextCursor: String?,
    val rating: String? = null,
    val yourVisit: Visit? = null
)

