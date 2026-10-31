package com.safesphere.backend.social.media;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Phase 17C (decision D-6): the server's half of "no metadata in a video". The phone already re-packs every video with
 * MediaMuxer (picture and sound only, no location); this is the second, independent check, on the uploaded file itself.
 *
 * What it does, without decoding a single frame (the server never decodes video; it checks and strips the container):
 *   - recognises an MP4 by its first box ("ftyp" with an ISO / MP4 / 3GPP brand), never by the declared type;
 *   - walks every box with its size checked against its parent (64-bit sizes included), at most 64 top-level boxes, a
 *     moov of at most 8 MiB, at most 20 000 boxes inside it, at most 16 levels deep;
 *   - refuses fragmented files (moof, mvex, sidx ...), anything but exactly one picture track and at most one sound
 *     track (a GPS, timed-metadata or subtitle track is refused, not kept), external data references, encrypted or
 *     unknown codecs, and chunk offsets that do not point into the media data;
 *   - reads the length (mvhd / mdhd) and refuses anything over 60.5 s; reads the picture size (tkhd, rotation applied);
 *   - NEUTRALISES metadata instead of cutting it out: every udta, meta, uuid (XMP) box and every unknown box becomes a
 *     "free" box whose bytes are all zero, and the payload of every existing free / skip box is zeroed too. Cutting
 *     bytes out of a moov in front of the mdat would shift every stco / co64 offset and break playback; overwriting
 *     keeps every offset valid and still leaves no metadata byte behind;
 *   - zeroes the creation and modification times in mvhd, tkhd and mdhd (exact times; social content shows bands only)
 *     and the free-text name of each hdlr box.
 * What it does NOT do: look inside the coded picture / sound data (for example camera notes in an H.264 SEI message).
 * Phones do not write a location there; the phone's MediaMuxer copy and this container strip are the documented limit.
 * Pure apart from file I/O; no state.
 */
public final class Mp4Sanitizer {

    /** 60 seconds plus half a second of container rounding (decision D-6). */
    public static final long MAX_DURATION_MS = 60_500;
    public static final int MAX_SIDE = 4096;
    static final int MAX_TOP_LEVEL_BOXES = 64;
    static final int MAX_MOOV_BYTES = 8 * 1024 * 1024;
    static final int MAX_MOOV_BOXES = 20_000;
    static final int MAX_DEPTH = 16;

    public record Result(int width, int height, long durationMs) {
    }

    /** A refused upload; [getMessage] is safe to show (it never contains parser detail or file content). */
    public static final class Refused extends RuntimeException {
        public Refused(String message) {
            super(message, null, false, false);
        }
    }

    static final String NOT_A_VIDEO = "This file is not a video that can be shared (MP4 only)";
    static final String DAMAGED = "This video is damaged or incomplete";
    static final String UNSUPPORTED = "This video's format can't be shared. Try a video recorded with the phone's camera.";
    static final String TOO_LONG = "A video can be at most 60 seconds";
    static final String TOO_LARGE_PICTURE = "A video can be at most 4096 pixels a side";
    static final String TRACKS = "A video can have one picture track and at most one sound track";

    private static final Set<String> BRANDS = Set.of("isom", "iso2", "iso3", "iso4", "iso5", "iso6", "mp41", "mp42",
            "avc1", "dash", "3gp4", "3gp5", "3gp6", "3gp7", "3gp8", "3gp9", "3g2a", "MSNV");
    private static final Set<String> FRAGMENTED = Set.of("moof", "mfra", "sidx", "ssix", "styp", "emsg", "prft");
    private static final Set<String> STRIPPED = Set.of("udta", "meta", "uuid");
    private static final Set<String> FREE = Set.of("free", "skip", "wide");
    private static final Set<String> VIDEO_CODECS = Set.of("avc1", "avc3", "hvc1", "hev1", "mp4v", "av01", "vp09",
            "s263");
    private static final Set<String> AUDIO_CODECS = Set.of("mp4a", "samr", "sawb");
    /** Boxes inside a sample entry that describe how to decode; anything else there is zeroed. */
    private static final Set<String> CODEC_CONFIG = Set.of("avcC", "hvcC", "av1C", "vpcC", "esds", "btrt", "colr",
            "pasp", "clap", "fiel", "mdcv", "clli", "d263", "damr", "dOps", "chnl", "chan", "m4ds", "dvcC", "dvvC");
    private static final Set<String> SAMPLE_TABLE = Set.of("stts", "ctts", "cslg", "stss", "stsh", "stsz", "stz2",
            "stsc", "sdtp", "sgpd", "sbgp", "subs", "saiz", "saio", "padb", "stps");

