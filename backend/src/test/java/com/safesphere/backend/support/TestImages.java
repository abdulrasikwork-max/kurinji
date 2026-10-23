package com.safesphere.backend.support;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** Test helper (Phase 13B): small images, an EXIF GPS block, and hostile files, built in memory. */
public final class TestImages {

    /** Text placed in the fake EXIF block; it must never appear in a stored photo. */
    public static final String EXIF_MARKER = "GPSLatitude 13.0500 N 80.2824 E SECRET-HOME";

    private TestImages() {
    }

    public static byte[] jpeg(int width, int height) {
        return encode(noise(width, height, BufferedImage.TYPE_INT_RGB), "jpeg");
    }

    public static byte[] png(int width, int height) {
        return encode(noise(width, height, BufferedImage.TYPE_INT_ARGB), "png");
    }

    /** A real JPEG with an APP1 "Exif" segment (holding [EXIF_MARKER]) inserted right after the SOI marker. */
    public static byte[] jpegWithExifGps(int width, int height) {
        byte[] jpeg = jpeg(width, height);
        byte[] payload = ("Exif\0\0MM\0*\0\0\0\b" + EXIF_MARKER).getBytes(StandardCharsets.ISO_8859_1);
        int length = payload.length + 2;
        ByteBuffer out = ByteBuffer.allocate(jpeg.length + 4 + payload.length);
        out.put(jpeg, 0, 2);                                   // SOI
        out.put((byte) 0xFF).put((byte) 0xE1).put((byte) (length >> 8)).put((byte) length);
        out.put(payload);
        out.put(jpeg, 2, jpeg.length - 2);
        return out.array();
    }

    /** A valid 1 x 1 PNG whose header claims [width] x [height] (a decompression bomb's header, CRC fixed). */
    public static byte[] pngClaiming(int width, int height) {
        byte[] png = png(1, 1);
        // Signature (8) + IHDR length (4) + "IHDR" (4): width and height follow at 16 and 20; CRC after 13 data bytes.
        ByteBuffer buffer = ByteBuffer.wrap(png);
        buffer.putInt(16, width);
        buffer.putInt(20, height);
        CRC32 crc = new CRC32();
        crc.update(png, 12, 4 + 13);
        buffer.putInt(29, (int) crc.getValue());
        return png;
    }

    public static boolean containsExifSegment(byte[] jpeg) {
        for (int i = 0; i + 1 < jpeg.length; i++) {
            if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == 0xE1) return true; // APP1 (Exif / XMP)
        }
        return new String(jpeg, StandardCharsets.ISO_8859_1).contains("Exif");
    }

    private static BufferedImage noise(int width, int height, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        Random random = new Random(width * 31L + height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) image.setRGB(x, y, random.nextInt() | 0xFF000000);
        }
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, out)) throw new IllegalStateException("no writer for " + format);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }
}
