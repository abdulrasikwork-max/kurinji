package com.safesphere.backend.auth.phone;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Phase 17B, development only (OTP_PROVIDER=DEV_FILE, the default): nothing is sent to any phone. Each code is appended
 * to a file on the server's own computer, with only the last 4 digits of the number, so a developer can type it in. The
 * start-up log says where (never the codes). Not a log: application logs never contain a code.
 */
public class DevFileOtpSender implements OtpSender {

    private final Path file;
    private final Clock clock;

    public DevFileOtpSender(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    @Override
    public synchronized void send(String phoneE164, String code) {
        String line = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS) + "  " + PhoneNumbers.hint(phoneE164)
                + "  code " + code + System.lineSeparator();
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new OtpDeliveryException("dev file not writable (" + e.getClass().getSimpleName() + ")");
        }
    }

    @Override
    public String describe() {
        return "DEV_FILE (nothing is sent to phones; codes are written to " + file.toAbsolutePath() + ")";
    }
}