    private Mp4Sanitizer() {
    }

    /**
     * Checks [input] (the uploaded bytes, already size-limited by the caller) and writes the stripped copy to [output]
     * (same length; the caller moves it into place). Throws [Refused] for anything it does not accept.
     */
    public static Result sanitize(Path input, Path output) throws IOException {
        try (FileChannel in = FileChannel.open(input, StandardOpenOption.READ)) {
            long fileSize = in.size();
            if (fileSize < 16) throw new Refused(NOT_A_VIDEO);
            ByteBuffer first = ByteBuffer.allocate(8);
            readFully(in, first, 0);
            if (!"ftyp".equals(fourCc(first.array(), 4))) throw new Refused(NOT_A_VIDEO); // a photo, a text, ...
            List<Box> top = topLevel(in, fileSize);
            Box moov = null;
            List<long[]> mediaData = new ArrayList<>();
            List<Box> neutralise = new ArrayList<>();
            for (int i = 0; i < top.size(); i++) {
                Box box = top.get(i);
                switch (box.type) {
                    case "ftyp" -> {
                        if (i != 0) throw new Refused(NOT_A_VIDEO);
                    }
                    case "moov" -> {
                        if (moov != null) throw new Refused(DAMAGED);
                        moov = box;
                    }
                    case "mdat" -> mediaData.add(new long[]{box.start + box.header, box.start + box.size});
                    default -> {
                        if (FRAGMENTED.contains(box.type)) throw new Refused(UNSUPPORTED);
                        if (STRIPPED.contains(box.type) || FREE.contains(box.type)) neutralise.add(box);
                        else throw new Refused(UNSUPPORTED);
                    }
                }
            }
            if (!"ftyp".equals(top.get(0).type)) throw new Refused(NOT_A_VIDEO);
            checkBrands(read(in, top.get(0)));
            if (moov == null || mediaData.isEmpty()) throw new Refused(DAMAGED);
            if (moov.size > MAX_MOOV_BYTES) throw new Refused(UNSUPPORTED);

            byte[] movie = read(in, moov);
            Movie m = new Movie(movie, mediaData);
            Result result = m.analyse();

            copy(in, fileSize, output);
            try (FileChannel out = FileChannel.open(output, StandardOpenOption.WRITE)) {
                out.write(ByteBuffer.wrap(movie), moov.start);
                for (Box box : neutralise) zeroBox(out, box);
                out.force(true);
            }
            return result;
        }
    }

    // ---- top level ----

    private record Box(String type, long start, int header, long size) {
    }

    private static List<Box> topLevel(FileChannel in, long fileSize) throws IOException {
        List<Box> boxes = new ArrayList<>();
        long pos = 0;
        while (pos < fileSize) {
            if (boxes.size() >= MAX_TOP_LEVEL_BOXES) throw new Refused(UNSUPPORTED);
            if (fileSize - pos < 8) throw new Refused(DAMAGED);
            ByteBuffer head = ByteBuffer.allocate(16);
            head.limit(8);
            readFully(in, head, pos);
            long size = Integer.toUnsignedLong(head.getInt(0));
            String type = fourCc(head.array(), 4);
            int header = 8;
            if (size == 1) {
                if (fileSize - pos < 16) throw new Refused(DAMAGED);
                head.limit(16);
                head.position(8);
                readFully(in, head, pos + 8);
                size = head.getLong(8);
                header = 16;
            } else if (size == 0) {
                size = fileSize - pos; // "to the end of the file": only the last box can say so
            }
            if (size < header || size > fileSize - pos) throw new Refused(DAMAGED);
            boxes.add(new Box(type, pos, header, size));
            pos += size;
        }
        if (boxes.isEmpty()) throw new Refused(NOT_A_VIDEO);
        return boxes;
    }

