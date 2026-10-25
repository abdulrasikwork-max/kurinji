package com.safesphere.app.core.textcheck

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Phase 13E lex-1 evidence on the phone: the same rules as the server's kurinji_moderation/detect.py (same vectors,
 * TextCheckTest), used only for the private nudge before sharing (DraftNudge). The server decides; this never does.
 * Rules: a hit is TARGETED by a person marker or an @mention in its sentence; QUOTED inside closed quotation marks
 * (with 3+ words outside) or after a reported-speech marker; identity terms never alone, HATE next to abuse; contact
 * details DOXXING 1 (3 with a possessive phrase); 3 links or a link and a spam phrase SPAM 3; self-harm only a flag.
 */
class TextCheck(private val lexicon: HashedLexicon) {

    data class Evidence(val category: String, val severity: Int, val targeted: Boolean, val quoted: Boolean,
                        val start: Int, val end: Int) {
        /** The shared-vector form CATEGORY:severity:T|U:Q|-@start-end. */
        fun code() = "$category:$severity:${if (targeted) "T" else "U"}:${if (quoted) "Q" else "-"}@$start-$end"
    }

    data class Result(val script: String, val evidence: List<Evidence>, val strongLanguage: Boolean,
                      val selfHarm: Boolean)

    fun check(text: String): Result {
        val norm = TextNormalizer.normalise(text)
        val tokens = norm.tokens
        val hits = lexicon.match(tokens)
        val sentence = sentences(norm)
        val quoted = quoted(norm)

        val targetedSentences = hits.filter { it.kind == "TARGET" }.map { sentence[it.first] }.toMutableSet()
        tokens.forEachIndexed { k, t ->
            if (t.first > 0 && norm.chars[t.first - 1].cp == '@'.code) targetedSentences.add(sentence[k])
        }
        val reportedSentences = hits.filter { it.kind == "REPORTED" }.map { sentence[it.first] }.toSet()

        fun isQuoted(first: Int, last: Int) = (first until last).any { quoted[it] } || sentence[first] in reportedSentences

        val evidence = ArrayList<Evidence>()
        var selfHarm = false
        var strong = false
        for (h in hits) {
            if (h.kind == "PROFANITY" || h.kind == "SLUR" || (h.kind == "INSULT" && h.severity >= 3)) strong = true
            if (h.kind == "SELF_HARM") {
                if (!isQuoted(h.first, h.last)) selfHarm = true
                continue
            }
            if (h.kind !in SCORED) continue
            var targeted = sentence[h.first] in targetedSentences
            val category = when (h.kind) {
                "PROFANITY" -> if (targeted) "HARASSMENT" else "PROFANITY"
                "INSULT" -> "HARASSMENT"
                "SEXUAL" -> {
                    targeted = targeted || h.severity >= 3
                    "SEXUAL_HARASSMENT"
                }
                "SLUR" -> {
                    targeted = targeted || h.severity >= 3
                    "HATE"
                }
                "THREAT" -> "THREAT"
                else -> {
                    targeted = false
                    "SPAM"
                }
            }
            evidence.add(Evidence(category, h.severity, targeted, isQuoted(h.first, h.last), tokens[h.first].start,
                tokens[h.last - 1].end))
        }

        val groups = hits.filter { it.kind == "IDENTITY" || it.kind == "SLUR" }
        for (h in hits) {
            if (h.kind !in ABUSIVE && h.kind != "DEHUMAN") continue
            for (group in groups) {
                if (group === h || sentence[group.first] != sentence[h.first]) continue
                if (maxOf(group.first - h.last, h.first - group.last, 0) > IDENTITY_WINDOW) continue
                val first = minOf(h.first, group.first)
                val last = maxOf(h.last, group.last)
                val severity = if (h.kind == "DEHUMAN" || h.severity >= 2) 3 else 1
                evidence.add(Evidence("HATE", severity, true, isQuoted(first, last), tokens[first].start,
                    tokens[last - 1].end))
            }
        }

        val links = matches(LINK, norm)
        if (links.size >= 3 || (links.isNotEmpty() && evidence.any { it.category == "SPAM" })) {
            evidence.add(span("SPAM", 3, false, links.first(), norm))
        }
        val doxxPhrase = hits.any { it.kind == "DOXX" }
        val contacts = (matches(EMAIL, norm) + matches(PHONE, norm)).sortedBy { it.first }
        for (m in contacts) evidence.add(span("DOXXING", if (doxxPhrase) 3 else 1, doxxPhrase, m, norm))

        evidence.sortWith(compareBy<Evidence>({ it.start }, { it.end }, { it.category }, { it.severity }))
        return Result(norm.script(), merge(evidence).take(MAX_EVIDENCE), strong, selfHarm)
    }

