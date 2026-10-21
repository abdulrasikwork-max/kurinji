package com.safesphere.backend.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.safesphere.backend.config.CoordinationProperties;
import com.safesphere.backend.config.CoordinationProperties.Mode;
import com.safesphere.backend.moderation.service.CoordinationCheck;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Phase 9C safety rule, enforced on the source (docs/PHASE9_DESIGN.md Appendix A): the coordination check can do
 * exactly one thing, open a review case for a human. It never hides content, never touches a person's standing,
 * never changes or deletes a signal, and never calls out. OFF means nothing at all happens.
 */
class CoordinationIsolationTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "com", "safesphere", "backend", "moderation",
            "service", "CoordinationCheck.java");

    @Test
    void theOnlyWriteIsOpeningAReviewCase() throws IOException {
        String source = Files.readString(SOURCE);
        Matcher saves = Pattern.compile("\\.(save|saveAndFlush|saveAll|delete\\w*)\\(").matcher(source);
        int writes = 0;
        while (saves.find()) writes++;
        assertEquals(1, writes, "one write: cases.saveAndFlush(new ModerationCase(...))");
        assertTrue(source.contains("cases.saveAndFlush(new ModerationCase("));
        for (String forbidden : new String[] {"quarantine(", "restrict", "standingFor", "markReachedConfirmed",
                "signals.", "contributions.", "flags.", "RestTemplate", "WebClient", "HttpClient", "@Scheduled"}) {
            assertFalse(source.contains(forbidden), "coordination check must not use: " + forbidden);
        }
        // Phase 9B: besides the case, it only COUNTS (weekly, system-wide; OutcomeRecorder takes no place or person).
        Matcher counts = Pattern.compile("outcomes\\.(\\w+)\\(").matcher(source);
        while (counts.find()) {
            assertTrue(counts.group(1).equals("coordinationShadow") || counts.group(1).equals("caseOpened"),
                    "unexpected use of the recorder: " + counts.group(1));
        }
    }

    @Test
    void offMeansNothingHappens() {
        CoordinationCheck off = new CoordinationCheck(null, null, null, new CoordinationProperties(Mode.OFF, null, null),
                null);
        LocalDateTime now = LocalDateTime.of(2026, 10, 21, 12, 0);
        assertEquals(new CoordinationCheck.Run(0, 0), off.run(now), "no database touched (all dependencies are null)");
        assertTrue(off.groupOf(1L, now).isEmpty());
    }

    @Test
    void theShippedDefaultIsShadow() {
        assertEquals(Mode.SHADOW, new CoordinationProperties(null, null, null).mode());
    }
}
