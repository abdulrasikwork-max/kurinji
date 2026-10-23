package com.safesphere.backend.social.media;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.event.IIOReadWarningListener;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/**
 * Phase 13B (design §4.4, Q4): every uploaded photo is decoded and written again as a new JPEG, so nothing the phone or
 * the camera put in the file survives: no EXIF (GPS position, time, device), no XMP, no comments, no thumbnails, no
 * extra data after the image. The phone already strips metadata; this is the second, independent strip.
 *
 * Defences against hostile files:
 *   - only JPEG and PNG, recognised by their first bytes (never by the name or the declared type);
 *   - the size is read from the header BEFORE decoding, and anything over 4096 px a side is refused, so a small file
 *     that would expand to gigabytes of pixels (a "decompression bomb") is never decoded;
 *   - metadata is not even parsed (ignoreMetadata) and any decoder warning (a truncated or damaged file) refuses it;
 *   - the output is always a plain RGB JPEG (transparency becomes white).
 * Pure apart from the JDK image codecs; no state.
 */
public final class ImageSanitizer {

    public static final int MAX_SIDE = 4096;
    private static final float[] QUALITIES = {0.85f, 0.7f, 0.5f};

    public record Result(byte[] jpeg, int width, int height) {
    }

    /** A refused upload; [getMessage] is safe to show (it never contains decoder text). */
    public static final class Refused extends RuntimeException {
        public Refused(String message) {
            super(message, null, false, false);
        }
    }

    private ImageSanitizer() {
    }

    public static Result sanitize(byte[] input, int maxBytes) {
        if (input == null || input.length == 0) throw new Refused("The photo is empty");
        if (input.length > maxBytes) throw new Refused("A photo can be at most 5 MB");
        String format = format(input);
        if (format == null) throw new Refused("Only JPEG and PNG photos can be added");
        BufferedImage decoded = decode(input, format);
        BufferedImage rgb = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.drawImage(decoded, 0, 0, null);
        } finally {
            g.dispose();
        }
        for (float quality : QUALITIES) {
            byte[] out = encode(rgb, quality);
            if (out.length <= maxBytes) return new Result(out, rgb.getWidth(), rgb.getHeight());
        }
        throw new Refused("A photo can be at most 5 MB");
    }

    /** "jpeg" or "png" from the magic bytes; null for anything else. */
    static String format(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "jpeg";
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        if (b.length < png.length) return null;
        for (int i = 0; i < png.length; i++) if (b[i] != png[i]) return null;
        return "png";
    }

    private static BufferedImage decode(byte[] input, String format) {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
        if (!readers.hasNext()) throw new Refused("Only JPEG and PNG photos can be added");
        ImageReader reader = readers.next();
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            reader.setInput(stream, true, true); // one image only; metadata ignored, never parsed
            int width = reader.getWidth(0);      // header only: nothing is decoded yet
            int height = reader.getHeight(0);
            if (width < 1 || height < 1) throw new Refused("This file is not a photo that can be read");
            if (width > MAX_SIDE || height > MAX_SIDE) throw new Refused("A photo can be at most 4096 pixels a side");
            AtomicBoolean warned = new AtomicBoolean(false);
            reader.addIIOReadWarningListener((IIOReadWarningListener) (source, warning) -> warned.set(true));
            BufferedImage image = reader.read(0);
            if (warned.get() || image == null) throw new Refused("This photo is damaged or incomplete");
            return image;
        } catch (Refused e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new Refused("This file is not a photo that can be read");
        } finally {
            reader.dispose();
        }
    }

    private static byte[] encode(BufferedImage image, float quality) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), param); // no metadata, no thumbnails
        } catch (IOException e) {
            throw new IllegalStateException("JPEG encoding failed", e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
