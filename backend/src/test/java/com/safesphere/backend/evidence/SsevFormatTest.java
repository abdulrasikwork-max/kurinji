package com.safesphere.backend.evidence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.safesphere.backend.evidence.tools.SsevFormat;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * The SSEV1 container used by the phone and the offline recovery tool: round trips at the segment boundaries, and
 * every kind of tampering (bit flip, truncation, re-ordering, wrong key, edited header) is detected.
 */
class SsevFormatTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SEGMENT = 64;

    @Test
    void roundTrips_atEverySizeAroundTheSegmentBoundaries() throws Exception {
        byte[] key = random(32);
        for (int size : new int[] {0, 1, SEGMENT - 1, SEGMENT, SEGMENT + 1, 2 * SEGMENT, 5 * SEGMENT + 7}) {
            byte[] plaintext = random(size);
            byte[] ciphertext = encrypt(key, plaintext);
            int segments = Math.max(1, (size + SEGMENT - 1) / SEGMENT);
            assertEquals(SsevFormat.HEADER_BYTES + size + segments * SsevFormat.TAG_BYTES, ciphertext.length, "size " + size);
            assertArrayEquals(plaintext, SsevFormat.decrypt(key, ciphertext), "size " + size);
        }
    }

    @Test
    void aFlippedBit_aWrongKey_orAnEditedHeader_isRejected() throws Exception {
        byte[] key = random(32);
        byte[] ciphertext = encrypt(key, random(200));

        byte[] flipped = ciphertext.clone();
        flipped[flipped.length - 20] ^= 0x01;
        assertThrows(GeneralSecurityException.class, () -> SsevFormat.decrypt(key, flipped));

        assertThrows(GeneralSecurityException.class, () -> SsevFormat.decrypt(random(32), ciphertext));

        byte[] header = ciphertext.clone();
        header[12] ^= 0x01; // nonce prefix, part of the AAD
        assertThrows(GeneralSecurityException.class, () -> SsevFormat.decrypt(key, header));
    }

    @Test
    void truncationAtASegmentBoundary_isRejected() throws Exception {
        byte[] key = random(32);
        byte[] ciphertext = encrypt(key, random(3 * SEGMENT));
        int sealedSegment = SEGMENT + SsevFormat.TAG_BYTES;
        byte[] cut = Arrays.copyOf(ciphertext, SsevFormat.HEADER_BYTES + 2 * sealedSegment);
        // The second segment was written as "not last": decrypting it as the last one fails.
        assertThrows(GeneralSecurityException.class, () -> SsevFormat.decrypt(key, cut));
    }

    @Test
    void reorderedSegments_areRejected() throws Exception {
        byte[] key = random(32);
        byte[] ciphertext = encrypt(key, random(3 * SEGMENT));
        int s = SEGMENT + SsevFormat.TAG_BYTES;
        int h = SsevFormat.HEADER_BYTES;
        byte[] swapped = ciphertext.clone();
        System.arraycopy(ciphertext, h, swapped, h + s, s);
        System.arraycopy(ciphertext, h + s, swapped, h, s);
        assertThrows(GeneralSecurityException.class, () -> SsevFormat.decrypt(key, swapped));
    }

    @Test
    void notAnEvidenceFile_isRejected() {
        assertThrows(GeneralSecurityException.class, () -> SsevFormat.decrypt(random(32), "hello world, not SSEV".getBytes()));
    }

    /**
     * Cross-implementation vector: produced by the Android app's EvidenceCipher (EvidenceCipherTest prints the same
     * values). If either implementation changes the format, one of the two tests fails.
     */
    @Test
    void decryptsTheVectorWrittenByTheAndroidImplementation() throws Exception {
        byte[] key = HexFormat.of().parseHex(CrossVector.KEY_HEX);
        byte[] ciphertext = HexFormat.of().parseHex(CrossVector.CIPHERTEXT_HEX);
        assertArrayEquals(CrossVector.PLAINTEXT.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                SsevFormat.decrypt(key, ciphertext));
    }

    private static byte[] encrypt(byte[] key, byte[] plaintext) throws IOException, GeneralSecurityException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SsevFormat.encrypt(key, new ByteArrayInputStream(plaintext), out, SEGMENT, RANDOM);
        return out.toByteArray();
    }

    private static byte[] random(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
