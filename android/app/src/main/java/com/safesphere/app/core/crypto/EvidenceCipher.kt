package com.safesphere.app.core.crypto

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The SSEV1 evidence container (Phase 5). Pure Kotlin/JCA, so it is unit-tested on the JVM, and byte-for-byte the same
 * format as the backend's offline recovery tool (backend: evidence/tools/SsevFormat.java; a shared test vector keeps the
 * two in step).
 *
 *   header, 16 bytes (also the AAD of every segment):
 *     "SSEV" | version 0x01 | plaintext segment size (Int, big-endian) | 7-byte random nonce prefix
 *   segments: AES-256-GCM with a 128-bit tag; IV = nonce prefix | segment index (Int, big-endian) | last flag
 *
 * Why segments instead of one GCM operation: a video can be hundreds of MB; segments let the phone encrypt and decrypt
 * as a stream with a small buffer. The index in the IV prevents re-ordering, the last flag prevents truncation, and the
 * header in the AAD prevents header edits ("STREAM" construction). A fresh random 256-bit key is used per item, so
 * nonces can never repeat across items, and deleting that one key destroys that one item (crypto-shredding).
 */
object EvidenceCipher {

    const val FORMAT = "SSEV1"
    const val HEADER_BYTES = 16
    const val TAG_BYTES = 16
    const val KEY_BYTES = 32
    const val DEFAULT_SEGMENT_BYTES = 64 * 1024
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'S'.code.toByte(), 'E'.code.toByte(), 'V'.code.toByte())
    private const val VERSION: Byte = 1
    private const val MAX_SEGMENT_BYTES = 16 * 1024 * 1024

    /** What sealing produced. Hashes are lower-case hex SHA-256. */
    data class Sealed(
        val plaintextBytes: Long,
        val ciphertextBytes: Long,
        val plaintextSha256: String,
        val ciphertextSha256: String
    )

    fun newKey(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    /** Encrypts [plaintext] into [out] in one pass, hashing both sides on the way. Does not close the streams. */
    fun encrypt(
        key: ByteArray,
        plaintext: InputStream,
        out: OutputStream,
        segmentBytes: Int = DEFAULT_SEGMENT_BYTES,
        random: SecureRandom = SecureRandom()
    ): Sealed {
        require(key.size == KEY_BYTES) { "Evidence keys are 256-bit" }
        require(segmentBytes in 1..MAX_SEGMENT_BYTES)
        val plainDigest = MessageDigest.getInstance("SHA-256")
        val cipherDigest = MessageDigest.getInstance("SHA-256")
        val prefix = ByteArray(7).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).put(VERSION).putInt(segmentBytes).put(prefix).array()
        out.write(header)
        cipherDigest.update(header)
        var plainTotal = 0L
        var cipherTotal = HEADER_BYTES.toLong()

        var current = plaintext.readUpTo(segmentBytes)
        var index = 0
        while (true) {
            val next = if (current.size == segmentBytes) plaintext.readUpTo(segmentBytes) else ByteArray(0)
            val last = next.isEmpty()
            plainDigest.update(current)
            plainTotal += current.size
            val sealed = segmentCipher(Cipher.ENCRYPT_MODE, key, header, prefix, index, last).doFinal(current)
            out.write(sealed)
            cipherDigest.update(sealed)
            cipherTotal += sealed.size
            if (last) break
            current = next
            index++
        }
        out.flush()
        return Sealed(plainTotal, cipherTotal, plainDigest.digest().toHex(), cipherDigest.digest().toHex())
    }

    /**
     * Decrypts [ciphertext] into [out]. Each segment is authenticated before its plaintext is written; a tampered,
     * re-ordered or truncated file throws [GeneralSecurityException] / [EOFException] (the caller discards the output).
     */
    fun decrypt(key: ByteArray, ciphertext: InputStream, out: OutputStream) {
        val header = ciphertext.readUpTo(HEADER_BYTES)
        if (header.size != HEADER_BYTES || !header.copyOfRange(0, 4).contentEquals(MAGIC) || header[4] != VERSION) {
            throw GeneralSecurityException("Not an SSEV1 evidence file")
        }
        val segmentBytes = ByteBuffer.wrap(header).getInt(5)
        if (segmentBytes !in 1..MAX_SEGMENT_BYTES) throw GeneralSecurityException("Bad segment size")
        val prefix = header.copyOfRange(9, 16)
        val sealedSegment = segmentBytes + TAG_BYTES
        var current = ciphertext.readUpTo(sealedSegment)
        if (current.size < TAG_BYTES) throw EOFException("Evidence file is truncated")
        var index = 0
        while (true) {
            val next = if (current.size == sealedSegment) ciphertext.readUpTo(sealedSegment) else ByteArray(0)
            val last = next.isEmpty()
            out.write(segmentCipher(Cipher.DECRYPT_MODE, key, header, prefix, index, last).doFinal(current))
            if (last) break
            if (next.size < TAG_BYTES) throw EOFException("Evidence file is truncated")
            current = next
            index++
        }
        out.flush()
    }

    /** SHA-256 of a whole stream (local integrity re-check). */
    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    private fun segmentCipher(mode: Int, key: ByteArray, header: ByteArray, prefix: ByteArray, index: Int, last: Boolean): Cipher {
        val iv = ByteBuffer.allocate(12).put(prefix).putInt(index).put(if (last) 1 else 0).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, iv))
            updateAAD(header)
        }
    }

    /** Reads until [size] bytes or the end of the stream (InputStream.readNBytes needs API 33). */
    private fun InputStream.readUpTo(size: Int): ByteArray {
        val buffer = ByteArray(size)
        var filled = 0
        while (filled < size) {
            val read = read(buffer, filled, size - filled)
            if (read < 0) break
            filled += read
        }
        return if (filled == size) buffer else buffer.copyOf(filled)
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