    private static void checkBrands(byte[] ftyp) {
        int header = 8;
        if (ftyp.length < header + 8 || ftyp.length > 1024) throw new Refused(NOT_A_VIDEO);
        if ("qt  ".equals(fourCc(ftyp, header))) throw new Refused(NOT_A_VIDEO); // QuickTime rules, not MP4
        boolean known = BRANDS.contains(fourCc(ftyp, header));

        for (int at = header + 8; at + 4 <= ftyp.length; at += 4) known |= BRANDS.contains(fourCc(ftyp, at));
        if (!known) throw new Refused(NOT_A_VIDEO);
    }

    // ---- the movie box (in memory, patched in place) ----

    /** The moov box as bytes; [analyse] checks it and writes every change into [b]. */
    private static final class Movie {
        private final byte[] b;
        private final List<long[]> mediaData;
        private int boxes;
        private long movieTimescale;
        private long movieDuration;
        private final List<Track> tracks = new ArrayList<>();

        Movie(byte[] bytes, List<long[]> mediaData) {
            this.b = bytes;
            this.mediaData = mediaData;
        }

        Result analyse() {
            boolean sawHeader = false;
            for (Child c : children(headerOf(0, b.length), b.length, 1)) {
                switch (c.type) {
                    case "mvhd" -> {
                        if (sawHeader) throw new Refused(DAMAGED);
                        sawHeader = true;
                        movieHeader(c);
                    }
                    case "trak" -> tracks.add(track(c));
                    case "iods" -> { }
                    case "mvex" -> throw new Refused(UNSUPPORTED);
                    default -> neutralise(c);
                }
            }
            if (!sawHeader || movieTimescale == 0) throw new Refused(DAMAGED);
            Track picture = null;
            int sound = 0;
            for (Track t : tracks) {
                if ("vide".equals(t.handler)) {
                    if (picture != null) throw new Refused(TRACKS);
                    picture = t;
                } else if ("soun".equals(t.handler)) {
                    if (++sound > 1) throw new Refused(TRACKS);
                } else {
                    throw new Refused(TRACKS);
                }
            }
            if (picture == null) throw new Refused(TRACKS);
            long durationMs = millis(movieDuration, movieTimescale);
            for (Track t : tracks) durationMs = Math.max(durationMs, t.durationMs);
            if (durationMs < 1) throw new Refused(DAMAGED);
            if (durationMs > MAX_DURATION_MS) throw new Refused(TOO_LONG);
            int width = picture.width > 0 ? picture.width : picture.entryWidth;
            int height = picture.height > 0 ? picture.height : picture.entryHeight;
            if (picture.rotated) {
                int w = width;
                width = height;
                height = w;
            }
            if (width < 1 || height < 1) throw new Refused(DAMAGED);
            if (width > MAX_SIDE || height > MAX_SIDE) throw new Refused(TOO_LARGE_PICTURE);
            return new Result(width, height, durationMs);
        }

        private void movieHeader(Child c) {
            int p = c.payload;
            int version = u8(p);
            if (version == 1) {
                need(c, 4 + 28);
                zero(p + 4, 16);
                movieTimescale = u32(p + 20);
                movieDuration = u64(p + 24);
            } else if (version == 0) {
                need(c, 4 + 16);
                zero(p + 4, 8);
                movieTimescale = u32(p + 12);
                movieDuration = u32(p + 16);
            } else {
                throw new Refused(UNSUPPORTED);
            }
        }

