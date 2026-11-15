package com.safesphere.app.feature.social.domain

import java.time.LocalDate

/**
 * Phase 13C: Discover as the app sees it. Nothing here is stored on the phone; every screen loads what it shows. No
 * model carries a count.
 */

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

data class PlacePage(
    val place: Place,
    val items: List<Post>,
    val nextCursor: String?
)

