package com.safesphere.app.core.textcheck

import java.text.Normalizer

/**
 * Phase 13E norm-1: the text normaliser shared with the server (moderation-service/NORMALISER.md; the Python twin is
 * kurinji_moderation/normalise.py). Both give the same output for every line of
 * moderation-service/data/vectors/normaliser-vectors.tsv (TextNormalizerTest).
 *
 * The normalised text is a MATCHING VIEW only: the draft is never changed by it. Every normalised character keeps the
 * code-point range [start, end) of the raw text it came from, so a flagged phrase can be marked in the person's own
 * words. Pure Kotlin: no network, no storage, no logging (TextCheckIsolationTest): drafts never leave the phone.
 */
object TextNormalizer {

    const val VERSION = "norm-1"

    private val INVISIBLE_SINGLE = setOf(0x00AD, 0x034F, 0x061C, 0x115F, 0x1160, 0x17B4, 0x17B5, 0x3164, 0xFEFF, 0xFFA0)
    private val INVISIBLE_RANGES = listOf(0x180B..0x180F, 0x200B..0x200F, 0x202A..0x202E, 0x2060..0x2064,
        0x2066..0x206F, 0xFE00..0xFE0F, 0x1D173..0x1D17A, 0xE0000..0xE007F, 0xE0100..0xE01EF)

    private val HOMOGLYPHS: Map<Int, Char> = mapOf(
        0x0131 to 'i',
        0x0430 to 'a', 0x0435 to 'e', 0x043E to 'o', 0x0440 to 'p', 0x0441 to 'c', 0x0443 to 'y', 0x0445 to 'x',
        0x043A to 'k', 0x043C to 'm', 0x0442 to 't', 0x043D to 'h', 0x0432 to 'b', 0x0456 to 'i', 0x0458 to 'j',
        0x0455 to 's', 0x0501 to 'd',
        0x0410 to 'a', 0x0415 to 'e', 0x041E to 'o', 0x0420 to 'p', 0x0421 to 'c', 0x0422 to 't', 0x041D to 'h',
        0x041A to 'k', 0x041C to 'm', 0x0412 to 'b', 0x0425 to 'x', 0x0423 to 'y', 0x0406 to 'i', 0x0408 to 'j',
        0x0405 to 's',
        0x03B1 to 'a', 0x03BF to 'o', 0x03C1 to 'p', 0x03BD to 'v', 0x03C4 to 't', 0x03B9 to 'i', 0x03BA to 'k',
        0x03B5 to 'e', 0x03C5 to 'u', 0x03C7 to 'x', 0x0391 to 'a', 0x0392 to 'b', 0x0395 to 'e', 0x0396 to 'z',
        0x0397 to 'h', 0x0399 to 'i', 0x039A to 'k', 0x039C to 'm', 0x039D to 'n', 0x039F to 'o', 0x03A1 to 'p',
        0x03A4 to 't', 0x03A5 to 'y', 0x03A7 to 'x',
        0x0261 to 'g'
    )

    private val LEET: Map<Int, Char> = mapOf('0'.code to 'o', '1'.code to 'i', '3'.code to 'e', '4'.code to 'a',
        '5'.code to 's', '7'.code to 't', '8'.code to 'b', '@'.code to 'a', '$'.code to 's', '!'.code to 'i',
        '|'.code to 'i', 0x20AC to 'e')
    private val INTERIOR_ONLY = setOf('!'.code, '|'.code)
    private val AFTER_LETTER_ONLY = setOf('@'.code)
    private val SEPARATORS = setOf(' '.code, '.'.code, '-'.code, '_'.code, '*'.code, '/'.code)

    /** One normalised code point and the raw code-point range [start, end) it came from. */
    class NChar(var cp: Int, val start: Int, val end: Int)

    enum class Script { TAMIL, LATIN, MIXED, OTHER }

    /** A word of the normalised text; [start]/[end] in raw code points, [first]/[last] indexes into the chars. */
    data class Token(val text: String, val start: Int, val end: Int, val script: Script, val first: Int, val last: Int)

    class Normalised(val chars: List<NChar>, val light: List<NChar>) {
        val text: String = codePoints(chars.map { it.cp })
        val lightText: String = codePoints(light.map { it.cp })
        val tokens: List<Token> = tokensOf(chars)

        /** For each UTF-16 unit of [lightText], the index of its light character (regex positions -> characters). */
        val lightIndex: IntArray = IntArray(lightText.length).also { index ->
            var unit = 0
            light.forEachIndexed { i, c ->
                repeat(Character.charCount(c.cp)) { index[unit++] = i }
            }
        }

        /** TAMIL / LATIN / MIXED / NONE, as the Python twin. */
        fun script(): String {
            val scripts = tokens.map { it.script }.filter { it != Script.OTHER }.toSet()
            return when {
                scripts.isEmpty() -> "NONE"
                scripts == setOf(Script.TAMIL) -> "TAMIL"
                scripts == setOf(Script.LATIN) -> "LATIN"
                else -> "MIXED"
            }
        }
    }

