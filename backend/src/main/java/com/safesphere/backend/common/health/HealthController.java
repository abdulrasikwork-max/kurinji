package com.safesphere.backend.common.health;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase D12: a liveness-and-database check for an uptime monitor and the deploy smoke test. Public (GET only), no new
 * dependency (no Actuator), and it says as little as possible: {"status":"UP"} when the database answers, 503
 * {"status":"DOWN"} when it does not. No version, no exception text, no time, no counts, nothing about any person.
 */
@RestController
public class HealthController {

    /** How long the database may take to answer the ping, in seconds. */
    static final int PING_SECONDS = 2;

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, String>> health() {
        boolean up;
        try (Connection connection = dataSource.getConnection()) {
            up = connection.isValid(PING_SECONDS);
        } catch (SQLException | RuntimeException e) {
            up = false; // the reason is deliberately not returned and not logged here
        }
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("status", up ? "UP" : "DOWN"));
    }
}
