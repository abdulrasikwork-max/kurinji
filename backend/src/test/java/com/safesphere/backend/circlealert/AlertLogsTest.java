package com.safesphere.backend.circlealert;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * N6 (docs/PHASE_N6.md §3.3): nothing about the automatic Safety Circle texts logs a number, a text or a location. Every
 * log call in the alert code and the SMS adapters may carry only counts, a provider status or an exception class.
 */
class AlertLogsTest {

    private static final List<Path> ROOTS = List.of(
            Path.of("src", "main", "java", "com", "safesphere", "backend", "circlealert"),
            Path.of("src", "main", "java", "com", "safesphere", "backend", "common", "sms"));
    private static final Pattern LOG = Pattern.compile("log\\.(warn|info|debug|error|trace)\\(([^;]*);", Pattern.DOTALL);
    private static final Pattern FORBIDDEN = Pattern.compile(
            "phone|number|text\\(|\\.text|(?<!get)message\\b|getName|latitude|longitude|position|recipient|key\\b"
                    + "|getContactId",
            Pattern.CASE_INSENSITIVE);

    @Test
    void alertLogs_carryNoNumberTextOrLocation() throws IOException {
        int seen = 0;
        for (Path root : ROOTS) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    Matcher logs = LOG.matcher(Files.readString(file));
                    while (logs.find()) {
                        seen++;
                        String args = logs.group(2);
                        int comma = args.indexOf("\",");
                        String values = comma < 0 ? "" : args.substring(comma + 2);
                        assertThat(values).as(file.getFileName() + ": " + args).doesNotContainPattern(FORBIDDEN);
                    }
                }
            }
        }
        assertThat(seen).as("the dispatcher does log, by count and kind").isPositive();
    }

    @Test
    void theSmsAdapters_neverLog() throws IOException {
        for (String name : List.of("Fast2SmsClient.java", "DevFileSmsGateway.java", "SmsGateway.java")) {
            String source = Files.readString(ROOTS.get(1).resolve(name));
            assertThat(source).as(name).doesNotContain("Logger").doesNotContain("log.").doesNotContain("System.out");
        }
    }
}
