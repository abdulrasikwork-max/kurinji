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
 * Fast start: puts the movie box (moov, the index a player needs before it can show a first picture) in front of the
 * media data. MediaMuxer on the phone writes it at the END, so a player must first ask for the tail of the file, which
 * over a slow link (the laptop pilot: about 2.4 Mbit/s up, 1 to 2 s per request) costs several round trips before
 * anything plays.
 *
 * It runs on the copy Mp4Sanitizer already checked and cleaned, and only ever REORDERS boxes: no byte of a sample or of
 * the (already cleaned) movie box changes except the chunk offsets (stco / co64), which are moved by the size of the
 * movie box. The caller proves the result by running the sanitizer over it again (every chunk offset must still point
 * into the media data) and keeps the original copy if anything is off: this is an optimisation, never a reason to
 * refuse a video.
 *
 * Plain files only: a fragmented or otherwise unusual layout was already refused by the sanitizer.
 */
public final class Mp4FastStart {

    private static final int MAX_MOOV_BYTES = 8 * 1024 * 1024;
    private static final int MAX_TOP_LEVEL_BOXES = 64;
    private static final int MAX_DEPTH = 8;
    /** The only boxes walked on the way to a chunk-offset table. */
    private static final Set<String> CONTAINERS = Set.of("moov", "trak", "mdia", "minf", "stbl");

    private Mp4FastStart() {
    }

    private record Box(String type, long start, int header, long size) {
    }