        private Track track(Child trak) {
            Track t = new Track();
            boolean sawHeader = false;
            boolean sawMedia = false;
            for (Child c : children(trak.payload, trak.end, trak.depth + 1)) {
                switch (c.type) {
                    case "tkhd" -> {
                        if (sawHeader) throw new Refused(DAMAGED);
                        sawHeader = true;
                        trackHeader(c, t);
                    }
                    case "mdia" -> {
                        if (sawMedia) throw new Refused(DAMAGED);
                        sawMedia = true;
                        media(c, t);
                    }
                    case "edts", "tref" -> { }
                    default -> neutralise(c);
                }
            }
            if (!sawHeader || !sawMedia || t.handler == null) throw new Refused(DAMAGED);
            return t;
        }

        private void trackHeader(Child c, Track t) {
            int p = c.payload;
            int version = u8(p);
            int matrix;
            if (version == 1) {
                need(c, 4 + 32 + 16 + 36 + 8);
                zero(p + 4, 16);
                matrix = p + 4 + 32 + 16;
            } else if (version == 0) {
                need(c, 4 + 20 + 16 + 36 + 8);
                zero(p + 4, 8);
                matrix = p + 4 + 20 + 16;
            } else {
                throw new Refused(UNSUPPORTED);
            }
            int a = s32(matrix);
            int d = s32(matrix + 16);
            t.rotated = a == 0 && d == 0; // 90 or 270 degrees: the picture is shown on its side
            t.width = (int) ((u32(matrix + 36) + 0x8000) >>> 16);
            t.height = (int) ((u32(matrix + 40) + 0x8000) >>> 16);
        }

        private void media(Child mdia, Track t) {
            boolean sawHeader = false;
            boolean sawHandler = false;
            Child info = null;
            for (Child c : children(mdia.payload, mdia.end, mdia.depth + 1)) {
                switch (c.type) {
                    case "mdhd" -> {
                        if (sawHeader) throw new Refused(DAMAGED);
                        sawHeader = true;
                        mediaHeader(c, t);
                    }
                    case "hdlr" -> {
                        if (sawHandler) throw new Refused(DAMAGED);
                        sawHandler = true;
                        need(c, 4 + 4 + 4 + 12);
                        t.handler = fourCc(b, c.payload + 8);
                        zero(c.payload + 24, c.end - (c.payload + 24)); // the free-text name
                    }
                    case "minf" -> {
                        if (info != null) throw new Refused(DAMAGED);
                        info = c;
                    }
                    default -> neutralise(c);
                }
            }
            if (!sawHeader || !sawHandler || info == null) throw new Refused(DAMAGED);
            mediaInformation(info, t); // after hdlr: the sample entries are checked against the handler
        }

        private void mediaHeader(Child c, Track t) {
            int p = c.payload;
            int version = u8(p);
            long timescale;
            long duration;
            if (version == 1) {
                need(c, 4 + 28);
                zero(p + 4, 16);
                timescale = u32(p + 20);
                duration = u64(p + 24);
            } else if (version == 0) {
                need(c, 4 + 16);
                zero(p + 4, 8);
                timescale = u32(p + 12);
                duration = u32(p + 16);
            } else {
                throw new Refused(UNSUPPORTED);
            }
            if (timescale == 0) throw new Refused(DAMAGED);
            t.durationMs = millis(duration, timescale);
        }

        private void mediaInformation(Child minf, Track t) {
            boolean sawTable = false;
            boolean sawData = false;
            for (Child c : children(minf.payload, minf.end, minf.depth + 1)) {
                switch (c.type) {
                    case "vmhd", "smhd" -> { }
                    case "dinf" -> {
                        sawData = true;
                        dataInformation(c);
                    }
                    case "stbl" -> {
                        if (sawTable) throw new Refused(DAMAGED);
                        sawTable = true;
                        sampleTable(c, t);
                    }
                    default -> neutralise(c);
                }
            }
            if (!sawTable || !sawData) throw new Refused(DAMAGED);
        }

