package com.safesphere.backend.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.account.service.AccountEraser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Phase 10A.3 drift guard: every foreign key to users(id) in the migrations is handled by account deletion, either
 * deleted with the person or (audit trail of decisions) kept on an anonymised row. A new table that refers to a person
 * fails this test until AccountEraser deals with it.
 */
class AccountEraserCoverageTest {

    private static final Pattern TABLE = Pattern.compile("(?i)^\\s*(CREATE|ALTER)\\s+TABLE\\s+(\\w+)");
    private static final Pattern USER_FK = Pattern.compile("(?i)FOREIGN\\s+KEY\\s*\\((\\w+)\\)\\s*REFERENCES\\s+users\\s*\\(");

    @Test
    void everyReferenceToAUserIsDeletedOrDeliberatelyKept() throws IOException {
        Set<String> found = new HashSet<>();
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".sql")).sorted().toList()) {
                String table = null;
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    Matcher t = TABLE.matcher(line);
                    if (t.find()) table = t.group(2).toLowerCase();
                    Matcher fk = USER_FK.matcher(line);
                    if (fk.find()) found.add(table + "." + fk.group(1).toLowerCase());
                }
            }
        }
        assertThat(found).as("references to users found in the migrations").hasSizeGreaterThanOrEqualTo(13);
        Set<String> handled = new HashSet<>(AccountEraser.DELETED_REFERENCES);
        handled.addAll(AccountEraser.KEPT_REFERENCES);
        assertThat(handled).containsExactlyInAnyOrderElementsOf(found);
        assertThat(AccountEraser.DELETED_REFERENCES).doesNotContainAnyElementsOf(AccountEraser.KEPT_REFERENCES);
        assertThat(List.copyOf(AccountEraser.KEPT_REFERENCES))
                .containsExactlyInAnyOrder("moderation_actions.actor_id", "moderation_appeals.decided_by");
    }
}
