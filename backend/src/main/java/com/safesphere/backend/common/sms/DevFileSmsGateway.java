package com.safesphere.backend.common.sms;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Phase N6, development only (ALERT_PROVIDER=DEV_FILE, the default): nothing is sent to any phone. Each alert text is
 * appended to a file on the server's own computer, with only the last 4 digits of the number, so a developer can see
 * what would have been sent. Not a log: application logs never contain an alert text. The prod profile refuses it
 * (ProductionGuard).
 */
public class DevFileSmsGateway implements SmsGateway {

    private final Path file;
    private final Clock clock;

    public DevFileSmsGateway(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    @Override
    public synchronized String send(String phoneE164, String text) {
        String last4 = phoneE164 == null || phoneE164.length() < 4 ? "????" : phoneE164.substring(phoneE164.length() - 4);
        String reference = "dev-" + UUID.randomUUID();
        String line = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS) + "  ******" + last4 + "  " + reference + "  "
                + text.replace('\n', ' ') + System.lineSeparator();
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new SmsRefusedException("dev file not writable (" + e.getClass().getSimpleName() + ")");
        }
        return reference;
    }

    @Override
    public String describe() {
        return "DEV_FILE (nothing is sent to phones; alert texts are written to " + file.toAbsolutePath() + ")";
    }
}
