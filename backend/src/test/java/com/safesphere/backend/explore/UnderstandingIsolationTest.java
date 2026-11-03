package com.safesphere.backend.explore;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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
 * Phase 17G-3 invariants of the optional understanding (comments are ignored: they may name what the code must not do).
 * It is a small island: it reads only the closed lists it must answer with, stores nothing, schedules nothing, makes exactly
 * one kind of outbound call (to our own internal service), holds no provider key, and no other area depends on it.
 */
class UnderstandingIsolationTest {

    private static final Path BACKEND = Path.of("src", "main", "java", "com", "safesphere", "backend");
    private static final Path EXPLORE = BACKEND.resolve("explore");

    /** The only backend classes outside the island that it may import: closed lists and plumbing. */
    private static final List<String> ALLOWED_IMPORTS = List.of(
            "com.safesphere.backend.explore.", "com.safesphere.backend.config.ExploreUnderstandingProperties",
            "com.safesphere.backend.common.exception.", "com.safesphere.backend.security.CurrentUser",
            "com.safesphere.backend.social.policy.ExploreFacets", "com.safesphere.backend.social.policy.ProfileLists",
            "com.safesphere.backend.social.entity.PostEnums");

    @Test
    void theIsland_importsOnlyClosedListsAndPlumbing() throws IOException {
        Pattern importLine = Pattern.compile("^import\\s+(com\\.safesphere\\.backend\\.[\\w.]+);", Pattern.MULTILINE);
        List<String> offending = new ArrayList<>();
        for (Path file : javaFiles(EXPLORE)) {
            Matcher matcher = importLine.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (matcher.find()) {
                String imported = matcher.group(1);
                if (ALLOWED_IMPORTS.stream().noneMatch(imported::startsWith)) offending.add(file.getFileName() + " -> " + imported);
            }
        }
        assertThat(offending).as("no safety, community, intelligence, evaluation, moderation, social data or service").isEmpty();
    }

    @Test
    void noOtherAreaDependsOnTheIsland() throws IOException {
        List<String> users = new ArrayList<>();
        for (Path file : javaFiles(BACKEND)) {
            if (file.startsWith(EXPLORE)) continue;
            if (code(file).contains("com.safesphere.backend.explore")) users.add(BACKEND.relativize(file).toString());
        }
        assertThat(users).as("social, safety and the rest keep working with the island removed").isEmpty();
    }

    @Test
    void theIsland_storesNothing_andSchedulesNothing() throws IOException {
        Pattern persistence = Pattern.compile("@Entity|@Table|JpaRepository|CrudRepository|JdbcTemplate|EntityManager|@Scheduled|@Async|@EventListener");
        for (Path file : javaFiles(EXPLORE)) {
            assertThat(persistence.matcher(code(file)).find()).as(file.getFileName() + " must store and schedule nothing").isFalse();
        }
    }

    @Test
    void onlyTheClient_callsOut_andNoFileHoldsAProviderKeyOrAddress() throws IOException {
        Pattern outbound = Pattern.compile("HttpClient|RestTemplate|WebClient|java\\.net\\.http");
        Pattern provider = Pattern.compile("(?i)anthropic|groq|gsk_|x-api-key|api[-_ ]?key|sk-ant|IRL_UNDERSTANDING");
        for (Path file : javaFiles(EXPLORE)) {
            String code = code(file);
            boolean client = file.getFileName().toString().equals("UnderstandingClient.java");
            assertThat(outbound.matcher(code).find()).as(file.getFileName() + " outbound call").isEqualTo(client);
            assertThat(provider.matcher(code).find()).as(file.getFileName() + " must not name the provider or a key").isFalse();
        }
    }

    @Test
    void theClient_sendsOnlyTheText_toOnlyTheInternalRoute() throws IOException {
        String code = code(EXPLORE.resolve("UnderstandingClient.java"));
        assertThat(code).contains("Map.of(\"text\", text)").contains("\"/v1/understand\"").contains("\"/v1/health\"");
        assertThat(code).doesNotContain("userId").doesNotContain("Jwt").doesNotContain("placeId").doesNotContain("city");
    }

    @Test
    void thePropertiesNeverPrintTheKey() throws IOException {
        String code = code(BACKEND.resolve(Path.of("config", "ExploreUnderstandingProperties.java")));
        String toString = code.substring(code.indexOf("public String toString"));
        assertThat(toString).doesNotContain("key");
    }

    @Test
    void theModeIsOffByDefault() throws IOException {
        String properties = Files.readString(Path.of("src", "main", "resources", "application.properties"), StandardCharsets.UTF_8);
        assertThat(properties).contains("app.explore.understanding.mode=${EXPLORE_UNDERSTANDING_MODE:OFF}");
        assertThat(properties).contains("app.explore.understanding.user-daily-cap=20").contains("global-daily-cap=300");
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
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