        /** Every data reference must say "the data is in this file" (flag 1); a link to another file is refused. */
        private void dataInformation(Child dinf) {
            for (Child c : children(dinf.payload, dinf.end, dinf.depth + 1)) {
                if (!"dref".equals(c.type)) {
                    neutralise(c);
                    continue;
                }
                need(c, 8);
                long count = u32(c.payload + 4);
                List<Child> entries = children(c.payload + 8, c.end, c.depth + 1);
                if (count != entries.size() || count < 1) throw new Refused(DAMAGED);
                for (Child e : entries) {
                    if (!"url ".equals(e.type) && !"urn ".equals(e.type)) throw new Refused(UNSUPPORTED);
                    need(e, 4);
                    if ((u32(e.payload) & 1) == 0) throw new Refused(UNSUPPORTED);
                    zero(e.payload + 4, e.end - (e.payload + 4)); // a self-contained entry carries no location
                }
            }
        }

        private void sampleTable(Child stbl, Track t) {
            boolean sawDescription = false;
            boolean sawOffsets = false;
            for (Child c : children(stbl.payload, stbl.end, stbl.depth + 1)) {
                if (SAMPLE_TABLE.contains(c.type)) continue;
                switch (c.type) {
                    case "stsd" -> {
                        if (sawDescription) throw new Refused(DAMAGED);
                        sawDescription = true;
                        sampleDescription(c, t);
                    }
                    case "stco", "co64" -> {
                        if (sawOffsets) throw new Refused(DAMAGED);
                        sawOffsets = true;
                        chunkOffsets(c, "co64".equals(c.type));
                    }
                    default -> neutralise(c);
                }
            }
            if (!sawDescription || !sawOffsets) throw new Refused(DAMAGED);
        }

        private void sampleDescription(Child stsd, Track t) {
            need(stsd, 8);
            long count = u32(stsd.payload + 4);
            List<Child> entries = children(stsd.payload + 8, stsd.end, stsd.depth + 1);
            if (count != 1 || entries.size() != 1) throw new Refused(UNSUPPORTED);
            Child entry = entries.get(0);
            int fixed;
            if ("vide".equals(t.handler)) {
                if (!VIDEO_CODECS.contains(entry.type)) throw new Refused(UNSUPPORTED);
                fixed = 78; // VisualSampleEntry fields after the box header
                need(entry, fixed);
                t.entryWidth = u16(entry.payload + 24);
                t.entryHeight = u16(entry.payload + 26);
                zero(entry.payload + 42, 32); // compressorname: free text
            } else if ("soun".equals(t.handler)) {
                if (!AUDIO_CODECS.contains(entry.type)) throw new Refused(UNSUPPORTED);
                fixed = 28; // AudioSampleEntry (version 0) fields after the box header
                need(entry, fixed);
                if (u16(entry.payload + 8) != 0) throw new Refused(UNSUPPORTED);
            } else {
                throw new Refused(TRACKS);
            }
            for (Child c : children(entry.payload + fixed, entry.end, entry.depth + 1, true)) {
                if (!CODEC_CONFIG.contains(c.type)) neutralise(c);
            }
        }

        /** Every chunk must start inside an mdat: nothing in the file can point a player at another box. */
        private void chunkOffsets(Child c, boolean wide) {
            need(c, 8);
            long count = u32(c.payload + 4);
            long room = (c.end - (c.payload + 8)) / (wide ? 8 : 4);
            if (count > room) throw new Refused(DAMAGED);
            for (int i = 0; i < count; i++) {
                int at = c.payload + 8 + i * (wide ? 8 : 4);
                long offset = wide ? u64(at) : u32(at);
                if (!inMediaData(offset)) throw new Refused(DAMAGED);
            }
        }

        private boolean inMediaData(long offset) {
            for (long[] range : mediaData) if (offset >= range[0] && offset < range[1]) return true;
            return false;
        }

        // ---- box walking inside the byte array ----

        private record Child(String type, int start, int payload, int end, int depth) {
        }

        private int headerOf(int start, int limit) {
            if (limit - start < 8) throw new Refused(DAMAGED);
            return u32(start) == 1 ? 16 : 8;
        }

        private List<Child> children(int from, int to, int depth) {
            return children(from, to, depth, false);
        }

