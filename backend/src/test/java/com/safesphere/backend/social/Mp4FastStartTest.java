package com.safesphere.backend.social;

import static com.safesphere.backend.support.TestVideos.video;
import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.social.media.Mp4FastStart;
import com.safesphere.backend.social.media.Mp4Sanitizer;
import com.safesphere.backend.support.TestVideos;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fast start: the movie box moves in front of the media data (a phone's MediaMuxer writes it last, which costs a player
 * several round trips on a slow link). Only boxes are reordered; every chunk offset must still point at the same sample
 * bytes, and the sanitizer must still accept (and measure the same) video.
 */
class Mp4FastStartTest {

    @TempDir
    Path dir;

    @Test
    void movieBoxLast_becomesFirst_samplesStayWhereTheOffsetsSay_andTheSanitizerStillAcceptsIt() throws Exception {
        Path sanitized = sanitized(video().moovLast().build());
        Mp4Sanitizer.Result before = Mp4Sanitizer.sanitize(sanitized, dir.resolve("before.mp4"));
        assertThat(topLevelTypes(Files.readAllBytes(sanitized))).startsWith("ftyp").endsWith("moov"); // really "last"

        Path fast = dir.resolve("fast.mp4");
        assertThat(Mp4FastStart.moveMovieFirst(sanitized, fast)).isTrue();

        byte[] out = Files.readAllBytes(fast);
        assertThat(out).hasSameSizeAs(Files.readAllBytes(sanitized));
        List<String> order = topLevelTypes(out);
        assertThat(order.subList(0, 2)).containsExactly("ftyp", "moov");
        assertThat(order).contains("mdat");
        assertSamplesAtTheirOffsets(out);
        assertThat(Mp4Sanitizer.sanitize(fast, dir.resolve("again.mp4"))).isEqualTo(before);
        for (String leak : List.of(TestVideos.GPS, TestVideos.XMP_GPS, TestVideos.ANDROID_VERSION, "udta", "uuid")) {
            assertThat(TestVideos.contains(out, leak)).as(leak).isFalse();
        }
    }

    @Test
    void everyMovieLastShape_isReordered_andStaysValid() throws Exception {
        for (TestVideos.Builder b : List.of(video().moovLast(), video().moovLast().largeMdat(),
                video().moovLast().version1(), video().moovLast().largeMdat().version1(),
                video().moovLast().noAudio())) {
            Path sanitized = sanitized(b.build());
            Mp4Sanitizer.Result before = Mp4Sanitizer.sanitize(sanitized, dir.resolve("b-" + System.nanoTime() + ".mp4"));
            Path fast = dir.resolve("f-" + System.nanoTime() + ".mp4");
            assertThat(Mp4FastStart.moveMovieFirst(sanitized, fast)).isTrue();
            assertThat(topLevelTypes(Files.readAllBytes(fast)).subList(0, 2)).containsExactly("ftyp", "moov");
            assertThat(Mp4Sanitizer.sanitize(fast, dir.resolve("a-" + System.nanoTime() + ".mp4"))).isEqualTo(before);
        }
    }

    @Test
    void alreadyMovieFirst_isLeftAlone_andNothingIsWritten() throws Exception {
        Path sanitized = sanitized(video().build()); // the fixture's default layout has moov first
        Path fast = dir.resolve("none.mp4");
        assertThat(Mp4FastStart.moveMovieFirst(sanitized, fast)).isFalse();
        assertThat(Files.exists(fast)).isFalse();
    }

    @Test
    void anythingThatIsNotAPlainMovie_isLeftAlone_withoutAnError() throws Exception {
        for (byte[] junk : List.of(new byte[0], new byte[7], "not a video at all, just text".getBytes(StandardCharsets.US_ASCII),
                Arrays.copyOf(video().moovLast().build(), 100), // cut off inside a box
                video().moovLast().build())) { // not sanitized: metadata still inside, but still a valid layout
            Path in = dir.resolve("junk-" + System.nanoTime() + ".bin");
            Files.write(in, junk);
            Path out = dir.resolve("junk-out-" + System.nanoTime() + ".bin");
            boolean moved = Mp4FastStart.moveMovieFirst(in, out);
            if (!moved) assertThat(Files.exists(out)).isFalse();
        }
    }

    // ---- helpers ----

    /** The fixture after the real sanitizer (what the service hands to the fast-start step). */
    private Path sanitized(byte[] input) throws Exception {
        Path in = dir.resolve("in-" + System.nanoTime() + ".mp4");
        Files.write(in, input);
        Path out = dir.resolve("san-" + System.nanoTime() + ".mp4");
        Mp4Sanitizer.sanitize(in, out);
        return out;
    }

    private static List<String> topLevelTypes(byte[] file) {
        List<String> types = new ArrayList<>();
        ByteBuffer b = ByteBuffer.wrap(file);
        int pos = 0;
        while (pos + 8 <= file.length) {
            long size = Integer.toUnsignedLong(b.getInt(pos));
            types.add(new String(file, pos + 4, 4, StandardCharsets.ISO_8859_1));
            if (size == 1) size = b.getLong(pos + 8);
            if (size < 8) break;
            pos += (int) size;
        }
        return types;
    }

    /** Each stco entry still points at the sample bytes the fixture put there. */
    private static void assertSamplesAtTheirOffsets(byte[] file) {
        List<Long> offsets = new ArrayList<>();
        byte[] stco = "stco".getBytes(StandardCharsets.US_ASCII);
        for (int i = TestVideos.indexOf(file, stco); i >= 0; ) {
            ByteBuffer b = ByteBuffer.wrap(file);
            offsets.add(Integer.toUnsignedLong(b.getInt(i + 4 + 4 + 4)));
            int next = TestVideos.indexOf(Arrays.copyOfRange(file, i + 4, file.length), stco);
            i = next < 0 ? -1 : i + 4 + next;
        }
        assertThat(offsets).hasSize(2);
        assertThat(Arrays.copyOfRange(file, offsets.get(0).intValue(), offsets.get(0).intValue()
                + TestVideos.PICTURE.length)).isEqualTo(TestVideos.PICTURE);
        assertThat(Arrays.copyOfRange(file, offsets.get(1).intValue(), offsets.get(1).intValue()
                + TestVideos.SOUND.length)).isEqualTo(TestVideos.SOUND);
    }
}
