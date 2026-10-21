package com.safesphere.app.core.crypto

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Restore on a new phone (§11): PBKDF2 known answers, the envelope and item-key wraps, and their bindings. */
class RecoveryKeyCryptoTest {

    private val random = SecureRandom()

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `PBKDF2-HMAC-SHA256 matches the RFC 7914 test vectors`() {
        // RFC 7914 §11: P="passwd", S="salt", c=1, first 32 bytes of the 64-byte output.
        assertArrayEquals(
            hex("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc"),
            RecoveryKeyCrypto.pbkdf2Sha256("passwd".toByteArray(), "salt".toByteArray(), 1)
        )
        // RFC 7914 §11: P="Password", S="NaCl", c=80000.
        assertArrayEquals(
            hex("4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56"),
            RecoveryKeyCrypto.pbkdf2Sha256("Password".toByteArray(), "NaCl".toByteArray(), 80_000)
        )
    }

    @Test
    fun `the derived key equals the JDK PBKDF2WithHmacSHA256 for a UTF-8 passphrase`() {
        val passphrase = "river candle mosaic fjord ésprit"
        val salt = RecoveryKeyCrypto.newSalt(random)
        val jdk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(passphrase.toCharArray(), salt, 2_000, 256)).encoded
        assertArrayEquals(jdk, RecoveryKeyCrypto.deriveKek(passphrase.toCharArray(), salt, 2_000))
    }

    @Test
    fun `NFC normalisation and surrounding spaces do not change the key`() {
        val salt = RecoveryKeyCrypto.newSalt(random)
        val composed = RecoveryKeyCrypto.deriveKek("café river lamp".toCharArray(), salt, 1_000)
        val decomposed = RecoveryKeyCrypto.deriveKek("  café river lamp ".toCharArray(), salt, 1_000)
        assertArrayEquals(composed, decomposed)
    }

    @Test
    fun `the envelope opens with the right passphrase only, and is bound to its key version`() {
        val rk = RecoveryKeyCrypto.newRecoveryKey(random)
        val salt = RecoveryKeyCrypto.newSalt(random)
        val kek = RecoveryKeyCrypto.deriveKek("correct river lamp mosaic fjord".toCharArray(), salt, 1_000)
        val envelope = RecoveryKeyCrypto.wrapRecoveryKey(kek, rk, 1, random)
        assertEquals(RecoveryKeyCrypto.WRAPPED_BYTES, envelope.size)
        assertArrayEquals(rk, RecoveryKeyCrypto.openRecoveryKey(kek, envelope, 1))

        val wrong = RecoveryKeyCrypto.deriveKek("correct river lamp mosaic fjords".toCharArray(), salt, 1_000)
        assertThrows(GeneralSecurityException::class.java) { RecoveryKeyCrypto.openRecoveryKey(wrong, envelope, 1) }
        assertThrows(GeneralSecurityException::class.java) { RecoveryKeyCrypto.openRecoveryKey(kek, envelope, 2) }
        val tampered = envelope.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) { RecoveryKeyCrypto.openRecoveryKey(kek, tampered, 1) }
    }

    @Test
    fun `an item wrap opens only for its own item`() {
        val rk = RecoveryKeyCrypto.newRecoveryKey(random)
        val itemKey = ByteArray(32).also(random::nextBytes)
        val ref = "3f1c0000-0000-4000-8000-000000000001"
        val sha = "a".repeat(64)
        val wrapped = RecoveryKeyCrypto.wrapItemKey(rk, itemKey, ref, sha, random)
        assertArrayEquals(itemKey, RecoveryKeyCrypto.openItemKey(rk, wrapped, ref, sha))
        // A server swapping wraps between items (or changing the hash) is detected.
        assertThrows(GeneralSecurityException::class.java) {
            RecoveryKeyCrypto.openItemKey(rk, wrapped, "3f1c0000-0000-4000-8000-000000000002", sha)
        }
        assertThrows(GeneralSecurityException::class.java) { RecoveryKeyCrypto.openItemKey(rk, wrapped, ref, "b".repeat(64)) }
        assertThrows(GeneralSecurityException::class.java) {
            RecoveryKeyCrypto.openItemKey(RecoveryKeyCrypto.newRecoveryKey(random), wrapped, ref, sha)
        }
    }

    @Test
    fun `an empty passphrase is refused`() {
        assertThrows(GeneralSecurityException::class.java) {
            RecoveryKeyCrypto.deriveKek(CharArray(0), RecoveryKeyCrypto.newSalt(random), 1_000)
        }
    }
}
