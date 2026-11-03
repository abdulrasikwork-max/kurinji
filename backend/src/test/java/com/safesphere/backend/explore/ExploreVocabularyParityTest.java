package com.safesphere.backend.explore;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.social.entity.PostEnums.Daypart;
import com.safesphere.backend.social.policy.ExploreFacets;
import com.safesphere.backend.social.policy.ExploreFacets.Facet;
import com.safesphere.backend.social.policy.ProfileLists.Interest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Phase 17G-3: the vocabulary the optional understanding is told (moderation-service/data/explore-vocabulary-v1.tsv) must be
 * exactly the backend's closed lists - the category enum, the Explore tag file and the day parts - in the same order, so the
 * provider can never be offered a code the engine does not know, nor miss one it does. Descriptions say nothing about safety.
 */
class ExploreVocabularyParityTest {

    private static final Path FILE = Path.of("..", "moderation-service", "data", "explore-vocabulary-v1.tsv");
    private static final Pattern FORBIDDEN = Pattern.compile("(?i)\\bsafe\\b|safety|danger|women|woman|ladies|lighting|harass|unsafe");

    private List<String[]> lines() throws IOException {
        List<String[]> out = new ArrayList<>();
        for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            out.add(line.split("\t", -1));
        }
        return out;
    }

    private List<String> codes(String kind) throws IOException {
        return lines().stream().filter(l -> l[0].equals(kind)).map(l -> l[1]).toList();
    }

    @Test
    void categories_areTheInterestEnum() throws IOException {
        assertThat(codes("CATEGORY")).containsExactlyElementsOf(Arrays.stream(Interest.values()).map(Enum::name).toList());
    }

    @Test
    void tags_areTheExploreTagFile() throws IOException {
        assertThat(codes("FACET")).containsExactlyElementsOf(ExploreFacets.all().stream().map(Facet::code).toList());
    }

    @Test
    void dayParts_areTheDaypartEnum() throws IOException {
        assertThat(codes("DAYPART")).containsExactlyElementsOf(Arrays.stream(Daypart.values()).map(Enum::name).toList());
    }

    @Test
    void everyLineIsKindCodeDescription_andSaysNothingAboutSafety() throws IOException {
        for (String[] line : lines()) {
            assertThat(line).hasSize(3);
            assertThat(line[0]).isIn("CATEGORY", "FACET", "DAYPART");
            assertThat(line[1]).matches("[A-Z][A-Z_]+");
            assertThat(line[2]).isNotBlank();
            assertThat(FORBIDDEN.matcher(line[2]).find()).as(line[1] + ": " + line[2]).isFalse();
        }
    }
}
