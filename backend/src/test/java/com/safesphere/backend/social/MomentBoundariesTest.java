package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.social.dto.MomentDtos;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Phase N4 guard (N-58, docs/PHASE_N4.md §1 "never reintroduce"): the moment code and its tables carry no location of
 * any kind, no presence, no viewer or read tracking, no engagement count and no ranking; and nothing of the deferred
 * kinds (voice, video: N-44). Enforced on the source (comments stripped, so the rules may be explained in words) and on
 * the answers' field names, so a later change cannot bring one of these back unnoticed.
 */
class MomentBoundariesTest {

    private static final Path MAIN = Path.of("src", "main", "java", "com", "safesphere", "backend");
    private static final Path V35 = Path.of("src", "main", "resources", "db", "migration", "V35__moments.sql");

    private static final Pattern FORBIDDEN = Pattern.compile("(?i)(latitude|longitude|\\blat\\b|\\blng\\b|geohash|geocell"
            + "|\\bcell\\b|area_cell|coordinate|distance|nearby|proximity|place_id|placeId|\\bcity\\b|cities"
            + "|last_?seen|\\bonline\\b|presence|view_?count|viewed_?by|seen_?by|seen_?at|read_?at|read_?receipt"
            + "|like_?count|reaction|upvote|follower|\\brank|ranking|\\bscore\\b|trending|popular"
            + "|voice|audio|video|duration_ms)");

    @Test
    void theMomentCode_hasNoLocationPresenceTrackingCountOrRanking() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(MomentBoundariesTest::isMomentSource).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = FORBIDDEN.matcher(code(lines.get(i)));
                    if (m.find()) violations.add(file.getFileName() + ":" + (i + 1) + " " + m.group());
                }
            }
        }
        assertThat(violations).as("retired concepts in the moment code").isEmpty();
    }

    @Test
    void theMomentTables_haveNoSuchColumnEither() throws IOException {
        String sql = Files.readString(V35, StandardCharsets.UTF_8);
        int from = sql.indexOf("CREATE TABLE daily_questions");
        int to = sql.indexOf("-- ---- 3.");
        assertThat(from).isPositive();
        assertThat(to).isGreaterThan(from);
        List<String> violations = new ArrayList<>();
        for (String line : sql.substring(from, to).split("\n")) {
            Matcher m = FORBIDDEN.matcher(line.replaceAll("--.*$", ""));
            if (m.find()) violations.add(line.trim() + " -> " + m.group());
        }
        assertThat(violations).as("retired concepts in the V35 moment tables").isEmpty();
        // The three tables this guard is about are really in that part of the file.
        assertThat(sql.substring(from, to)).contains("CREATE TABLE moments", "CREATE TABLE moment_media");
    }

    @Test
    void noAnswer_hasAFieldForACountALocationOrAViewer() {
        Pattern field = Pattern.compile("(?i)(count|total|number|views?$|viewer|seen|read|lat|lng|cell|place|city|"
                + "distance|online|rank|score|likes?$)");
        List<String> violations = new ArrayList<>();
        for (Class<?> dto : MomentDtos.class.getDeclaredClasses()) {
            if (!dto.isRecord()) continue;
            for (RecordComponent component : dto.getRecordComponents()) {
                if (field.matcher(component.getName()).find()) violations.add(dto.getSimpleName() + "." + component.getName());
            }
        }
        assertThat(violations).as("answer fields").isEmpty();
        assertThat(MomentDtos.class.getDeclaredClasses()).hasSizeGreaterThanOrEqualTo(8);
    }

    /** N4-B3: the old social surface is gone from the backend and no route of it is served any more (N-58). */
    @Test
    void theOldSocialSurface_isGone() throws IOException {
        Pattern oldFile = Pattern.compile("^(Post|Comment|Place|Discover|City|Cities|Mp4|TimeBands|EditionPolicy).*\\.java$");
        Pattern oldRoute = Pattern.compile("\"/api/(posts|places|discover|media|comments|explore)\\b");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (oldFile.matcher(name).find()) violations.add(name);
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) continue;
                    if (oldRoute.matcher(line).find()) violations.add(name + ":" + (i + 1));
                }
            }
        }
        assertThat(violations).as("old social files or routes").isEmpty();
    }

    private static boolean isMomentSource(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".java") && (name.startsWith("Moment") || name.startsWith("DailyQuestion"));
    }

    /** The line without its comment (the rules above are explained in comments, in words). */
    private static String code(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return "";
        int comment = line.indexOf("//");
        String code = comment < 0 ? line : line.substring(0, comment);
        // Words shown to people ("Only a Who's in? has a time and a place") are not fields.
        return code.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
    }
}
