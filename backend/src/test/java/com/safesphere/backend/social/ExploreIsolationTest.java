package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Phase 17G-1 invariants of the Explore CODE (comments are ignored: they may name what the code must not do):
 * it reads no safety, community-signal, intelligence, evaluation or moderation package; it makes no outbound call; and it has
 * no popularity input: nothing in it counts posts, people, reactions, perspectives or ratings, and it never reads a post's
 * hidden creation time (others' lists are ordered by visible_from).
 */
class ExploreIsolationTest {

    private static final Path SOCIAL = Path.of("src", "main", "java", "com", "safesphere", "backend", "social");
    private static final List<Path> EXPLORE = List.of(
            SOCIAL.resolve(Path.of("policy", "ExplorePolicy.java")),
            SOCIAL.resolve(Path.of("policy", "ExploreFacets.java")),
            SOCIAL.resolve(Path.of("service", "ExploreService.java")),
            SOCIAL.resolve(Path.of("controller", "ExploreController.java")),
            SOCIAL.resolve(Path.of("dto", "ExploreDtos.java")));

    private static final Pattern OTHER_AREAS = Pattern.compile(
            "com\\.safesphere\\.backend\\.(emergency|emergencycontext|escalation|journey|evidence|safetycontact|community"
                    + "|intelligence|evaluation|moderation|escalation|circlealert)\\b");
    private static final Pattern OUTBOUND = Pattern.compile("HttpClient|RestTemplate|WebClient|java\\.net\\.http|@Async|@Scheduled");
    private static final Pattern POPULARITY = Pattern.compile(
            "getCreatedAt|createdAt|Butterfl|SavedPost|CommentRepository|Comment\\b|[Rr]ating|[Pp]opular|COUNT\\(|\\.count\\(|"
                    + "countBy|Collectors\\.counting");

    @Test
    void theExploreCode_readsNoSafetyCommunityIntelligenceEvaluationOrModerationPackage() throws IOException {
        for (Path file : EXPLORE) {
            assertThat(OTHER_AREAS.matcher(code(file)).find()).as(file.getFileName() + " must stay in social").isFalse();
        }
    }

    @Test
    void theExploreCode_makesNoOutboundCallAndSchedulesNothing() throws IOException {
        for (Path file : EXPLORE) {
            assertThat(OUTBOUND.matcher(code(file)).find()).as(file.getFileName() + " must not call out").isFalse();
        }
    }

    @Test
    void theExploreCode_hasNoPopularityInput() throws IOException {
        for (Path file : EXPLORE) {
            String code = code(file);
            assertThat(POPULARITY.matcher(code).find()).as(file.getFileName() + " must not count or rank by popularity").isFalse();
        }
    }

    @Test
    void theExploreQuery_readsOnlyEveryoneExactExperiencesByVisibleFrom() throws IOException {
        String repository = Files.readString(SOCIAL.resolve(Path.of("repository", "PostRepository.java")), StandardCharsets.UTF_8);
        String query = repository.substring(repository.indexOf("exploreCandidates") - 900, repository.indexOf("exploreCandidates") + 200);
        assertThat(query).contains("EVERYONE").contains("PostType.EXPERIENCE").contains("Precision.EXACT")
                .contains("p.authorId <> :viewer").contains("mergedInto IS NULL").contains("ORDER BY p.visibleFrom DESC")
                .doesNotContain("createdAt");
    }

    /** The source without comments and string literals' line comments, so only code is scanned. */
    private static String code(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        text = text.replaceAll("(?s)/\\*.*?\\*/", " ");
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            int at = line.indexOf("//");
            lines.add(at >= 0 && !line.substring(0, at).contains("\"") ? line.substring(0, at) : line);
        }
        return String.join("\n", lines);
    }
}
