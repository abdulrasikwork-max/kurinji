-- Phase 4B: Safe Walk. A journey is a period during which the person promises to check in regularly.
-- The SERVER computes every check-in deadline (phone clocks drift); the phone runs the timer and the
-- escalation policy. Privacy: only the LAST known location is kept (overwritten), never a track.
CREATE TABLE safe_journeys (
    id                        BIGINT        NOT NULL AUTO_INCREMENT,
    user_id                   BIGINT        NOT NULL,
    status                    VARCHAR(20)   NOT NULL,          -- ACTIVE, COMPLETED, CANCELLED, EMERGENCY
    destination_label         VARCHAR(100)  NULL,              -- free text chosen by the person, optional
    check_in_interval_minutes INT           NOT NULL,          -- 2 .. 60
    escalation_level          TINYINT       NOT NULL DEFAULT 0, -- 0 normal, 1 check-in missed, 2 no response, 3 emergency
    next_check_in_due_at      DATETIME(6)   NULL,              -- UTC; NULL once the journey has ended
    started_at                DATETIME(6)   NOT NULL,          -- UTC
    ended_at                  DATETIME(6)   NULL,              -- UTC
    last_latitude             DECIMAL(9, 6) NULL,
    last_longitude            DECIMAL(9, 6) NULL,
    last_accuracy_meters      DECIMAL(7, 1) NULL,
    last_location_at          DATETIME(6)   NULL,              -- UTC, when the phone took that fix
    created_at                DATETIME(6)   NOT NULL,
    updated_at                DATETIME(6)   NOT NULL,

    -- At most ONE active journey per user: 1 while ACTIVE, NULL otherwise (same technique as V2/V3).
    active_marker TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN 1 ELSE NULL END) STORED,

    PRIMARY KEY (id),
    CONSTRAINT uk_safe_journeys_one_active UNIQUE (user_id, active_marker),
    CONSTRAINT fk_safe_journeys_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX idx_safe_journeys_user_started (user_id, started_at),
    CONSTRAINT ck_safe_journeys_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED', 'EMERGENCY')),
    CONSTRAINT ck_safe_journeys_interval CHECK (check_in_interval_minutes BETWEEN 2 AND 60),
    CONSTRAINT ck_safe_journeys_escalation CHECK (escalation_level BETWEEN 0 AND 3),
    -- An active journey always has a deadline; an ended one never does.
    CONSTRAINT ck_safe_journeys_deadline CHECK ((status = 'ACTIVE') = (next_check_in_due_at IS NOT NULL)),
    CONSTRAINT ck_safe_journeys_ended CHECK ((status = 'ACTIVE') = (ended_at IS NULL)),
    CONSTRAINT ck_safe_journeys_latitude CHECK (last_latitude IS NULL OR last_latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_safe_journeys_longitude CHECK (last_longitude IS NULL OR last_longitude BETWEEN -180 AND 180),
    -- Latitude, longitude and time are stored together or not at all.
    CONSTRAINT ck_safe_journeys_location_complete CHECK (
        (last_latitude IS NULL AND last_longitude IS NULL AND last_location_at IS NULL)
        OR (last_latitude IS NOT NULL AND last_longitude IS NOT NULL AND last_location_at IS NOT NULL))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- What happened during a journey (check-ins are journey events). Escalations live in escalation_events (V6).
CREATE TABLE journey_events (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    journey_id  BIGINT      NOT NULL,
    type        VARCHAR(20) NOT NULL,                     -- STARTED, CHECKED_IN, COMPLETED, CANCELLED, EMERGENCY
    occurred_at DATETIME(6) NOT NULL,                     -- UTC, server time

    PRIMARY KEY (id),
    CONSTRAINT fk_journey_events_journey FOREIGN KEY (journey_id) REFERENCES safe_journeys (id) ON DELETE CASCADE,
    INDEX idx_journey_events_journey_time (journey_id, occurred_at),
    CONSTRAINT ck_journey_events_type CHECK (type IN ('STARTED', 'CHECKED_IN', 'COMPLETED', 'CANCELLED', 'EMERGENCY'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
