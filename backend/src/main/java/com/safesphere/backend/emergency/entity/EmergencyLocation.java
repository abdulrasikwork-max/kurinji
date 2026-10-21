package com.safesphere.backend.emergency.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One location point of an emergency. Maps to "emergency_locations" (migration V4). Timestamps hold UTC.
 * Rows are never updated: a point is written once and removed only together with its emergency.
 */
@Entity
@Table(name = "emergency_locations")
public class EmergencyLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "emergency_id", nullable = false, updatable = false)
    private Long emergencyId;

    @Column(nullable = false, updatable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(nullable = false, updatable = false, precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "accuracy_meters", updatable = false, precision = 7, scale = 1)
    private BigDecimal accuracyMeters;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private LocationSource source;

    @Column(name = "battery_percent", updatable = false)
    private Integer batteryPercent;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private LocalDateTime recordedAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    /** Required by JPA. */
    protected EmergencyLocation() {
    }

    public EmergencyLocation(Long emergencyId, BigDecimal latitude, BigDecimal longitude, BigDecimal accuracyMeters,
                             LocationSource source, Integer batteryPercent, LocalDateTime recordedAt,
                             LocalDateTime receivedAt) {
        this.emergencyId = emergencyId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracyMeters = accuracyMeters;
        this.source = source;
        this.batteryPercent = batteryPercent;
        this.recordedAt = recordedAt;
        this.receivedAt = receivedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getEmergencyId() {
        return emergencyId;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public BigDecimal getAccuracyMeters() {
        return accuracyMeters;
    }

    public LocationSource getSource() {
        return source;
    }

    public Integer getBatteryPercent() {
        return batteryPercent;
    }

    public LocalDateTime getRecordedAt() {
        return recordedAt;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    /** Coordinates are deliberately left out so a stray log line can never contain a precise location. */
    @Override
    public String toString() {
        return "EmergencyLocation{id=" + id + ", emergencyId=" + emergencyId + ", location=<redacted>}";
    }
}