    /**
     * Writes [output] = ftyp, moov, then every other box in its original order, with the chunk offsets corrected.
     * Returns false (and leaves [output] untouched) when there is nothing to do: the movie box is already first, or the
     * layout is not one this step handles. Throws IOException only for a failing disk.
     */
    public static boolean moveMovieFirst(Path input, Path output) throws IOException {
        try (FileChannel in = FileChannel.open(input, StandardOpenOption.READ)) {
            long size = in.size();
            List<Box> top = topLevel(in, size);
            if (top == null || top.size() < 3 || !"ftyp".equals(top.get(0).type)) return false;
            int moovIndex = -1;
            int firstMdat = -1;
            for (int i = 0; i < top.size(); i++) {
                String type = top.get(i).type;
                if ("moov".equals(type)) {
                    if (moovIndex >= 0) return false;
                    moovIndex = i;
                } else if ("mdat".equals(type) && firstMdat < 0) {
                    firstMdat = i;
                }
            }
            if (moovIndex < 0 || firstMdat < 0 || moovIndex < firstMdat) return false; // no movie, no data, or already first
            Box moov = top.get(moovIndex);
            if (moov.size > MAX_MOOV_BYTES) return false;

            byte[] movie = new byte[(int) moov.size];
            readFully(in, ByteBuffer.wrap(movie), moov.start);
            if (!shiftChunkOffsets(movie, moov.start, moov.size)) return false;

            try (FileChannel out = FileChannel.open(output, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                long at = 0;
                Box ftyp = top.get(0);
                at += transfer(in, out, ftyp.start, ftyp.size, at);
                at += write(out, movie, at);
                for (int i = 1; i < top.size(); i++) {
                    if (i == moovIndex) continue;
                    Box box = top.get(i);
                    at += transfer(in, out, box.start, box.size, at);
                }
                if (at != size) throw new IOException("fast start length mismatch");
                out.force(true);
            }
            return true;
        }
    }

    // ---- top level ----

    private static List<Box> topLevel(FileChannel in, long fileSize) throws IOException {
        List<Box> boxes = new ArrayList<>();
        long pos = 0;
        while (pos < fileSize) {
            if (boxes.size() >= MAX_TOP_LEVEL_BOXES || fileSize - pos < 8) return null;
            ByteBuffer head = ByteBuffer.allocate(16);
            head.limit(8);
            readFully(in, head, pos);
            long size = Integer.toUnsignedLong(head.getInt(0));
            String type = new String(head.array(), 4, 4, StandardCharsets.ISO_8859_1);
            int header = 8;
            if (size == 1) {
                if (fileSize - pos < 16) return null;
                head.limit(16);
                head.position(8);
                readFully(in, head, pos + 8);
                size = head.getLong(8);
                header = 16;
            } else if (size == 0) {
                size = fileSize - pos;
            }
            if (size < header || size > fileSize - pos) return null;
            boxes.add(new Box(type, pos, header, size));
            pos += size;
        }
        return boxes;
    }

    // ---- the movie box: chunk offsets ----

    /**
     * Adds [moovSize] to every chunk offset that points BEFORE the movie box's old position (media data that stays
     * where it is relative to the file start but now has the movie box in front of it). False when the box tree is not
     * what it should be, or a 32-bit offset would overflow.
     */
    private static boolean shiftChunkOffsets(byte[] b, long oldMoovStart, long moovSize) {
        return walk(b, 0, b.length, 0, oldMoovStart, moovSize);
    }

    private static boolean walk(byte[] b, int from, int to, int depth, long oldMoovStart, long moovSize) {
        if (depth > MAX_DEPTH) return false;
        int pos = from;
        while (pos < to) {
            if (to - pos < 8) return false;
            long size = u32(b, pos);
            String type = new String(b, pos + 4, 4, StandardCharsets.ISO_8859_1);
            int header = 8;
            if (size == 1) {
                if (to - pos < 16) return false;
                size = u64(b, pos + 8);
                header = 16;
            }
            if (size < header || size > to - pos) return false;
            int payload = pos + header;
            int end = (int) (pos + size);
            if (depth == 0 && !"moov".equals(type)) return false;
            if (CONTAINERS.contains(type)) {
                if (!walk(b, payload, end, depth + 1, oldMoovStart, moovSize)) return false;
            } else if ("stco".equals(type) || "co64".equals(type)) {
                if (!shiftTable(b, payload, end, "co64".equals(type), oldMoovStart, moovSize)) return false;
            }
            pos = end;
        }
        return true;
    }

    private static boolean shiftTable(byte[] b, int payload, int end, boolean wide, long oldMoovStart, long moovSize) {
        if (end - payload < 8) return false;
        long count = u32(b, payload + 4);
        int width = wide ? 8 : 4;
        if (count > (end - (payload + 8)) / width) return false;
        for (int i = 0; i < count; i++) {
            int at = payload + 8 + i * width;
            long offset = wide ? u64(b, at) : u32(b, at);
            if (offset >= oldMoovStart) continue; // after the old movie box: its position does not change
            long moved = offset + moovSize;
            if (wide) {
                putU64(b, at, moved);
            } else {
                if (moved > 0xFFFFFFFFL) return false;
                putU32(b, at, moved);
            }
        }
        return true;
    }

    // ---- bytes ----

    private static long u32(byte[] b, int at) {
        return ((b[at] & 0xFFL) << 24) | ((b[at + 1] & 0xFFL) << 16) | ((b[at + 2] & 0xFFL) << 8) | (b[at + 3] & 0xFFL);
    }

    private static long u64(byte[] b, int at) {
        long value = (u32(b, at) << 32) | u32(b, at + 4);
        if (value < 0) throw new IllegalArgumentException("offset too large");
        return value;
    }

    private static void putU32(byte[] b, int at, long v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }

    private static void putU64(byte[] b, int at, long v) {
        putU32(b, at, v >>> 32);
        putU32(b, at + 4, v & 0xFFFFFFFFL);
    }

    private static void readFully(FileChannel in, ByteBuffer buffer, long position) throws IOException {
        long at = position;
        while (buffer.hasRemaining()) {
            int n = in.read(buffer, at);
            if (n < 0) throw new IOException("unexpected end of file");
            at += n;
        }
    }

    private static int write(FileChannel out, byte[] bytes, long position) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long at = position;
        while (buffer.hasRemaining()) at += out.write(buffer, at);
        return bytes.length;
    }

    /** Copies [length] bytes of [in] from [from] to [out] at [to]; returns [length]. */
    private static long transfer(FileChannel in, FileChannel out, long from, long length, long to) throws IOException {
        long done = 0;
        while (done < length) {
            long n = in.transferTo(from + done, length - done, out.position(to + done));
            if (n <= 0) throw new IOException("copy stalled");
            done += n;
        }
        return length;
    }
}
