package com.safesphere.app.feature.social.domain

import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale

/**
 * Phase 13C: the butterfly, the Cocoon and Discover as the app sees them. Nothing here is stored on the phone; every
 * screen loads what it shows. No model carries a count except [ButterflyTally], which only the author of a post gets.
 */

/** A Cocoon item: "want to experience" until the person marks it "been". */
enum class ButterflyState { WANT, BEEN }

/** What the author of an Experience may learn: a number only from 3 people up (C2). */
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

data class CocoonItem(val post: Post, val state: ButterflyState, val savedPeriod: TimeBand)

data class CocoonPage(val items: List<CocoonItem>, val nextCursor: String?)

data class PlacePage(val place: Place, val items: List<Post>, val nextCursor: String?)

/**
 * Phase 13C (C5): the Edition city a profile's free-text city names, if it names one of the launch cities. Compares
 * letters only, case- and accent-insensitive, and knows a few common other names ("Madras", "Trichy", "Pondy",
 * "Bangalore", "Tuticorin", "Tanjore"). Pure.
 */
object CityMatch {

    private val ALIASES: Map<String, City> = buildMap {
        for (city in City.entries) if (city != City.OTHER) put(key(city.name), city)
        put("madras", City.CHENNAI)
        put("trichy", City.TIRUCHIRAPPALLI)
        put("tiruchi", City.TIRUCHIRAPPALLI)
        put("trichinopoly", City.TIRUCHIRAPPALLI)
        put("kovai", City.COIMBATORE)
        put("pondicherry", City.PUDUCHERRY)
        put("pondy", City.PUDUCHERRY)
        put("bangalore", City.BENGALURU)
        put("bengaluru", City.BENGALURU)
        put("tuticorin", City.THOOTHUKUDI)
        put("tanjore", City.THANJAVUR)
        put("nellai", City.TIRUNELVELI)
    }

    /** The city [text] names, or null (blank, unknown, or "Other"). */
    fun of(text: String?): City? {
        if (text.isNullOrBlank()) return null
        return ALIASES[key(text)]
    }

    private fun key(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).lowercase(Locale.ROOT).filter { it in 'a'..'z' }
}
