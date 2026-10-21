package com.safesphere.backend.evidence.tools;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The SSEV1 evidence container, the SAME format the Android app writes (core/crypto/EvidenceCipher.kt). Used by the
 * offline recovery tool and by tests; the running server never decrypts anything.
 *
 *   header (16 bytes, also the AAD of every segment):
 *     "SSEV" | version 0x01 | plaintext segment size (int, big-endian) | 7-byte random nonce prefix
 *   segments: AES-256-GCM, 128-bit tag, IV = nonce prefix | segment index (int, big-endian) | last flag (0x00 / 0x01)
 *     every segment but the last holds exactly "segment size" plaintext bytes; there is always a last segment.
 *
 * The index in the IV stops re-ordering, the last flag stops truncation (a stream cut at a segment boundary has no
 * "last" segment and fails), and the header as AAD stops header edits. This is the "STREAM" online AEAD construction.
 */
public final class SsevFormat {

    public static final int HEADER_BYTES = 16;
    public static final int TAG_BYTES = 16;
    public static final int KEY_BYTES = 32;
    static final byte[] MAGIC = "SSEV".getBytes(StandardCharsets.US_ASCII);
    static final byte VERSION = 1;

    private SsevFormat() {
    }

    public static void encrypt(byte[] key, InputStream plaintext, OutputStream out, int segmentSize, SecureRandom random)
            throws IOException, GeneralSecurityException {
        byte[] prefix = new byte[7];
        random.nextBytes(prefix);
        byte[] header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).put(VERSION).putInt(segmentSize).put(prefix).array();
        out.write(header);
        byte[] current = readUpTo(plaintext, segmentSize);
        int index = 0;
        while (true) {
            byte[] next = current.length == segmentSize ? readUpTo(plaintext, segmentSize) : new byte[0];
            boolean last = next.length == 0;
            out.write(cipher(Cipher.ENCRYPT_MODE, key, header, prefix, index, last).doFinal(current));
            if (last) return;
            current = next;
            index++;
        }
    }

    public static void decrypt(byte[] key, InputStream ciphertext, OutputStream out)
            throws IOException, GeneralSecurityException {
        byte[] header = ciphertext.readNBytes(HEADER_BYTES);
        if (header.length != HEADER_BYTES || !Arrays.equals(Arrays.copyOf(header, 4), MAGIC) || header[4] != VERSION) {
            throw new GeneralSecurityException("Not an SSEV1 evidence file");
        }
        ByteBuffer view = ByteBuffer.wrap(header);
        int segmentSize = view.getInt(5);
        if (segmentSize < 1 || segmentSize > 16 * 1024 * 1024) throw new GeneralSecurityException("Bad segment size");
        byte[] prefix = Arrays.copyOfRange(header, 9, 16);
        int sealedSize = segmentSize + TAG_BYTES;
        byte[] current = ciphertext.readNBytes(sealedSize);
        if (current.length < TAG_BYTES) throw new EOFException("Evidence file is truncated");
        int index = 0;
        while (true) {
            byte[] next = current.length == sealedSize ? ciphertext.readNBytes(sealedSize) : new byte[0];
            boolean last = next.length == 0;
            out.write(cipher(Cipher.DECRYPT_MODE, key, header, prefix, index, last).doFinal(current));
            if (last) return;
            if (next.length < TAG_BYTES) throw new EOFException("Evidence file is truncated");
            current = next;
            index++;
        }
    }

    /** Convenience for tests. */
    public static byte[] decrypt(byte[] key, byte[] ciphertext) throws IOException, GeneralSecurityException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        decrypt(key, new java.io.ByteArrayInputStream(ciphertext), out);
        return out.toByteArray();
    }

    private static Cipher cipher(int mode, byte[] key, byte[] header, byte[] prefix, int index, boolean last)
            throws GeneralSecurityException {
        byte[] iv = ByteBuffer.allocate(12).put(prefix).putInt(index).put((byte) (last ? 1 : 0)).array();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BYTES * 8, iv));
        cipher.updateAAD(header);
        return cipher;
    }

    private static byte[] readUpTo(InputStream in, int size) throws IOException {
        return in.readNBytes(size);
    }

    public static String sha256Hex(byte[] data) throws GeneralSecurityException {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
