package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.social.dto.ConversationDtos;
import com.safesphere.backend.social.dto.MomentDtos;
import com.safesphere.backend.social.dto.RepliesDtos;
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
 * Phase N5 (N-58, N-88, N-93): the response code and the V36 tables never bring back what IRL refuses: location,
 * presence, who read or saw anything, typing, reactions, counts, ranking. A dot in Replies is the person's own "last
 * opened" time, never a read receipt.
 */
class ResponseBoundariesTest {

    private static final Path MAIN = Path.of("src", "main", "java", "com", "safesphere", "backend");
    private static final Path V36 = Path.of("src", "main", "resources", "db", "migration", "V36__responses_and_plans.sql");

    private static final Pattern FORBIDDEN = Pattern.compile("(?i)(latitude|longitude|\\blat\\b|\\blng\\b|geohash"
            + "|\\bcell\\b|distance|nearby|proximity|\\bcity\\b|last_?seen|\\bonline\\b|presence|typing"
            + "|view_?count|viewed_?by|seen_?by|seen_?at|read_?at|read_?receipt|delivered|is_?read|\\bunread"
            + "|like_?count|reaction|upvote|follower|\\brank|ranking|\\bscore\\b|trending|popular|leaderboard"
            + "|voice|audio|video)");

    @Test
    void theResponseCode_hasNoPresenceReadingReactionCountOrRanking() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(ResponseBoundariesTest::isResponseSource).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = FORBIDDEN.matcher(code(lines.get(i)));
                    if (m.find()) violations.add(file.getFileName() + ":" + (i + 1) + " " + m.group());
                }
            }
        }
        assertThat(violations).as("retired concepts in the response code").isEmpty();
    }

    @Test
    void theV36Tables_haveNoSuchColumn() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String line : Files.readAllLines(V36, StandardCharsets.UTF_8)) {
            Matcher m = FORBIDDEN.matcher(line.replaceAll("--.*$", ""));
            if (m.find()) violations.add(line.trim() + " -> " + m.group());
        }
        assertThat(violations).as("retired concepts in V36").isEmpty();
    }

    @Test
    void noResponseAnswer_hasAFieldForACountOrForReading() {
        Pattern field = Pattern.compile("(?i)(count|total|number|views?$|viewer|seen|read|online|rank|score|likes?$)");
        List<String> violations = new ArrayList<>();
        List<Class<?>> all = new ArrayList<>();
        for (Class<?> holder : List.of(MomentDtos.class, ConversationDtos.class, RepliesDtos.class)) {
            all.addAll(List.of(holder.getDeclaredClasses()));
        }
        for (Class<?> dto : all) {
            if (!dto.isRecord()) continue;
            for (RecordComponent component : dto.getRecordComponents()) {
                if (field.matcher(component.getName()).find()) violations.add(dto.getSimpleName() + "." + component.getName());
            }
        }
        assertThat(violations).as("answer fields").isEmpty();
    }

    private static boolean isResponseSource(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".java") && (name.startsWith("Response") || name.startsWith("Plan")
                || name.startsWith("Conversation") || name.startsWith("Message") || name.startsWith("Replies")
                || name.startsWith("SocialSettings") || name.startsWith("People") || name.startsWith("MomentSignal"));
    }

    /** The line without its comment (the rules are explained in comments, in words). */
    private static String code(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return "";
        int comment = line.indexOf("//");
        return comment < 0 ? line : line.substring(0, comment);
    }
}
