package com.safesphere.app.core.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Phase 5: the SSEV1 evidence container and the escrow key. Pure JVM, no Android. */
class EvidenceCipherTest {

    private val random = SecureRandom()

    @Test
    fun `round trips at every size around the segment boundaries and reports exact hashes`() {
        val key = EvidenceCipher.newKey()
        for (size in listOf(0, 1, 63, 64, 65, 128, 5 * 64 + 7)) {
            val plaintext = ByteArray(size).also(random::nextBytes)
            val out = ByteArrayOutputStream()
            val sealed = EvidenceCipher.encrypt(key, ByteArrayInputStream(plaintext), out, segmentBytes = 64)
            val ciphertext = out.toByteArray()

            assertEquals(size.toLong(), sealed.plaintextBytes)
            assertEquals(ciphertext.size.toLong(), sealed.ciphertextBytes)
            assertEquals(sha256(plaintext), sealed.plaintextSha256)
            assertEquals(sha256(ciphertext), sealed.ciphertextSha256)
            assertArrayEquals("size $size", plaintext, decrypt(key, ciphertext))
        }
    }

    @Test
    fun `the same plaintext never produces the same ciphertext`() {
        val key = EvidenceCipher.newKey()
        val plaintext = "same words".toByteArray()
        assertNotEquals(encrypt(key, plaintext).toList(), encrypt(key, plaintext).toList())
    }

    @Test
    fun `a flipped bit, a wrong key or an edited header is detected`() {
        val key = EvidenceCipher.newKey()
        val ciphertext = encrypt(key, ByteArray(300).also(random::nextBytes))

        val flipped = ciphertext.clone().also { it[200] = (it[200].toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) { decrypt(key, flipped) }
        assertThrows(GeneralSecurityException::class.java) { decrypt(EvidenceCipher.newKey(), ciphertext) }
        val header = ciphertext.clone().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) { decrypt(key, header) }
    }

    @Test
    fun `cutting the file at a segment boundary is detected`() {
        val key = EvidenceCipher.newKey()
        val ciphertext = encrypt(key, ByteArray(3 * 64).also(random::nextBytes))
        val cut = ciphertext.copyOf(EvidenceCipher.HEADER_BYTES + 2 * (64 + EvidenceCipher.TAG_BYTES))
        assertThrows(GeneralSecurityException::class.java) { decrypt(key, cut) }
    }

    @Test
    fun `swapping two segments is detected`() {
        val key = EvidenceCipher.newKey()
        val ciphertext = encrypt(key, ByteArray(3 * 64).also(random::nextBytes))
        val s = 64 + EvidenceCipher.TAG_BYTES
        val h = EvidenceCipher.HEADER_BYTES
        val swapped = ciphertext.clone()
        System.arraycopy(ciphertext, h, swapped, h + s, s)
        System.arraycopy(ciphertext, h + s, swapped, h, s)
        assertThrows(GeneralSecurityException::class.java) { decrypt(key, swapped) }
    }

    /** The same three values are in the backend's CrossVector: both implementations must agree byte for byte. */
    @Test
    fun `matches the backend implementation byte for byte`() {
        val key = KEY_HEX.hexToBytes()
        val fixedRandom = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                for (i in bytes.indices) bytes[i] = (0xA0 + i).toByte()
            }
        }
        val out = ByteArrayOutputStream()
        EvidenceCipher.encrypt(key, ByteArrayInputStream(PLAINTEXT.toByteArray()), out, segmentBytes = 16, random = fixedRandom)
        assertEquals(CIPHERTEXT_HEX, out.toByteArray().toHex())
        assertEquals(PLAINTEXT, String(decrypt(key, CIPHERTEXT_HEX.hexToBytes())))
    }

    // ---- escrow -----------------------------------------------------------------------------------

    @Test
    fun `escrow wraps the item key so that only the offline private key opens it`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        val der = pair.public.encoded
        val keyId = sha256(der)
        val escrow = EscrowKey.parse(Base64.getEncoder().encodeToString(der), keyId, pinnedKeyId = null)
        val itemKey = EvidenceCipher.newKey()

        val wrapped = escrow.wrap(itemKey)

        assertEquals(384, wrapped.size)
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding").apply { init(Cipher.DECRYPT_MODE, pair.private, EscrowKey.OAEP) }
        assertArrayEquals(itemKey, cipher.doFinal(wrapped))
    }

    @Test
    fun `a server key whose id does not match, or that is not the pinned one, is refused`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        val base64 = Base64.getEncoder().encodeToString(pair.public.encoded)
        val keyId = sha256(pair.public.encoded)

        assertThrows(GeneralSecurityException::class.java) { EscrowKey.parse(base64, "0".repeat(64), null) }
        assertThrows(GeneralSecurityException::class.java) { EscrowKey.parse(base64, keyId, pinnedKeyId = "f".repeat(64)) }
        assertEquals(keyId, EscrowKey.parse(base64, keyId, pinnedKeyId = keyId.uppercase()).keyId)
    }

    @Test
    fun `a weak RSA key is refused`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertThrows(GeneralSecurityException::class.java) {
            EscrowKey.parse(Base64.getEncoder().encodeToString(pair.public.encoded), sha256(pair.public.encoded), null)
        }
    }

    private fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray =
        ByteArrayOutputStream().also { EvidenceCipher.encrypt(key, ByteArrayInputStream(plaintext), it, segmentBytes = 64) }.toByteArray()

    private fun decrypt(key: ByteArray, ciphertext: ByteArray): ByteArray =
        ByteArrayOutputStream().also { EvidenceCipher.decrypt(key, ByteArrayInputStream(ciphertext), it) }.toByteArray()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private companion object {
        const val KEY_HEX = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        const val PLAINTEXT = "SafeSphere SSEV1 cross-implementation vector!"
        const val CIPHERTEXT_HEX = "535345560100000010a0a1a2a3a4a5a659b07accc5242d581e46e4d3ca45005ad488111455b25fcb" +
            "d884fdae9639e2df718165a8fe4fdbe68dfdd211a047c044e0d60fada2f590a8d82d0f88265e075980d9e88198ce6921bcbbd3b1" +
            "9906a3db80d48888bdcca71097f5549e1d"
    }
}
