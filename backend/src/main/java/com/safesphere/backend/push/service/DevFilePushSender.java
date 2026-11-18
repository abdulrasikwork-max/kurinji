package com.safesphere.backend.push.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Phase N7, development only (PUSH_PROVIDER=DEV_FILE, the default): nothing is sent to any phone. Each push is appended to
 * a file on the server's own computer (the kind, the subject id, the event id, the priority and lifetime; never the
 * token), so a developer can see what would have been sent. Not a log. The prod profile refuses it (ProductionGuard).
 */
public class DevFilePushSender implements PushSender {

    private final Path file;
    private final Clock clock;

    public DevFilePushSender(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    @Override
    public synchronized Outcome send(Push push) {
        String line = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS) + "  " + push.type() + "  s=" + push.subject()
                + "  m=" + push.eventId() + "  " + (push.highPriority() ? "HIGH" : "NORMAL") + "  ttl=" + push.ttlSeconds()
                + "s  collapse=" + push.collapseKey() + System.lineSeparator();
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            return Outcome.of(Result.RETRY, "dev-file");
        }
        return Outcome.accepted();
    }

    @Override
    public String describe() {
        return "DEV_FILE (nothing is sent to phones; pushes are written to " + file.toAbsolutePath() + ")";
    }
}