    fun invisible(cp: Int): Boolean = cp in INVISIBLE_SINGLE || INVISIBLE_RANGES.any { cp in it }

    fun isMark(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
        else -> false
    }

    fun isLatinLetter(cp: Int): Boolean = cp in 'a'.code..'z'.code

    fun isWordChar(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER,
        Character.OTHER_LETTER, Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
        Character.DECIMAL_DIGIT_NUMBER -> true
        else -> false
    }

    fun isTamil(cp: Int): Boolean = cp in 0x0B80..0x0BFF

    fun normalise(raw: String): Normalised {
        val rawCps = raw.codePoints().toArray()
        // Step 0: drop invisible characters, keeping each remaining character's raw position.
        val kept = rawCps.indices.filter { !invisible(rawCps[it]) }

        // Step 1: NFKC per cluster (a character and the marks after it), so offsets survive composition.
        var out = ArrayList<NChar>()
        var i = 0
        while (i < kept.size) {
            var j = i + 1
            while (j < kept.size && isMark(rawCps[kept[j]])) j++
            val start = kept[i]
            val end = kept[j - 1] + 1
            val cluster = codePoints((i until j).map { rawCps[kept[it]] })
            Normalizer.normalize(cluster, Normalizer.Form.NFKC).codePoints().forEach { out.add(NChar(it, start, end)) }
            i = j
        }

        // Step 2: homoglyphs, Latin diacritics folded, ASCII lower case.
        for (c in out) {
            val homoglyph = HOMOGLYPHS[c.cp]
            if (homoglyph != null) {
                c.cp = homoglyph.code
            } else if (c.cp in 0x00C0..0x024F) {
                c.cp = Normalizer.normalize(String(Character.toChars(c.cp)), Normalizer.Form.NFD).codePointAt(0)
            }
            if (c.cp in 'A'.code..'Z'.code) c.cp += 32
        }
        val light = out.map { NChar(it.cp, it.start, it.end) }

        leet(out)
        out = joinSingleLetters(out)
        out = squeeze(out)
        return Normalised(out, light)
    }

    private fun leet(chars: MutableList<NChar>) {
        val n = chars.size
        var i = 0
        while (i < n) {
            if (!(isLatinLetter(chars[i].cp) || chars[i].cp in LEET)) {
                i++
                continue
            }
            var j = i
            while (j < n && (isLatinLetter(chars[j].cp) || chars[j].cp in LEET)) j++
            val letters = (i until j).filter { isLatinLetter(chars[it].cp) }
            if (letters.isNotEmpty()) {
                val first = letters.first()
                val last = letters.last()
                for (k in i until j) {
                    val cp = chars[k].cp
                    val mapped = LEET[cp] ?: continue
                    if (cp in INTERIOR_ONLY && !(first < k && k < last)) continue
                    if (cp in AFTER_LETTER_ONLY && first >= k) continue
                    chars[k].cp = mapped.code
                }
            }
            i = j
        }
    }

    private fun joinSingleLetters(chars: List<NChar>): ArrayList<NChar> {
        val n = chars.size
        fun single(p: Int) = isLatinLetter(chars[p].cp) && (p == 0 || !isLatinLetter(chars[p - 1].cp)) &&
            (p == n - 1 || !isLatinLetter(chars[p + 1].cp))

        val drop = HashSet<Int>()
        var p = 0
        while (p < n) {
            if (!single(p)) {
                p++
                continue
            }
            val chain = arrayListOf(p)
            while (chain.last() + 2 < n && chars[chain.last() + 1].cp in SEPARATORS && single(chain.last() + 2)) {
                chain.add(chain.last() + 2)
            }
            if (chain.size >= 3) chain.dropLast(1).forEach { drop.add(it + 1) }
            p = chain.last() + 1
        }
        return ArrayList(chars.filterIndexed { k, _ -> k !in drop })
    }

    private fun squeeze(chars: List<NChar>): ArrayList<NChar> {
        val out = ArrayList<NChar>()
        for (c in chars) {
            if (out.size >= 2 && out[out.size - 1].cp == c.cp && out[out.size - 2].cp == c.cp) continue
            out.add(c)
        }
        return out
    }

    private fun tokensOf(chars: List<NChar>): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        val n = chars.size
        while (i < n) {
            if (!isWordChar(chars[i].cp)) {
                i++
                continue
            }
            var j = i
            while (j < n && isWordChar(chars[j].cp)) j++
            val cps = (i until j).map { chars[it].cp }
            val tamil = cps.any(::isTamil)
            val latin = cps.any(::isLatinLetter)
            val script = when {
                tamil && latin -> Script.MIXED
                tamil -> Script.TAMIL
                latin -> Script.LATIN
                else -> Script.OTHER
            }
            tokens.add(Token(codePoints(cps), chars[i].start, chars[j - 1].end, script, i, j))
            i = j
        }
        return tokens
    }

    internal fun codePoints(cps: List<Int>): String {
        val builder = StringBuilder()
        cps.forEach { builder.appendCodePoint(it) }
        return builder.toString()
    }
}
