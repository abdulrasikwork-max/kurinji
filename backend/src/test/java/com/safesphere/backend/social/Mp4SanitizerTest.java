package com.safesphere.backend.social;

import static com.safesphere.backend.support.TestVideos.video;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.safesphere.backend.social.media.Mp4Sanitizer;
import com.safesphere.backend.support.TestImages;
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
 * Phase 17C (D-6): the server's video check. Every kind of metadata is gone from the stored copy, every chunk offset
 * still points at the same sample bytes (boxes are overwritten, never cut out), and anything unusual is refused.
 */
class Mp4SanitizerTest {

    @TempDir
    Path dir;

    @Test
    void stripsEveryMetadataBox_keepsTheSamplesWhereTheOffsetsSay_moovFirst() throws Exception {
        byte[] input = video().build();
        assertThat(TestVideos.contains(input, TestVideos.GPS)).isTrue(); // the fixture really carries a position
        Run run = run(input);

        assertThat(run.result).isEqualTo(new Mp4Sanitizer.Result(640, 360, 5_000));
        assertThat(run.output).hasSameSizeAs(input);
        for (String leak : List.of(TestVideos.GPS, TestVideos.XMP_GPS, TestVideos.ANDROID_VERSION, TestVideos.FREE_TEXT,
                TestVideos.HANDLER_NAME, TestVideos.COMPRESSOR, "©xyz", "udta", "uuid", "xmpmeta", "gpsx")) {
            assertThat(TestVideos.contains(run.output, leak)).as(leak).isFalse();
        }
        assertSamplesAtTheirOffsets(run.output);
        assertTimesZero(run.output);
        assertThat(Mp4Sanitizer.sanitize(write(run.output), dir.resolve("again.mp4")))
                .isEqualTo(run.result); // the stripped copy is itself a valid upload
    }

    @Test
    void moovAfterTheMediaData_largeSizes_andVersion1Headers() throws Exception {
        for (TestVideos.Builder b : List.of(video().moovLast(), video().largeMdat(), video().version1(),
                video().moovLast().largeMdat().version1())) {
            Run run = run(b.build());
            assertThat(run.result.durationMs()).isEqualTo(5_000);
            assertThat(TestVideos.contains(run.output, TestVideos.GPS)).isFalse();
            assertSamplesAtTheirOffsets(run.output);
            assertTimesZero(run.output);
        }
    }

    @Test
    void sizeOfAPictureOnItsSide_isShownTheWayItPlays() throws Exception {
        assertThat(run(video().size(1920, 1080).rotate90().build()).result)
                .isEqualTo(new Mp4Sanitizer.Result(1080, 1920, 5_000));
    }

    @Test
    void pictureOnlyVideo_andOtherCodecs_areAccepted() throws Exception {
        assertThat(run(video().noAudio().noMetadata().build()).result.width()).isEqualTo(640);
        for (String codec : List.of("hvc1", "hev1", "avc3", "mp4v", "av01", "vp09")) {
            assertThat(run(video().videoCodec(codec).build()).result.height()).isEqualTo(360);
        }
        assertThat(run(video().brand("mp42").build()).result.durationMs()).isEqualTo(5_000);
        assertThat(run(video().brand("3gp4").build()).result.durationMs()).isEqualTo(5_000);
    }

    @Test
    void sixtySecondsAndHalfASecondOfRounding_isTheLongestAccepted() throws Exception {
        assertThat(run(video().durationMs(60_500).build()).result.durationMs()).isEqualTo(60_500);
        assertRefused(video().durationMs(60_501).build(), "at most 60 seconds");
        assertRefused(video().durationMs(0).build(), "damaged");
    }

    @Test
    void refusesWhatIsNotAPlainMp4() {
        assertRefused(TestImages.jpeg(10, 10), "MP4 only");
        assertRefused("not a video, just text that is long enough".getBytes(StandardCharsets.US_ASCII), "");
        assertRefused(new byte[8], "");
        assertRefused(video().brand("qt  ").build(), "MP4 only");             // QuickTime
        assertRefused(video().topLevel("moof").build(), "can't be shared");  // fragmented
        assertRefused(video().topLevel("sidx").build(), "can't be shared");
        assertRefused(video().topLevel("abcd").build(), "can't be shared");  // unknown top-level box
        assertRefused(video().fragmentedMoov().build(), "can't be shared");
        assertRefused(video().videoCodec("encv").build(), "can't be shared"); // encrypted
        assertRefused(video().externalReference().build(), "can't be shared");
        assertRefused(video().size(5000, 300).build(), "4096 pixels");
    }

    @Test
    void refusesExtraTracks_soAPositionInATrackCannotGetThrough() {
        assertRefused(video().extraTrack("meta").build(), "one picture track");  // timed metadata (GPS) track
        assertRefused(video().extraTrack("text").build(), "one picture track");
        assertRefused(video().secondVideo().build(), "one picture track");
    }

    @Test
    void refusesDamagedFiles() {
        byte[] good = video().build();
        assertRefused(Arrays.copyOf(good, good.length - 5), "");             // cut short
        assertRefused(video().offsetOutsideMediaData().build(), "damaged");   // a chunk pointing at another box
        byte[] lying = good.clone();
        int moov = TestVideos.indexOf(lying, "moov".getBytes(StandardCharsets.US_ASCII)) - 4;
        ByteBuffer.wrap(lying).putInt(moov, Integer.MAX_VALUE);               // a size larger than the file
        assertRefused(lying, "damaged");
        byte[] child = good.clone();
        int mvhd = TestVideos.indexOf(child, "mvhd".getBytes(StandardCharsets.US_ASCII)) - 4;
        ByteBuffer.wrap(child).putInt(mvhd, 4);                               // a size smaller than its header
        assertRefused(child, "damaged");
    }

    // ---- helpers ----

    private record Run(byte[] output, Mp4Sanitizer.Result result) {
    }

    private Run run(byte[] input) throws Exception {
        Path out = dir.resolve("out-" + System.nanoTime() + ".mp4");
        Mp4Sanitizer.Result result = Mp4Sanitizer.sanitize(write(input), out);
        return new Run(Files.readAllBytes(out), result);
    }

    private Path write(byte[] bytes) throws Exception {
        Path in = dir.resolve("in-" + System.nanoTime() + ".mp4");
        Files.write(in, bytes);
        return in;
    }

    private void assertRefused(byte[] input, String messagePart) {
        assertThatThrownBy(() -> run(input)).isInstanceOf(Mp4Sanitizer.Refused.class)
                .hasMessageContaining(messagePart);
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

    /** No creation / modification time survives in mvhd, tkhd or mdhd. */
    private static void assertTimesZero(byte[] file) {
        byte[] created = ByteBuffer.allocate(4).putInt(TestVideos.CREATED).array();
        assertThat(TestVideos.indexOf(file, created)).isEqualTo(-1);
    }
}
