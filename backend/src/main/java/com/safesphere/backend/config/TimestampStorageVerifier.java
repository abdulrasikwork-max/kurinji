package com.safesphere.backend.config;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Start-up self-check: a UTC {@code LocalDateTime} must reach the database with the same wall-clock digits.
 *
 * <p>It sends a known value through the same Hibernate binding path the entities use and reads back the text the
 * database received. If a wrong JVM time zone, JDBC URL or driver change shifts it, the backend refuses to start
 * instead of silently writing wrong timestamps (evidence and emergency history must be trustworthy). It reads
 * only: no table is touched.
 */
@Component
public class TimestampStorageVerifier implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TimestampStorageVerifier.class);
    private static final LocalDateTime PROBE = LocalDateTime.of(2026, 1, 15, 12, 34, 56, 123_456_000);
    private static final DateTimeFormatter AS_STORED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${app.time.verify-storage:true}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.warn("Timestamp storage check is switched off (app.time.verify-storage=false)");
            return;
        }
        String expected = PROBE.format(AS_STORED);
        Object received = entityManager
                .createNativeQuery("SELECT CONCAT(:probe, '')")
                .setParameter("probe", PROBE)
                .getSingleResult();
        if (!expected.equals(String.valueOf(received))) {
            throw new IllegalStateException("Timestamp storage check failed: a UTC value " + expected
                    + " reached the database as " + received + ". Check the JVM time zone and the JDBC URL "
                    + "(connectionTimeZone=UTC). Refusing to start so no shifted timestamps are stored.");
        }
        log.info("Timestamp storage check passed (UTC values are stored unchanged)");
    }
}
