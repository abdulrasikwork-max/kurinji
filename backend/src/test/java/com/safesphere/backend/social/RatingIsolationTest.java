package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Phase 17G-2 (U5) invariants (comments are ignored: they may name what the code must not do). The guarded rating is a small
 * island: only a short, named list of files may touch "I went" or the rating; nothing that orders, ranks or lists places or posts
 * reads it; no public answer carries a count, a name or a time of anybody's rating; no outbound call and no scheduler.
 */
class RatingIsolationTest {

    private static final Path MAIN = Path.of("src", "main", "java", "com", "safesphere", "backend");
    private static final Path SQL = Path.of("src", "main", "resources", "db", "migration", "V30__place_visits.sql");

    private static final Pattern TOKENS = Pattern.compile(
            "PlaceVisit|PlaceRatings|RatingPolicy|VisitResponse|RatingRequest|place_visits|placeVisits|visitService|\\.ratings\\b");

    /** Everything that may mention "I went" or the rating, and why. */
    private static final Set<String> ALLOWED = Set.of(
            "social/entity/PlaceVisit.java", "social/repository/PlaceVisitRepository.java", "social/policy/RatingPolicy.java",
            "social/service/PlaceRatings.java", "social/service/PlaceVisitService.java",
            "social/controller/PlaceController.java",   // the four endpoints
            "social/dto/PlaceDtos.java",                 // RatingRequest, VisitResponse
            "social/dto/DiscoverDtos.java",              // the place page carries the text and the caller's own visit
            "social/service/DiscoverService.java",       // assembling that place page (and nothing else)
            "moderation/service/ContentModerationService.java", // a merged place takes its visits along
            "account/service/AccountEraser.java");       // erasure

    @Test
    void onlyTheNamedFiles_touchVisitsOrRatings() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (TOKENS.matcher(code(file)).find()) found.add(MAIN.relativize(file).toString().replace('\\', '/'));
            }
        }
        assertThat(found).as("files that mention I went / ratings").isSubsetOf(ALLOWED);
        assertThat(found).contains("social/service/PlaceVisitService.java", "social/service/PlaceRatings.java");
    }

    @Test
    void nothingThatOrdersOrListsReadsARating() throws IOException {
        for (String name : List.of("social/repository/PostRepository.java", "social/service/ExploreService.java",
                "social/policy/ExplorePolicy.java", "social/policy/EditionPolicy.java", "social/service/PostMarkService.java",
                "social/repository/PlaceRepository.java", "social/service/PlaceService.java")) {
            assertThat(TOKENS.matcher(code(MAIN.resolve(name))).find()).as(name + " must not read visits or ratings").isFalse();
        }
        String discover = code(MAIN.resolve("social/service/DiscoverService.java"));
        assertThat(count(discover, "ratings.textFor(")).as("the place page asks for the text once").isEqualTo(1);
        assertThat(count(discover, "visitService.")).as("and for the caller's own visit once").isEqualTo(1);
    }

    @Test
    void noPublicAnswerCarriesACountANameOrATime() throws IOException {
        String place = code(MAIN.resolve("social/dto/PlaceDtos.java"));
        String discover = code(MAIN.resolve("social/dto/DiscoverDtos.java"));
        Pattern forbidden = Pattern.compile("(?i)raters|ratingCount|votes|average|ratedAt|createdAt|distribution|histogram");
        assertThat(forbidden.matcher(place).find()).isFalse();
        assertThat(forbidden.matcher(discover).find()).isFalse();
        // the public number is a String and the caller's own visit has only their own rating
        assertThat(discover).contains("String rating,").contains("PlaceDtos.VisitResponse yourVisit");
        assertThat(place).contains("record VisitResponse(Integer rating)");
        String policy = code(MAIN.resolve("social/policy/RatingPolicy.java"));
        assertThat(policy).contains("public static String display(");
    }

    @Test
    void theRatingCodeHasNoOutboundCallNoSchedulerAndNoLogging() throws IOException {
        Pattern bad = Pattern.compile("HttpClient|RestTemplate|WebClient|java\\.net\\.http|@Scheduled|@Async|LoggerFactory|log\\.");
        for (String name : List.of("social/service/PlaceRatings.java", "social/service/PlaceVisitService.java",
                "social/policy/RatingPolicy.java", "social/entity/PlaceVisit.java")) {
            assertThat(bad.matcher(code(MAIN.resolve(name))).find()).as(name).isFalse();
        }
    }

    @Test
    void theOnlyPublicRatingIsTheGuardedAverage_readsAreByTheOwnersIdOrReturnTwoNumbers() throws IOException {
        String repository = code(MAIN.resolve("social/repository/PlaceVisitRepository.java"));
        // every per-row read is by (user, place) or by place for a merge; the one cross-person read returns two numbers
        assertThat(repository).contains("findByUserIdAndPlaceId").contains("Totals totals(");
        assertThat(repository).doesNotContain("findByUserId(").doesNotContain("List<PlaceVisit> findAll");
        String ratings = code(MAIN.resolve("social/service/PlaceRatings.java"));
        assertThat(ratings).contains("DailyStateService.INDIA").contains("memo");
    }

    @Test
    void theMigration_keepsTheRatingOnTheVisitRow_withBothChecks() throws IOException {
        String sql = Files.readString(SQL, StandardCharsets.UTF_8);
        assertThat(sql).contains("UNIQUE (user_id, place_id)").contains("ck_place_visits_rating")
                .contains("rating BETWEEN 1 AND 5").contains("ck_place_visits_rated_at").contains("ON DELETE CASCADE");
        assertThat(sql.toLowerCase()).doesNotContain("create table place_ratings");
    }

    private static long count(String text, String needle) {
        long n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) n++;
        return n;
    }

    /** The source without comments, so only code is scanned. */
    private static String code(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", " ");
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            int at = line.indexOf("//");
            lines.add(at >= 0 && !line.substring(0, at).contains("\"") ? line.substring(0, at) : line);
        }
        return String.join("\n", lines);
    }
}
