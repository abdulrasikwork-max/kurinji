package com.safesphere.backend.push.service;

import com.safesphere.backend.config.PushProperties;
import com.safesphere.backend.push.entity.PushType;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * N7 (D38 = N-130): "Things are waiting" for staff who switched it on (push_settings.staff). Moderators and admins: an
 * open content case; admins also: an ID photo waiting for a decision. At most once an hour per person, and only when
 * something NEW arrived since the last one. Read-only counts of other packages' tables; nothing about a case or a
 * person goes into the push (subject "staff").
 */
@Service
public class StaffWaitingJob {

    private static final Logger log = LoggerFactory.getLogger(StaffWaitingJob.class);
    static final Duration AT_MOST_EVERY = Duration.ofHours(1);

    private final PushService push;
    private final JdbcTemplate jdbc;
    private final PushProperties properties;
    private final Clock clock;

    public StaffWaitingJob(PushService push, JdbcTemplate jdbc, PushProperties properties, Clock clock) {
        this.push = push;
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.push.staff-interval:PT10M}", initialDelayString = "PT5M")
    public void scheduled() {
        if (!properties.jobEnabled()) return;
        try {
            run();
        } catch (RuntimeException e) {
            log.warn("The staff check stopped ({}); the next run tries again", e.getClass().getSimpleName());
        }
    }

    /** Returns how many staff were told. */
    public int run() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDateTime newestCase = newest("SELECT MAX(opened_at) FROM content_cases WHERE status = 'OPEN'");
        LocalDateTime newestPhoto = newest("SELECT MAX(photo_uploaded_at) FROM verification_requests"
                + " WHERE photo_uploaded_at IS NOT NULL");
        if (newestCase == null && newestPhoto == null) return 0;
        List<Map<String, Object>> staff = jdbc.queryForList("SELECT u.id, u.role FROM users u JOIN push_settings s"
                + " ON s.user_id = u.id WHERE s.staff = TRUE AND u.role IN ('ADMIN', 'MODERATOR') AND u.enabled = TRUE");
        int told = 0;
        for (Map<String, Object> row : staff) {
            long id = ((Number) row.get("id")).longValue();
            boolean admin = "ADMIN".equals(row.get("role"));
            LocalDateTime newest = admin ? later(newestCase, newestPhoto) : newestCase;
            if (newest == null) continue;
            LocalDateTime last = lastTold(id);
            if (last != null && (!newest.isAfter(last) || last.isAfter(now.minus(AT_MOST_EVERY)))) continue;
            try {
                if (push.notify(id, PushType.STAFF_WAITING, "staff")) told++;
            } catch (RuntimeException e) {
                log.warn("A staff push could not be queued ({})", e.getClass().getSimpleName());
            }
        }
        return told;
    }

    private LocalDateTime lastTold(long userId) {
        Timestamp value = jdbc.queryForObject("SELECT MAX(created_at) FROM push_messages WHERE user_id = ?"
                + " AND type = 'STAFF_WAITING' AND status IN ('QUEUED', 'SENT')", Timestamp.class, userId);
        return value == null ? null : value.toLocalDateTime();
    }

    private LocalDateTime newest(String sql) {
        Timestamp value = jdbc.queryForObject(sql, Timestamp.class);
        return value == null ? null : value.toLocalDateTime();
    }

    private static LocalDateTime later(LocalDateTime a, LocalDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }
}
