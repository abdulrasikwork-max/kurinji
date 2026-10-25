package com.safesphere.app.core.textcheck

import com.safesphere.app.core.textcheck.TextNormalizer.Script
import com.safesphere.app.core.textcheck.TextNormalizer.Token

/**
 * Phase 13E norm-1 matching keys (the Python twin is kurinji_moderation/phonetic.py; same vectors). Three spaces:
 * "en" the word as normalised, "enc" with runs of equal characters collapsed, "ta" a phonetic key (Tanglish spelling
 * variants collapse: zh/l, d/t, oo/u …; Tamil script: ண/ந → ன, ள/ழ → ல, ற → ர, long vowels → short).
 */
object PhoneticKey {

    private val LATIN_DIGRAPHS = listOf("zh" to "l", "sh" to "s", "ch" to "s", "th" to "t", "dh" to "t", "ph" to "p",
        "bh" to "p", "kh" to "k", "gh" to "k", "ng" to "n", "ck" to "k")
    private val LATIN_SINGLE = mapOf('b'.code to "p", 'd'.code to "t", 'g'.code to "k", 'c'.code to "k",
        'q'.code to "k", 'z'.code to "s", 'j'.code to "s", 'w'.code to "v", 'y'.code to "i", 'e'.code to "i",
        'o'.code to "u", 'f'.code to "p", 'x'.code to "ks")

    private val TAMIL_MAP = mapOf(
        0x0BA3 to "ன", 0x0BA8 to "ன",
        0x0BB3 to "ல", 0x0BB4 to "ல",
        0x0BB1 to "ர",
        0x0BC0 to "ி", 0x0BC2 to "ு",
        0x0BC7 to "ெ", 0x0BCB to "ொ",
        0x0BBE to "",
        0x0B86 to "அ", 0x0B88 to "இ", 0x0B8A to "உ", 0x0B8F to "எ", 0x0B93 to "ஒ"
    )
    private const val PULLI = 0x0BCD
    private val TAMIL_CONSONANTS = 0x0B95 until 0x0BBA

    fun collapse(text: String): String {
        val out = StringBuilder()
        var previous = -1
        text.codePoints().forEach { cp ->
            if (cp != previous) out.appendCodePoint(cp)
            previous = cp
        }
        return out.toString()
    }

    fun latinKey(token: String): String {
        val cps = token.codePoints().toArray()
        val out = StringBuilder()
        var i = 0
        while (i < cps.size) {
            val pair = if (i + 1 < cps.size) TextNormalizer.codePoints(listOf(cps[i], cps[i + 1])) else null
            val digraph = LATIN_DIGRAPHS.firstOrNull { it.first == pair }
            if (digraph != null) {
                out.append(digraph.second)
                i += 2
            } else {
                val single = LATIN_SINGLE[cps[i]]
                if (single != null) out.append(single) else out.appendCodePoint(cps[i])
                i++
            }
        }
        return collapse(out.toString())
    }

    fun tamilKey(token: String): String {
        val mapped = StringBuilder()
        token.codePoints().forEach { cp ->
            val to = TAMIL_MAP[cp]
            if (to != null) mapped.append(to) else mapped.appendCodePoint(cp)
        }
        val cps = mapped.codePoints().toArray()
        val out = StringBuilder()
        var i = 0
        while (i < cps.size) {
            // a doubled consonant (C + pulli + C) is written once
            if (cps[i] in TAMIL_CONSONANTS && i + 2 < cps.size && cps[i + 1] == PULLI && cps[i + 2] == cps[i]) {
                i += 2
                continue
            }
            out.appendCodePoint(cps[i])
            i++
        }
        return collapse(out.toString())
    }

    /** The (space, key) pairs a token can match in. */
    fun keys(token: Token): List<Pair<String, String>> = when (token.script) {
        Script.LATIN -> listOf("en" to token.text, "enc" to collapse(token.text), "ta" to latinKey(token.text))
        Script.TAMIL -> listOf("ta" to tamilKey(token.text))
        else -> listOf("en" to token.text)
    }

    /** The key of [token] in [space], or null when the token has none there. */
    fun keyIn(token: Token, space: String): String? = when (space) {
        "ta" -> when (token.script) {
            Script.TAMIL -> tamilKey(token.text)
            Script.LATIN -> latinKey(token.text)
            else -> null
        }
        else -> if (token.script == Script.TAMIL) null else if (space == "enc") collapse(token.text) else token.text
    }
}
