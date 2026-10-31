package com.safesphere.backend.support;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 17C: structurally valid MP4 files built byte by byte for the Mp4Sanitizer and API tests (the server never
 * decodes video, so a real codec stream is not needed). The defaults carry every kind of metadata a phone or an editor
 * writes: a GPS position in moov/udta/©xyz, an Android "meta" box, an XMP uuid box at the top, a free box with old
 * text in it, creation times, handler names and a compressor name. Sample bytes are recognisable ("PICTURE-…",
 * "SOUND-…") so a test can check that every chunk offset still points at the same bytes after stripping.
 */
public final class TestVideos {

    public static final String GPS = "+13.0827+080.2707/";
    public static final String XMP_GPS = "exif:GPSLatitude=\"13,4.96N\"";
    public static final String ANDROID_VERSION = "com.android.version 14";
    public static final String FREE_TEXT = "old-editor-note GPS 13.08";
    public static final String HANDLER_NAME = "SamsungVideoHandle";
    public static final String COMPRESSOR = "Galaxy A23 camera";
    public static final byte[] PICTURE = "PICTURE-SAMPLE-0001".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] SOUND = "SOUND-SAMPLE-0001".getBytes(StandardCharsets.US_ASCII);
    /** A creation time that is not zero (seconds since 1904), so a test sees it zeroed. */
    public static final int CREATED = 0x7A1B2C3D;

    private TestVideos() {
    }

    public static Builder video() {
        return new Builder();
    }

    public static final class Builder {
        private boolean moovFirst = true;
        private long durationMs = 5_000;
        private String brand = "isom";
        private String videoCodec = "avc1";
        private boolean audio = true;
        private String extraTrackHandler = null;
        private boolean secondVideo = false;
        private boolean metadata = true;
        private boolean externalReference = false;
        private boolean offsetOutside = false;
        private boolean largeMdat = false;
        private boolean version1 = false;
        private boolean rotate90 = false;
        private boolean mvex = false;
        private String extraTopLevel = null;
        private int width = 640;
        private int height = 360;

        public Builder moovLast() { moovFirst = false; return this; }
        public Builder durationMs(long ms) { durationMs = ms; return this; }
        public Builder brand(String b) { brand = b; return this; }
        public Builder videoCodec(String c) { videoCodec = c; return this; }
        public Builder noAudio() { audio = false; return this; }
        public Builder extraTrack(String handler) { extraTrackHandler = handler; return this; }
        public Builder secondVideo() { secondVideo = true; return this; }
        public Builder noMetadata() { metadata = false; return this; }
        public Builder externalReference() { externalReference = true; return this; }
        public Builder offsetOutsideMediaData() { offsetOutside = true; return this; }
        public Builder largeMdat() { largeMdat = true; return this; }
        public Builder version1() { version1 = true; return this; }
        public Builder rotate90() { rotate90 = true; return this; }
        public Builder fragmentedMoov() { mvex = true; return this; }
        public Builder topLevel(String type) { extraTopLevel = type; return this; }
        public Builder size(int w, int h) { width = w; height = h; return this; }

        public byte[] build() {
            byte[] ftyp = box("ftyp", ascii(brand), u32(0), ascii(brand), ascii("mp42"));
            byte[] uuid = metadata ? box("uuid", new byte[16], ascii("<x:xmpmeta " + XMP_GPS + "/>")) : new byte[0];
            byte[] free = box("free", ascii(FREE_TEXT));
            byte[] extra = extraTopLevel == null ? new byte[0] : box(extraTopLevel, ascii("data"));
            byte[] mdatPayload = concat(PICTURE, new byte[13], SOUND, new byte[7]);
            byte[] mdat = largeMdat ? largeBox("mdat", mdatPayload) : box("mdat", mdatPayload);
            int mdatHeader = largeMdat ? 16 : 8;
            // The moov's length does not depend on the offsets inside it, so build it once to measure it.
            int moovLength = moov(0, 0).length;
            long payloadStart = moovFirst
                    ? ftyp.length + uuid.length + free.length + extra.length + moovLength + mdatHeader
                    : ftyp.length + uuid.length + free.length + extra.length + mdatHeader;
            long pictureAt = offsetOutside ? 2 : payloadStart;
            long soundAt = payloadStart + PICTURE.length + 13;
            byte[] moov = moov(pictureAt, soundAt);
            return moovFirst ? concat(ftyp, uuid, free, extra, moov, mdat) : concat(ftyp, uuid, free, extra, mdat, moov);
        }

        private byte[] moov(long pictureAt, long soundAt) {
            List<byte[]> parts = new ArrayList<>();
            parts.add(mvhd());
            parts.add(trak(1, "vide", videoCodec, pictureAt, PICTURE.length));
            if (secondVideo) parts.add(trak(3, "vide", videoCodec, pictureAt, PICTURE.length));
            if (audio) parts.add(trak(2, "soun", "mp4a", soundAt, SOUND.length));
            if (extraTrackHandler != null) parts.add(trak(4, extraTrackHandler, "mett", soundAt, SOUND.length));
            if (mvex) parts.add(box("mvex", box("trex", new byte[24])));
            if (metadata) {
                parts.add(box("udta", box("©xyz", u16(GPS.length()), u16(0x15C7), ascii(GPS))));
                parts.add(box("meta", u32(0), box("keys", ascii(ANDROID_VERSION))));
            }
            return box("moov", parts.toArray(new byte[0][]));
        }

        private byte[] mvhd() {
            long d = durationMs; // timescale 1000
            byte[] times = version1 ? concat(u64(CREATED), u64(CREATED), u32(1000), u64(d))
                    : concat(u32(CREATED), u32(CREATED), u32(1000), u32(d));
            return box("mvhd", u8(version1 ? 1 : 0), new byte[3], times, u32(0x00010000), u16(0x0100), new byte[10],
                    matrix(false), new byte[24], u32(5));
        }

        private byte[] trak(int id, String handler, String codec, long chunkAt, int sampleSize) {
            boolean picture = "vide".equals(handler);
            long d = durationMs * 90; // media timescale 90 000
            byte[] head = version1 ? concat(u64(CREATED), u64(CREATED), u32(id), u32(0), u64(durationMs))
                    : concat(u32(CREATED), u32(CREATED), u32(id), u32(0), u32(durationMs));
            byte[] tkhd = box("tkhd", u8(version1 ? 1 : 0), new byte[]{0, 0, 3}, head, new byte[8], u16(0), u16(0),
                    u16(picture ? 0 : 0x0100), u16(0), matrix(picture && rotate90),
                    u32(picture ? (long) width << 16 : 0), u32(picture ? (long) height << 16 : 0));
            byte[] mdhdTimes = version1 ? concat(u64(CREATED), u64(CREATED), u32(90_000), u64(d))
                    : concat(u32(CREATED), u32(CREATED), u32(90_000), u32(d));
            byte[] mdhd = box("mdhd", u8(version1 ? 1 : 0), new byte[3], mdhdTimes, u16(0x55C4), u16(0));
            byte[] hdlr = box("hdlr", u32(0), u32(0), ascii(handler), new byte[12], ascii(HANDLER_NAME), new byte[1]);
            byte[] mediaHeader = picture ? box("vmhd", u32(1), new byte[8]) : box("smhd", u32(0), new byte[4]);
            byte[] url = box("url ", u32(externalReference ? 0 : 1),
                    externalReference ? ascii("http://example.invalid/video.mp4\0") : new byte[0]);
            byte[] dinf = box("dinf", box("dref", u32(0), u32(1), url));
            byte[] entry = picture ? visualEntry(codec) : audioEntry(codec);
            byte[] stbl = box("stbl",
                    box("stsd", u32(0), u32(1), entry),
                    box("stts", u32(0), u32(1), u32(1), u32(d)),
                    box("stsc", u32(0), u32(1), u32(1), u32(1), u32(1)),
                    box("stsz", u32(0), u32(sampleSize), u32(1)),
                    box("stco", u32(0), u32(1), u32(chunkAt)));
            byte[] minf = box("minf", mediaHeader, dinf, stbl);
            List<byte[]> trak = new ArrayList<>(List.of(tkhd, box("mdia", mdhd, hdlr, minf)));
            if (metadata) trak.add(box("udta", box("©xyz", ascii(GPS))));
            return box("trak", trak.toArray(new byte[0][]));
        }

        private byte[] visualEntry(String codec) {
            byte[] name = new byte[32];
            name[0] = (byte) COMPRESSOR.length();
            System.arraycopy(ascii(COMPRESSOR), 0, name, 1, COMPRESSOR.length());
            return box(codec, new byte[6], u16(1), u16(0), u16(0), new byte[12], u16(width), u16(height),
                    u32(0x00480000), u32(0x00480000), u32(0), u16(1), name, u16(0x0018), u16(0xFFFF),
                    box("avcC", new byte[]{1, 0x64, 0, 0x1F, (byte) 0xFF}),
                    box("gpsx", ascii(GPS))); // an unknown box inside the entry: must be zeroed too
        }

        private byte[] audioEntry(String codec) {
            return box(codec, new byte[6], u16(1), new byte[8], u16(2), u16(16), u16(0), u16(0), u32(44_100L << 16),
                    box("esds", u32(0), new byte[]{3, 5, 0, 1, 0}));
        }
    }

    // ---- bytes ----

    public static byte[] box(String type, byte[]... parts) {
        byte[] payload = concat(parts);
        return concat(u32(8 + payload.length), fourCc(type), payload);
    }

    public static byte[] largeBox(String type, byte[]... parts) {
        byte[] payload = concat(parts);
        return concat(u32(1), fourCc(type), u64(16 + payload.length), payload);
    }

    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    /** True if [needle] occurs anywhere in [haystack]. */
    public static boolean contains(byte[] haystack, String needle) {
        return indexOf(haystack, needle.getBytes(StandardCharsets.ISO_8859_1)) >= 0;
    }

    public static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static byte[] matrix(boolean rotate90) {
        int one = 0x00010000;
        int[] m = rotate90 ? new int[]{0, one, 0, -one, 0, 0, 0, 0, 0x40000000}
                : new int[]{one, 0, 0, 0, one, 0, 0, 0, 0x40000000};
        ByteBuffer b = ByteBuffer.allocate(36);
        for (int v : m) b.putInt(v);
        return b.array();
    }

    private static byte[] fourCc(String type) {
        return type.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] u8(int v) {
        return new byte[]{(byte) v};
    }

    private static byte[] u16(int v) {
        return new byte[]{(byte) (v >>> 8), (byte) v};
    }

    private static byte[] u32(long v) {
        return ByteBuffer.allocate(4).putInt((int) v).array();
    }

    private static byte[] u64(long v) {
        return ByteBuffer.allocate(8).putLong(v).array();
    }
}
