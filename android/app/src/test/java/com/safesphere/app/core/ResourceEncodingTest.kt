package com.safesphere.app.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Guards the XML resources against a past mistake: a UTF-8 file read as Windows-1252 and saved again turns
 * "…" into "â€¦" and "·" into "Â·" on screen. Every resource file must be valid UTF-8, without a BOM,
 * and free of those double-encoding patterns.
 */
class ResourceEncodingTest {

    private val resDir = File("src/main/res")

    @Test
    fun `resource files are clean UTF-8 without mojibake`() {
        val files = resDir.walkTopDown().filter { it.isFile && it.extension == "xml" }.toList()
        assertTrue("no resource files found in ${resDir.absolutePath}", files.isNotEmpty())

        val problems = files.mapNotNull { file ->
            val bytes = file.readBytes()
            when {
                bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                    "${file.path}: starts with a UTF-8 BOM"
                else -> {
                    val text = decodeStrictUtf8(bytes) ?: return@mapNotNull "${file.path}: not valid UTF-8"
                    MOJIBAKE.find(text)?.let { "${file.path}: double-encoded text \"${it.value}\"" }
                }
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    private fun decodeStrictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private companion object {
        /** UTF-8 lead bytes shown as Windows-1252: "â€" (punctuation), "Ã" (accents), "Â" (Latin-1 symbols). */
        val MOJIBAKE = Regex("â€|Ã[\\u0080-\\u00BF]|Â[\\u00A0-\\u00BF]")
    }
}
