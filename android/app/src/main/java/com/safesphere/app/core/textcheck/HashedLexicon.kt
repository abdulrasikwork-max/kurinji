package com.safesphere.app.core.textcheck

import com.safesphere.app.core.textcheck.TextNormalizer.Token
import java.security.MessageDigest

/**
 * Phase 13E lex-1 on the phone: the compiled lexicon (assets/textcheck/lexicon-v1.keys, byte-identical to
 * moderation-service/data/lexicon-v1.keys). It holds only truncated SHA-256 digests of the matching keys: the app never
 * contains a readable list of words (design §7.11). Matching hashes the keys of the draft's own words.
 */
class HashedLexicon(lines: List<String>) {

    data class Entry(val kind: String, val severity: Int, val prefix: Boolean, val tokens: Int)

    /** One entry found: tokens [first, last). */
    data class Hit(val kind: String, val severity: Int, val first: Int, val last: Int)

    val version: String
    private val entries: Map<String, Entry>

    init {
        val clean = lines.map { it.trimEnd('\r', '\n') }.filter { it.isNotBlank() }
        val header = clean.first().split('\t')
        require(header[0] == MAGIC && header[2] == TextNormalizer.VERSION) { "not a lex file for this normaliser" }
        version = header[1]
        entries = clean.drop(1).associate { line ->
            val f = line.split('\t')
            f[0] to Entry(f[1], f[2].toInt(), f[3] == "prefix", f[4].toInt())
        }
    }

    val size: Int get() = entries.size

    fun match(tokens: List<Token>): List<Hit> {
        val hits = LinkedHashSet<Hit>()
        for (i in tokens.indices) {
            for (n in 1..MAX_TOKENS) {
                if (i + n > tokens.size) break
                for (space in SPACES) {
                    val keys = tokens.subList(i, i + n).map { PhoneticKey.keyIn(it, space) }
                    if (keys.any { it == null }) continue
                    val words = keys.map { it!! }
                    val found = entries[digest(space, words)]
                    if (found != null && found.tokens == n) {
                        hits.add(Hit(found.kind, found.severity, i, i + n))
                        continue
                    }
                    val last = words.last().codePoints().toArray()
                    for (length in last.size - 1 downTo MIN_PREFIX) {
                        val stem = TextNormalizer.codePoints(last.take(length))
                        val prefix = entries[digest(space, words.dropLast(1) + stem)]
                        if (prefix != null && prefix.prefix && prefix.tokens == n) {
                            hits.add(Hit(prefix.kind, prefix.severity, i, i + n))
                            break
                        }
                    }
                }
            }
        }
        return hits.toList()
    }

    companion object {
        const val MAGIC = "SSLEX1"
        const val ASSET = "textcheck/lexicon-v1.keys"
        private val SPACES = listOf("en", "enc", "ta")
        private const val MAX_TOKENS = 5
        private const val MIN_PREFIX = 5

        fun digest(space: String, keys: List<String>): String {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest("$MAGIC|$space|${keys.joinToString(" ")}".toByteArray(Charsets.UTF_8))
            val out = StringBuilder(16)
            for (b in bytes.take(8)) {
                out.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
            }
            return out.toString()
        }

        private const val HEX = "0123456789abcdef"
    }
}