        /** [lenient]: fewer than 8 bytes left at the end (padding some writers add in a sample entry) are zeroed. */
        private List<Child> children(int from, int to, int depth, boolean lenient) {
            if (depth > MAX_DEPTH) throw new Refused(UNSUPPORTED);
            List<Child> list = new ArrayList<>();
            int pos = from;
            while (pos < to) {
                if (to - pos < 8) {
                    if (!lenient) throw new Refused(DAMAGED);
                    zero(pos, to - pos);
                    break;
                }
                if (++boxes > MAX_MOOV_BOXES) throw new Refused(UNSUPPORTED);
                long size = u32(pos);
                String type = fourCc(b, pos + 4);
                int header = 8;
                if (size == 1) {
                    if (to - pos < 16) throw new Refused(DAMAGED);
                    size = u64(pos + 8);
                    header = 16;
                }
                if (size < header || size > to - pos) throw new Refused(DAMAGED); // size 0 is never valid in here
                int end = (int) (pos + size);
                list.add(new Child(type, pos, pos + header, end, depth));
                pos = end;
            }
            return list;
        }

        /** The box becomes "free" with every byte after its header zero (its size, so every offset, unchanged). */
        private void neutralise(Child c) {
            byte[] free = "free".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(free, 0, b, c.start + 4, 4);
            zero(c.payload, c.end - c.payload);
        }

        private void need(Child c, int bytes) {
            if (c.end - c.payload < bytes) throw new Refused(DAMAGED);
        }

        private void zero(int from, int length) {
            if (length > 0) java.util.Arrays.fill(b, from, from + length, (byte) 0);
        }

        private int u8(int at) {
            return b[at] & 0xFF;
        }

        private int u16(int at) {
            return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
        }

        private int s32(int at) {
            return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
        }

        private long u32(int at) {
            return Integer.toUnsignedLong(s32(at));
        }

        private long u64(int at) {
            long value = (u32(at) << 32) | u32(at + 4);
            if (value < 0) throw new Refused(DAMAGED);
            return value;
        }
    }

    private static final class Track {
        String handler;
        long durationMs;
        int width;
        int height;
        int entryWidth;
        int entryHeight;
        boolean rotated;
    }

    // ---- helpers ----

    static long millis(long duration, long timescale) {
        if (timescale == 0) return 0;
        // duration * 1000 / timescale without overflow (duration is at most 2^63 - 1 here)
        return java.math.BigInteger.valueOf(duration).multiply(java.math.BigInteger.valueOf(1000))
                .divide(java.math.BigInteger.valueOf(timescale)).min(java.math.BigInteger.valueOf(Long.MAX_VALUE))
                .longValue();
    }

    private static String fourCc(byte[] bytes, int at) {
        return new String(bytes, at, 4, StandardCharsets.ISO_8859_1);
    }

    private static byte[] read(FileChannel in, Box box) throws IOException {
        if (box.size > Integer.MAX_VALUE) throw new Refused(UNSUPPORTED);
        ByteBuffer buffer = ByteBuffer.allocate((int) box.size);
        readFully(in, buffer, box.start);
        return buffer.array();
    }

    private static void readFully(FileChannel in, ByteBuffer buffer, long position) throws IOException {
        long at = position;
        while (buffer.hasRemaining()) {
            int n = in.read(buffer, at);
            if (n < 0) throw new Refused(DAMAGED);
            at += n;
        }
    }

    private static void copy(FileChannel in, long size, Path output) throws IOException {
        try (FileChannel out = FileChannel.open(output, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            long done = 0;
            while (done < size) {
                long n = in.transferTo(done, size - done, out);
                if (n <= 0) throw new IOException("copy stalled");
                done += n;
            }
        }
    }

    private static void zeroBox(FileChannel out, Box box) throws IOException {
        out.write(ByteBuffer.wrap("free".getBytes(StandardCharsets.US_ASCII)), box.start + 4);
        long from = box.start + box.header;
        long end = box.start + box.size;
        ByteBuffer zeros = ByteBuffer.allocate(64 * 1024);
        while (from < end) {
            zeros.clear();
            zeros.limit((int) Math.min(zeros.capacity(), end - from));
            while (zeros.hasRemaining()) from += out.write(zeros, from);
        }
    }
}
