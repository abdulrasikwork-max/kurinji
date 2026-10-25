package com.safesphere.app.core.textcheck

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Phase 13E: the phone and the server see text the same way. Every line of the shared vector files (written by
 * moderation-service/tools/make_vectors.py from the Python code) must come out identical here: the normaliser, its
 * keys, the lexicon evidence and the linear model's scores. The lexicon in the app is byte-identical to the service's.
 */
class TextCheckParityTest {

    private val service = File("../../moderation-service")
    private val asset = File("src/main/assets/" + HashedLexicon.ASSET)

    @Test
    fun `the normaliser matches the shared vectors`() {
        val lines = vectors("data/vectors/normaliser-vectors.tsv")
        assertTrue(lines.size > 40)
        for (line in lines) {
            val f = line.split('\t')
            val n = TextNormalizer.normalise(unescape(f[0]))
            assertEquals(f[0], f[1], escape(n.text))
            assertEquals(f[0], f[2], escape(n.lightText))
            assertEquals(f[0], f[3], n.chars.joinToString(",") { "${it.start}-${it.end}" })
            assertEquals(f[0], f[4], n.tokens.joinToString(";") { "${escape(it.text)}|${it.script}|${it.start}-${it.end}" })
            assertEquals(f[0], f[5], n.tokens.joinToString(";") { t ->
                PhoneticKey.keys(t).joinToString(",") { (space, key) -> "$space=${escape(key)}" }
            })
            assertEquals(f[0], f[6], n.script())
        }
    }

    @Test
    fun `the app's lexicon is the service's, and holds no readable word`() {
        assertArrayEquals(File(service, "data/lexicon-v1.keys").readBytes(), asset.readBytes())
        val lines = asset.readLines(Charsets.UTF_8)
        assertEquals("SSLEX1\tlex-1\tnorm-1", lines.first())
        lines.drop(1).filter { it.isNotBlank() }.forEach {
            assertTrue(it, it.matches(Regex("^[0-9a-f]{16}\t[A-Z_]+\t[0-3]\t(exact|prefix)\t[1-5]$")))
        }
        assertTrue(HashedLexicon(lines).size > 300)
    }

    @Test
    fun `the evidence matches the shared vectors`() {
        // restricted/ is never in git (sensitive): these vectors run only where the team keeps it.
        assumeTrue("restricted/ not present", File(service, "restricted/evidence-vectors.tsv").exists())
        val check = TextCheck(HashedLexicon(asset.readLines(Charsets.UTF_8)))
        val lines = vectors("restricted/evidence-vectors.tsv")
        assertTrue(lines.size > 200)
        for (line in lines) {
            val f = line.split('\t')
            val text = unescape(f[0])
            val result = check.check(text)
            assertEquals(f[0], f[1], result.evidence.joinToString(";") { it.code() })
            assertEquals(f[0], f[2] == "1", result.strongLanguage)
            assertEquals(f[0], f[3] == "1", result.selfHarm)
        }
    }

    @Test
    fun `everyday words never hit a scored kind`() {
        val lexicon = HashedLexicon(asset.readLines(Charsets.UTF_8))
        val scored = setOf("PROFANITY", "INSULT", "SEXUAL", "SLUR", "THREAT", "SPAM", "SELF_HARM", "DEHUMAN")
        val words = File(service, "data/vectors/innocent-words.txt").readLines(Charsets.UTF_8)
            .filter { !it.startsWith("#") }.flatMap { it.split(' ').filter(String::isNotBlank) }
        assertTrue(words.size > 400)
        for (word in words) {
            val bad = lexicon.match(TextNormalizer.normalise(word).tokens).filter { it.kind in scored }
            assertTrue("$word -> $bad", bad.isEmpty())
        }
    }

    @Test
    fun `the linear model gives the Python scores`() {
        assertEquals(0x811C9DC5L, LinearModel.fnv1a(""))
        assertEquals(0xE40C292CL, LinearModel.fnv1a("a"))
        assertEquals(0xBF9CF968L, LinearModel.fnv1a("foobar"))
        val model = LinearModel.fromText(File(service, "data/vectors/linear-test-model.lin").readText(Charsets.UTF_8))
        for (line in vectors("data/vectors/linear-vectors.tsv")) {
            val (raw, expected) = line.split('\t')
            val scores = model.scores(unescape(raw))
            for (pair in expected.split(',')) {
                val (name, value) = pair.split('=')
                assertEquals("$raw $name", value.toDouble(), scores.getValue(name), 1e-12)
            }
        }
    }

    private fun vectors(path: String): List<String> =
        File(service, path).readLines(Charsets.UTF_8).filter { it.isNotEmpty() && !it.startsWith("#") }

    companion object {
        /** The escapes of make_vectors.esc: \\ \t \n \r \uXXXX \UXXXXXXXX. */
        fun unescape(text: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c != '\\') {
                    out.append(c)
                    i++
                    continue
                }
                when (val next = text[i + 1]) {
                    'u' -> { out.appendCodePoint(text.substring(i + 2, i + 6).toInt(16)); i += 6 }
                    'U' -> { out.appendCodePoint(text.substring(i + 2, i + 10).toInt(16)); i += 10 }
                    else -> {
                        out.append(when (next) { 't' -> '\t'; 'n' -> '\n'; 'r' -> '\r'; else -> '\\' })
                        i += 2
                    }
                }
            }
            return out.toString()
        }

        fun escape(text: String): String {
            val out = StringBuilder()
            text.codePoints().forEach { cp ->
                when {
                    cp == '\\'.code -> out.append("\\\\")
                    cp == '\t'.code -> out.append("\\t")
                    cp == '\n'.code -> out.append("\\n")
                    cp == '\r'.code -> out.append("\\r")
                    TextNormalizer.invisible(cp) || isOther(cp) ->
                        out.append(if (cp <= 0xFFFF) "\\u%04X".format(cp) else "\\U%08X".format(cp))
                    else -> out.appendCodePoint(cp)
                }
            }
            return out.toString()
        }

        /** Unicode category C* (as Python's unicodedata.category(ch)[0] == "C"). */
        private fun isOther(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED -> true
            else -> false
        }
    }
}
