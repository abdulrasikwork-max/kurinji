package com.safesphere.app.feature.evidence

import com.safesphere.app.feature.evidence.domain.PassphraseGenerator
import com.safesphere.app.feature.evidence.domain.PassphrasePolicy
import com.safesphere.app.feature.evidence.domain.PassphraseProblem
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PassphrasePolicyTest {

    private val policy = PassphrasePolicy(setOf("correcthorsebatterystaple", "passwordpassword1"))

    private fun check(value: String, confirm: String = value, password: String? = "Secret-Login-1") =
        policy.check(value.toCharArray(), confirm.toCharArray(), "asha.rao@example.com", "Asha Rao", password?.toCharArray())

    @Test
    fun `a long random passphrase is accepted`() {
        assertNull(check("velvet lantern orbit thicket meadow"))
    }

    @Test
    fun `too short is refused`() {
        assertEquals(PassphraseProblem.TOO_SHORT, check("short words"))
    }

    @Test
    fun `the name or email name part is refused`() {
        assertEquals(PassphraseProblem.PERSONAL, check("velvet lantern asha meadow"))
        assertEquals(PassphraseProblem.PERSONAL, check("my surname is RAO forever"))
    }

    @Test
    fun `the account password is refused, also inside a longer passphrase`() {
        assertEquals(PassphraseProblem.SAME_AS_PASSWORD, check("Secret-Login-1234", password = "Secret-Login-1234"))
        assertEquals(PassphraseProblem.SAME_AS_PASSWORD, check("lantern secret-login-1 meadow"))
    }

    @Test
    fun `common or repetitive passphrases are refused`() {
        assertEquals(PassphraseProblem.COMMON, check("correct horse battery staple"))
        assertEquals(PassphraseProblem.COMMON, check("abcabcabcabcabcabc"))
        assertEquals(PassphraseProblem.COMMON, check("aaaaaaaaaaaaaaaaaaaa"))
    }

    @Test
    fun `the two entries must match`() {
        assertEquals(PassphraseProblem.MISMATCH, check("velvet lantern orbit thicket meadow", "velvet lantern orbit thicket meadows"))
    }

    @Test
    fun `generated passphrases have five words from the list and pass the policy`() {
        val words = (0 until 2000).map { "word$it" }
        val generator = PassphraseGenerator(words)
        repeat(20) {
            val value = String(generator.generate())
            val parts = value.split(' ')
            assertEquals(5, parts.size)
            assertTrue(parts.all { it in words })
        }
    }

    @Test
    fun `the bundled EFF list parses to 7776 distinct words`() {
        val asset = File("src/main/assets/eff_large_wordlist.txt")
        val words = asset.bufferedReader().useLines(PassphraseGenerator::parseEffList)
        assertEquals(7776, words.size)
        assertTrue(words.none { it.any(Char::isDigit) })
    }
}
