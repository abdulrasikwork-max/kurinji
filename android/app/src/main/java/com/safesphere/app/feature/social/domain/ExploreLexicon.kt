package com.safesphere.app.feature.social.domain

import java.util.Locale

/** What a word of a request can mean. */
enum class TermKind { CATEGORY, FACET, DAYPART, NEGATION, STOP }

/** One meaning of a term: the kind and the closed-list code it stands for (`-` for NEGATION and STOP). */
data class Term(val kind: TermKind, val code: String)

/**
 * Phase 17G-1 (decision G-6): the word list Explore uses to understand a typed request ON THE PHONE, English / Tamil /
 * Tanglish, from `explore/explore-lexicon-v1.tsv` plus the tag names of `explore-facets-v1.tsv`. Pure apart from reading the
 * bundled files once. The words never leave the phone.
 *
 * The file is a DRAFT: its Tamil and Tanglish have not been reviewed by a Tamil reader, and Tamil word endings are only
 * recognised where a form is written out. Both limits are said in the file and shown to the person as "We did not use: ...".
 */
object ExploreLexicon {

    private const val RESOURCE = "/explore/explore-lexicon-v1.tsv"

    /** The result of reading a lexicon text: the terms, and every line or term that could not be used (for tests). */
    data class Parsed(val terms: Map<String, Term>, val problems: List<String>)

    /** Normalised phrase -> meaning. First definition of a phrase wins (a duplicate is reported by [parse]). */
    val terms: Map<String, Term> by lazy { load() }

    /** The most words in one term (the parser tries phrases up to this long). */
    val maxWords: Int by lazy { terms.keys.maxOfOrNull { it.count { c -> c == ' ' } + 1 } ?: 1 }

    /**
     * Lower-case, then every character that is not a letter, a digit or a combining sign (the vowel signs of Tamil and other
     * scripts are signs, not letters) becomes one space; runs of spaces collapse; no space at either end.
     */
    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        var lastSpace = true
        for (ch in text.lowercase(Locale.ROOT)) {
            val keep = Character.isLetterOrDigit(ch) || when (Character.getType(ch)) {
                Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt() -> true
                else -> false
            }
            if (keep) {
                out.append(ch)
                lastSpace = false
            } else if (!lastSpace) {
                out.append(' ')
                lastSpace = true
            }
        }
        return out.toString().trimEnd()
    }

    /** Reads the file text and the tag names; reports a bad line, an unknown code or a phrase defined twice. */
    fun parse(text: String, facets: List<ExploreFacet> = ExploreFacets.all): Parsed {
        val terms = LinkedHashMap<String, Term>()
        val problems = mutableListOf<String>()

        fun add(phrase: String, term: Term, source: String) {
            val key = normalize(phrase)
            if (key.isEmpty()) return
            val existing = terms[key]
            if (existing == null) terms[key] = term
            else if (existing != term) problems += "\"$key\" means $existing and $term ($source)"
        }

        text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
            val f = line.split('\t')
            val kind = f.getOrNull(0)?.let { name -> TermKind.entries.firstOrNull { it.name == name } }
            if (f.size != 3 || kind == null) {
                problems += "bad line: ${line.take(40)}"
                return@forEach
            }
            val code = f[1]
            val valid = when (kind) {
                TermKind.CATEGORY -> Interest.fromCode(code) != null
                TermKind.FACET -> ExploreFacets.isValid(code)
                TermKind.DAYPART -> Daypart.entries.any { it.name == code }
                TermKind.NEGATION, TermKind.STOP -> code == "-"
            }
            if (!valid) {
                problems += "unknown code $kind $code"
                return@forEach
            }
            val seenInLine = HashSet<String>()
            f[2].split('|').forEach { raw ->
                val key = normalize(raw)
                if (key.isNotEmpty() && !seenInLine.add(key)) problems += "\"$key\" is written twice in one line"
                add(raw, Term(kind, code), "file")
            }
        }
        // The tag names (English, Tamil, Tanglish) always mean their own tag.
        facets.forEach { facet ->
            add(facet.english, Term(TermKind.FACET, facet.code), "tag name")
            add(facet.tamil, Term(TermKind.FACET, facet.code), "tag name")
            add(facet.tanglish, Term(TermKind.FACET, facet.code), "tag name")
        }
        return Parsed(terms, problems)
    }

    private fun load(): Map<String, Term> {
        val stream = ExploreLexicon::class.java.getResourceAsStream(RESOURCE) ?: return emptyMap()
        return stream.use { parse(it.readBytes().toString(Charsets.UTF_8)).terms }
    }
}
