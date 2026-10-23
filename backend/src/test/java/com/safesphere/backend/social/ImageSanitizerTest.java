package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.safesphere.backend.social.media.ImageSanitizer;
import com.safesphere.backend.social.media.ImageSanitizer.Refused;
import com.safesphere.backend.support.TestImages;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Phase 13B (design §4.4 invariant 7): no stored photo carries EXIF; hostile files are refused before decoding. */
class ImageSanitizerTest {

    private static final int MAX = 5 * 1024 * 1024;

    @Test
    void jpegWithExifGps_comesOutWithoutAnyExifSegment() throws Exception {
        byte[] input = TestImages.jpegWithExifGps(64, 48);
        assertThat(TestImages.containsExifSegment(input)).isTrue();          // the test input really has one
        ImageSanitizer.Result result = ImageSanitizer.sanitize(input, MAX);
        assertThat(TestImages.containsExifSegment(result.jpeg())).isFalse();
        assertThat(new String(result.jpeg(), StandardCharsets.ISO_8859_1)).doesNotContain("GPSLatitude")
                .doesNotContain("SECRET-HOME");
        assertThat(result.width()).isEqualTo(64);
        assertThat(result.height()).isEqualTo(48);
        assertThat(ImageIO.read(new ByteArrayInputStream(result.jpeg()))).isNotNull();
    }

    @Test
    void png_becomesAJpeg() {
        ImageSanitizer.Result result = ImageSanitizer.sanitize(TestImages.png(30, 20), MAX);
        assertThat(result.jpeg()[0] & 0xFF).isEqualTo(0xFF);
        assertThat(result.jpeg()[1] & 0xFF).isEqualTo(0xD8);
        assertThat(result.width()).isEqualTo(30);
    }

    @Test
    void oversizedPixelCounts_areRefusedBeforeDecoding() {
        assertThatThrownBy(() -> ImageSanitizer.sanitize(TestImages.pngClaiming(50_000, 50_000), MAX))
                .isInstanceOf(Refused.class).hasMessageContaining("4096");
        assertThatThrownBy(() -> ImageSanitizer.sanitize(TestImages.pngClaiming(4097, 10), MAX))
                .isInstanceOf(Refused.class).hasMessageContaining("4096");
    }

    @Test
    void nonImages_truncatedFiles_emptyAndTooLarge_areRefused() {
        assertThatThrownBy(() -> ImageSanitizer.sanitize("GIF89a not allowed".getBytes(StandardCharsets.US_ASCII), MAX))
                .isInstanceOf(Refused.class).hasMessageContaining("JPEG and PNG");
        assertThatThrownBy(() -> ImageSanitizer.sanitize("<svg xmlns=...>".getBytes(StandardCharsets.US_ASCII), MAX))
                .isInstanceOf(Refused.class);
        byte[] jpeg = TestImages.jpeg(200, 200);
        assertThatThrownBy(() -> ImageSanitizer.sanitize(Arrays.copyOf(jpeg, jpeg.length / 2), MAX))
                .isInstanceOf(Refused.class);
        byte[] png = TestImages.png(200, 200);
        assertThatThrownBy(() -> ImageSanitizer.sanitize(Arrays.copyOf(png, png.length / 2), MAX))
                .isInstanceOf(Refused.class);
        assertThatThrownBy(() -> ImageSanitizer.sanitize(new byte[0], MAX)).isInstanceOf(Refused.class);
        assertThatThrownBy(() -> ImageSanitizer.sanitize(jpeg, jpeg.length - 1)).isInstanceOf(Refused.class)
                .hasMessageContaining("5 MB");
    }
}
