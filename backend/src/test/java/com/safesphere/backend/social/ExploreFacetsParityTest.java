package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.social.policy.ExploreFacets;
import com.safesphere.backend.social.policy.ExploreFacets.Facet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Phase 17G-1 (G-1): the ONE tag list. The file the backend reads, the copy bundled in the app and the V29 CHECK list must
 * be the same; codes fit the VARCHAR(20) column; no tag says anything about safety, lighting, women or danger (Nearby covers
 * conditions, and no text may claim a place or person is safe).
 */
class ExploreFacetsParityTest {

    private static final Path FILE = Path.of("src/main/resources/explore/explore-facets-v1.tsv");
    private static final Path APP_COPY = Path.of("../android/app/src/main/resources/explore/explore-facets-v1.tsv");
    private static final Path V29 = Path.of("src/main/resources/db/migration/V29__explore_facets.sql");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "safe|safety|danger|women|woman|ladies|lit\\b|lighting|harass|unsafe", Pattern.CASE_INSENSITIVE);

    @Test
    void thirteenTags_withValidCodes_andThreeNames() {
        List<Facet> all = ExploreFacets.all();
        assertThat(all).hasSize(13);
        for (Facet facet : all) {
            assertThat(facet.code()).matches("[A-Z][A-Z_]{1,19}");
            assertThat(facet.english()).isNotBlank().hasSizeLessThanOrEqualTo(30);
            assertThat(facet.tamil()).isNotBlank();
            assertThat(facet.tanglish()).isNotBlank();
        }
        assertThat(ExploreFacets.codes()).hasSize(13);
    }

    @Test
    void theAppCopy_isByteIdentical() throws IOException {
        assertThat(Files.readAllBytes(APP_COPY)).as("the app's copy of explore-facets-v1.tsv")
                .isEqualTo(Files.readAllBytes(FILE));
    }

    @Test
    void v29CheckList_isExactlyTheFileCodes_inTheSameOrder() throws IOException {
        String sql = Files.readString(V29, StandardCharsets.UTF_8);
        Matcher check = Pattern.compile("CHECK \\(facet IN \\(([^)]*)\\)\\)", Pattern.DOTALL).matcher(sql);
        assertThat(check.find()).as("the CHECK list in V29").isTrue();
        List<String> inSql = new ArrayList<>();
        Matcher code = Pattern.compile("'([A-Z_]+)'").matcher(check.group(1));
        while (code.find()) inSql.add(code.group(1));
        assertThat(inSql).containsExactlyElementsOf(ExploreFacets.all().stream().map(Facet::code).toList());
    }

    @Test
    void noTag_says_anythingAboutSafety() {
        for (Facet facet : ExploreFacets.all()) {
            for (String text : List.of(facet.code(), facet.english(), facet.tanglish())) {
                assertThat(FORBIDDEN.matcher(text.toLowerCase(Locale.ROOT)).find())
                        .as("tag " + facet.code() + " must not mention safety: " + text).isFalse();
            }
        }
    }

    @Test
    void check_acceptsListedCodes_andRefusesTheRest() {
        assertThat(ExploreFacets.check(null, 4).ok()).isTrue();
        assertThat(ExploreFacets.check(List.of(), 4).codes()).isEmpty();
        assertThat(ExploreFacets.check(List.of(" quiet ", "SCENIC"), 4).codes()).containsExactly("QUIET", "SCENIC");
        assertThat(ExploreFacets.check(List.of("QUIET", "QUIET"), 4).problem()).isEqualTo("Choose each tag only once");
        assertThat(ExploreFacets.check(List.of("NOPE"), 4).problem()).isEqualTo("Choose tags from the list");
        assertThat(ExploreFacets.check(java.util.Arrays.asList("QUIET", null), 4).problem()).isNotNull();
        assertThat(ExploreFacets.check(List.of("QUIET", "LIVELY", "SCENIC", "OPEN_LATE", "GOOD_ALONE"), 4).problem())
                .isEqualTo("Choose at most 4 tags");
    }
}
