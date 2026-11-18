package com.safesphere.backend.circlealert.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * N6 (D20, V37 "alert_stops"): a number that asked to receive no IRL alerts, kept ONLY as an HMAC (key ALERT_SECRET,
 * outside the database). Added and lifted by an admin; nobody can read a number back from it.
 */
@Entity
@Table(name = "alert_stops")
public class AlertStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "number_key", nullable = false, updatable = false, length = 64, unique = true)
    private String numberKey;

    @Column(name = "added_by")
    private Long addedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Required by JPA. */
    protected AlertStop() {
    }

    public AlertStop(String numberKey, Long addedBy, LocalDateTime now) {
        this.numberKey = numberKey;
        this.addedBy = addedBy;
        this.createdAt = now;
    }

    public String getNumberKey() {
        return numberKey;
    }
}
