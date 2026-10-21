-- Phase 4B: the Escalation Ladder's history. One row per change of level, for a journey and/or an emergency.
--   0 back to normal (the person checked in)   1 check-in missed   2 no response   3 emergency (confirmed SOS)
--   4 emergency pathway (Phase 4D, user-tapped dial prompt)   5 Verified Nearby Assistance: reserved, never stored.
-- "At least one of journey_id / emergency_id" is enforced by the service: MySQL does not allow a CHECK constraint
-- on columns that have an ON DELETE CASCADE foreign key.
CREATE TABLE escalation_events (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    journey_id   BIGINT      NULL,
    emergency_id BIGINT      NULL,
    level        TINYINT     NOT NULL,
    reason       VARCHAR(30) NOT NULL,                    -- CHECKED_IN, CHECK_IN_MISSED, NO_RESPONSE, SOS_CONFIRMED
    created_at   DATETIME(6) NOT NULL,                    -- UTC, server time

    PRIMARY KEY (id),
    CONSTRAINT fk_escalation_events_journey FOREIGN KEY (journey_id) REFERENCES safe_journeys (id) ON DELETE CASCADE,
    CONSTRAINT fk_escalation_events_emergency FOREIGN KEY (emergency_id) REFERENCES emergency_events (id) ON DELETE CASCADE,
    INDEX idx_escalation_events_journey_time (journey_id, created_at),
    INDEX idx_escalation_events_emergency_time (emergency_id, created_at),
    CONSTRAINT ck_escalation_events_level CHECK (level BETWEEN 0 AND 4),
    CONSTRAINT ck_escalation_events_reason CHECK (reason IN ('CHECKED_IN', 'CHECK_IN_MISSED', 'NO_RESPONSE', 'SOS_CONFIRMED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- An emergency started during a Safe Walk points to that walk (NULL for a plain SOS).
ALTER TABLE emergency_events
    ADD COLUMN journey_id BIGINT NULL AFTER trigger_type,
    ADD CONSTRAINT fk_emergency_events_journey FOREIGN KEY (journey_id) REFERENCES safe_journeys (id) ON DELETE SET NULL;