    private fun span(category: String, severity: Int, targeted: Boolean, m: IntRange, norm: TextNormalizer.Normalised) =
        Evidence(category, severity, targeted, false, norm.light[norm.lightIndex[m.first]].start,
            norm.light[norm.lightIndex[m.last]].end)

    /** Each match as the UTF-16 range [start, end - 1] of the light text. */
    private fun matches(pattern: Pattern, norm: TextNormalizer.Normalised): List<IntRange> {
        val m: Matcher = pattern.matcher(norm.lightText)
        val out = ArrayList<IntRange>()
        while (m.find()) out.add(m.start() until m.end())
        return out
    }

    private fun merge(evidence: List<Evidence>): List<Evidence> {
        val merged = LinkedHashMap<Triple<String, Int, Int>, Evidence>()
        for (e in evidence) {
            val key = Triple(e.category, e.start, e.end)
            val m = merged[key]
            merged[key] = if (m == null) e else m.copy(severity = maxOf(m.severity, e.severity),
                targeted = m.targeted || e.targeted, quoted = m.quoted && e.quoted)
        }
        return merged.values.toList()
    }

    private fun sentences(norm: TextNormalizer.Normalised): IntArray {
        val tokens = norm.tokens
        val ids = IntArray(tokens.size)
        var current = 0
        for (k in tokens.indices) {
            if (k > 0) {
                val between = norm.chars.subList(tokens[k - 1].last, tokens[k].first)
                if (between.any { it.cp in SENTENCE_ENDS }) current++
            }
            ids[k] = current
        }
        return ids
    }

    private fun quoted(norm: TextNormalizer.Normalised): BooleanArray {
        val chars = norm.chars
        val inside = BooleanArray(chars.size)
        var i = 0
        while (i < chars.size) {
            val cp = chars[i].cp
            val close = OPEN_QUOTES[cp] ?: if (cp == STRAIGHT_QUOTE) STRAIGHT_QUOTE else null
            if (close == null) {
                i++
                continue
            }
            var j = i + 1
            while (j < chars.size && chars[j].cp != close) j++
            if (j >= chars.size) break // an unclosed quotation mark quotes nothing
            for (k in i + 1 until j) inside[k] = true
            i = j + 1
        }
        val flags = BooleanArray(norm.tokens.size) { t -> (norm.tokens[t].first until norm.tokens[t].last).any { inside[it] } }
        return if (flags.count { !it } < MIN_OUTSIDE_QUOTES) BooleanArray(flags.size) else flags
    }

    private companion object {
        val SENTENCE_ENDS = setOf('.'.code, '!'.code, '?'.code, '\n'.code, '\r'.code, 0x0964)
        val OPEN_QUOTES = mapOf(0x201C to 0x201D, 0x00AB to 0x00BB)
        const val STRAIGHT_QUOTE = '"'.code
        const val IDENTITY_WINDOW = 4
        const val MIN_OUTSIDE_QUOTES = 3
        const val MAX_EVIDENCE = 20
        val SCORED = setOf("PROFANITY", "INSULT", "SEXUAL", "SLUR", "THREAT", "SPAM")
        val ABUSIVE = setOf("PROFANITY", "INSULT", "SLUR")

        // The same ASCII-only patterns as the server (no \b, \s or \w).
        val LINK: Pattern = Pattern.compile("https?://[^ \\t\\n\\r]+|www\\.[^ \\t\\n\\r]+" +
            "|(?<![a-z0-9.-])[a-z0-9-]+\\.(?:com|in|net|org|io|me|ly|co|xyz|info|link|app)(?![a-z0-9])")
        val EMAIL: Pattern = Pattern.compile("(?<![a-z0-9._%+-])[a-z0-9._%+-]+@[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,}")
        val PHONE: Pattern = Pattern.compile("(?<![0-9])(?:\\+?91[ -]?)?[6-9][0-9]{4}[ -]?[0-9]{5}(?![0-9])")
    }
}
