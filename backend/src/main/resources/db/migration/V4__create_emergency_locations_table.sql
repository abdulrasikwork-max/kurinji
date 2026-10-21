-- Phase 4A: location points recorded by the phone during an emergency.
-- There is deliberately NO user_id: ownership always goes through the emergency (emergency_events.user_id),
-- so a location can never belong to a different person than its emergency.
-- Privacy: points are only accepted while the emergency is ACTIVE (plus a short upload grace period) and are
-- deleted together with the emergency (and therefore with the user account).
CREATE TABLE emergency_locations (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    emergency_id    BIGINT        NOT NULL,
    latitude        DECIMAL(9, 6) NOT NULL,           -- degrees, -90 .. 90 (6 decimals is about 0.1 m)
    longitude       DECIMAL(9, 6) NOT NULL,           -- degrees, -180 .. 180
    accuracy_meters DECIMAL(7, 1) NULL,               -- radius reported by the phone; NULL when unknown
    source          VARCHAR(20)   NOT NULL,           -- FUSED, LAST_KNOWN, GPS, NETWORK, UNKNOWN
    battery_percent TINYINT       NULL,               -- 0 .. 100; NULL when the phone cannot tell
    recorded_at     DATETIME(6)   NOT NULL,           -- UTC, when the phone took the fix
    received_at     DATETIME(6)   NOT NULL,           -- UTC, when the server stored it

    PRIMARY KEY (id),
    CONSTRAINT fk_emergency_locations_emergency
        FOREIGN KEY (emergency_id) REFERENCES emergency_events (id) ON DELETE CASCADE,
    -- One point per emergency and timestamp: a phone that re-sends a batch after a lost response does not
    -- create duplicates. The same index serves "newest first" reads for one emergency.
    CONSTRAINT uk_emergency_locations_emergency_recorded UNIQUE (emergency_id, recorded_at),
    CONSTRAINT ck_emergency_locations_latitude CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_emergency_locations_longitude CHECK (longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_emergency_locations_accuracy CHECK (accuracy_meters IS NULL OR accuracy_meters >= 0),
    CONSTRAINT ck_emergency_locations_battery CHECK (battery_percent IS NULL OR battery_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_emergency_locations_source CHECK (source IN ('FUSED', 'LAST_KNOWN', 'GPS', 'NETWORK', 'UNKNOWN'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
