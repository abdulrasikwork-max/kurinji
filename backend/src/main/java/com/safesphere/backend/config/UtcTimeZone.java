package com.safesphere.backend.config;

import java.util.TimeZone;

/**
 * Makes UTC the JVM's default time zone.
 *
 * <p>Why this matters: our entities hold UTC wall-clock values in {@code LocalDateTime} fields. Hibernate hands
 * them to JDBC as {@code java.sql.Timestamp}, which is built in the JVM's default zone. If that zone is not UTC
 * (for example India Standard Time on a developer PC) the MySQL driver converts the value a second time and the
 * stored {@code DATETIME} ends up shifted (5 h 30 m behind UTC for IST). Reading it back reverses the shift, so
 * the API looked correct while the raw table values were wrong. With a UTC default zone no shift can happen.
 * See docs/TIMESTAMPS.md.
 */
public final class UtcTimeZone {

    private UtcTimeZone() {
    }

    /** Call once, before Spring (and so before any database connection) starts. */
    public static void install() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }
}
