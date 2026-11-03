package com.safesphere.app.feature.social.domain

/**
 * What the phone understood from a typed request: the structured [query] (the city is the person's own default unless they
 * named one) and the [unused] words, shown honestly as "We did not use: ...".
 */
data class Understood(val query: ExploreQuery, val unused: List<String>)

/**
 * Phase 17G-1: understands a typed request from the word list, ON THE PHONE (pure Kotlin, offline, no network). It only
 * INTERPRETS: the server's deterministic engine decides what is found. Rules, each pinned by ExploreQueryParserTest:
 *
 * - words are matched in phrases, longest first (up to the longest term of the list), in the order written;
 * - a city (its name or another name such as "Trichy") sets the city; the first city wins, a second is unused;
 * - at most two categories, four tags and one time of day; a word that would exceed that is unused;
 * - a negation word ("not", "without", ...) makes the NEXT understood word unused ("not quiet" is shown as unused, never
 *   turned into "quiet"): v1 does not understand negation, and says so;
 * - words with no meaning for the search are ignored silently (the STOP words); every other unknown word is unused;
 * - nothing is learned, stored or sent.
 */
class ExploreQueryParser(
    private val terms: Map<String, Term> = ExploreLexicon.terms,
    private val maxWords: Int = ExploreLexicon.maxWords,
    private val cityOf: (String) -> City? = { Cities.match(it) }
) {

    fun parse(text: String, defaultCity: City?): Understood {
        val tokens = ExploreLexicon.normalize(text).split(' ').filter { it.isNotEmpty() }
        var city: City? = null
        val categories = LinkedHashSet<Interest>()
        val facets = LinkedHashSet<String>()
        var daypart: Daypart? = null
        val unused = LinkedHashSet<String>()
        var negated = false

        var i = 0
        while (i < tokens.size) {
            val longest = minOf(maxOf(maxWords, 3), tokens.size - i)
            var consumed = 0
            for (length in longest downTo 1) {
                val phrase = tokens.subList(i, i + length).joinToString(" ")
                val term = terms[phrase]
                val named = if (term == null && length <= 3) cityOf(phrase) else null
                if (term == null && named == null) continue
                consumed = length
                if (named != null) {
                    if (city == null || city == named) city = named else unused += phrase
                    negated = false
                } else when (term!!.kind) {
                    TermKind.NEGATION -> negated = true
                    TermKind.STOP -> Unit
                    else -> {
                        if (negated) {
                            unused += "not $phrase"
                        } else {
                            val taken = when (term.kind) {
                                TermKind.CATEGORY -> Interest.fromCode(term.code)?.let { category ->
                                    category in categories || (categories.size < ExploreQuery.MAX_CATEGORIES && categories.add(category))
                                } ?: false
                                TermKind.FACET -> term.code in facets ||
                                    (facets.size < ExploreFacets.MAX_PER_QUERY && facets.add(term.code))
                                TermKind.DAYPART -> {
                                    val wanted = Daypart.entries.firstOrNull { it.name == term.code }
                                    if (daypart == null || daypart == wanted) { daypart = wanted; true } else false
                                }
                                else -> true
                            }
                            if (!taken) unused += phrase
                        }
                        negated = false
                    }
                }
                break
            }
            if (consumed == 0) {
                unused += tokens[i]
                negated = false
                consumed = 1
            }
            i += consumed
        }
        return Understood(ExploreQuery(city ?: defaultCity, categories.toList(), facets.toList(), daypart), unused.toList())
    }
}
