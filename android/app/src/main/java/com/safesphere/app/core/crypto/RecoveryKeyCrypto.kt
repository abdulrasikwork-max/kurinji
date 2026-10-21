package com.safesphere.app.core.crypto

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * "Restore my evidence on a new phone" (Phase 5, docs/PHASE5.md §11). Pure JVM code: runs the same in unit tests.
 *
 *   passphrase --PBKDF2-HMAC-SHA256(salt, iterations)--> KEK (32 bytes)
 *   KEK --AES-256-GCM, AAD "SSRK1|<rkVersion>"--> envelope  (the recovery key RK, 32 random bytes per person)
 *   RK  --AES-256-GCM, AAD "SSRW1|<clientRef>|<ciphertextSha256>"--> recovery copy of one item key
 *
 * Every wrapped value is 12-byte IV | ciphertext | 16-byte tag = 60 bytes for a 32-byte key. A wrong passphrase, a
 * tampered envelope or a wrap moved to another item fails the GCM tag check: there is no separate "verifier" for an
 * attacker to test guesses against beyond what the envelope itself allows. The passphrase never leaves the phone.
 *
 * PBKDF2 is implemented here (RFC 8018 §5.2, one output block) over the NFC-normalised UTF-8 bytes of the passphrase,
 * instead of SecretKeyFactory: the encoding is then explicit and identical on every Android version and provider, and
 * the bytes can be wiped afterwards. Checked against the RFC 7914 §11 test vectors and the JDK implementation.
 */
object RecoveryKeyCrypto {

    const val KDF = "PBKDF2-SHA256"
    const val DEFAULT_ITERATIONS = 600_000
    const val KEY_BYTES = 32
    const val SALT_BYTES = 16
    const val WRAPPED_BYTES = 60
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun newRecoveryKey(random: SecureRandom): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    fun newSalt(random: SecureRandom): ByteArray = ByteArray(SALT_BYTES).also(random::nextBytes)

    /** PBKDF2-HMAC-SHA256 with a 32-byte output. The returned key must be wiped by the caller. */
    fun deriveKek(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        require(iterations >= 1) { "iterations" }
        val password = passphraseBytes(passphrase)
        try {
            return pbkdf2Sha256(password, salt, iterations)
        } finally {
            password.fill(0)
        }
    }

    /** RFC 8018 PBKDF2 with HMAC-SHA256, dkLen = 32 (one block, so T1 is the whole key). */
    internal fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        // An empty key is rejected by SecretKeySpec; the passphrase policy never allows it anyway.
        if (password.isEmpty()) throw GeneralSecurityException("Empty passphrase")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val result = u.copyOf()
        for (i in 2..iterations) {
            u = mac.doFinal(u)
            for (j in result.indices) result[j] = (result[j].toInt() xor u[j].toInt()).toByte()
        }
        u.fill(0)
        return result
    }

    fun wrapRecoveryKey(kek: ByteArray, recoveryKey: ByteArray, rkVersion: Int, random: SecureRandom): ByteArray =
        seal(kek, recoveryKey, envelopeAad(rkVersion), random)

    /** Throws [GeneralSecurityException] (AEADBadTagException) for a wrong passphrase or a tampered envelope. */
    fun openRecoveryKey(kek: ByteArray, wrapped: ByteArray, rkVersion: Int): ByteArray =
        open(kek, wrapped, envelopeAad(rkVersion))

    fun wrapItemKey(recoveryKey: ByteArray, itemKey: ByteArray, clientRef: String, ciphertextSha256: String,
                    random: SecureRandom): ByteArray =
        seal(recoveryKey, itemKey, itemAad(clientRef, ciphertextSha256), random)

    fun openItemKey(recoveryKey: ByteArray, wrapped: ByteArray, clientRef: String, ciphertextSha256: String): ByteArray =
        open(recoveryKey, wrapped, itemAad(clientRef, ciphertextSha256))

    private fun envelopeAad(rkVersion: Int) = "SSRK1|$rkVersion".toByteArray(Charsets.UTF_8)

    private fun itemAad(clientRef: String, ciphertextSha256: String) =
        "SSRW1|${clientRef.lowercase()}|${ciphertextSha256.lowercase()}".toByteArray(Charsets.UTF_8)

    private fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray, random: SecureRandom): ByteArray {
        require(key.size == KEY_BYTES && plaintext.size == KEY_BYTES) { "32-byte keys only" }
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plaintext)
    }

    private fun open(key: ByteArray, wrapped: ByteArray, aad: ByteArray): ByteArray {
        if (wrapped.size != WRAPPED_BYTES) throw GeneralSecurityException("Wrapped key has the wrong length")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, wrapped, 0, IV_BYTES))
        cipher.updateAAD(aad)
        return cipher.doFinal(wrapped, IV_BYTES, wrapped.size - IV_BYTES)
    }

    /**
     * NFC-normalised UTF-8 (so "é" typed as one or two code points is the same passphrase), surrounding whitespace
     * removed. Normalizer needs a String: that short-lived copy cannot be wiped (a JVM limitation, noted in §11).
     */
    internal fun passphraseBytes(passphrase: CharArray): ByteArray {
        val normalized = Normalizer.normalize(CharBuffer.wrap(passphrase), Normalizer.Form.NFC).trim()
        val buffer: ByteBuffer = Charsets.UTF_8.encode(CharBuffer.wrap(normalized))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        if (buffer.hasArray()) buffer.array().fill(0)
        return bytes
    }
}
