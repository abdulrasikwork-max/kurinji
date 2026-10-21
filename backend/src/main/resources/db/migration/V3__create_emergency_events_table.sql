-- Phase 3: the record of each manual SOS session. Nothing is sent to anyone yet; this is only the history.
CREATE TABLE emergency_events (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    user_id      BIGINT      NOT NULL,
    status       VARCHAR(20) NOT NULL,             -- ACTIVE, CANCELLED, RESOLVED
    trigger_type VARCHAR(20) NOT NULL,             -- MANUAL now; SHAKE, VOICE, DURESS_PIN reserved for later phases
    started_at   DATETIME(6) NOT NULL,             -- UTC
    ended_at     DATETIME(6) NULL,                 -- UTC; NULL while ACTIVE
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,

    -- At most ONE active emergency per user (double taps, two phones...): 1 while ACTIVE, NULL otherwise.
    active_marker TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN 1 ELSE NULL END) STORED,

    PRIMARY KEY (id),
    CONSTRAINT uk_emergency_events_one_active UNIQUE (user_id, active_marker),
    CONSTRAINT fk_emergency_events_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    -- History is read newest-first per user.
    INDEX idx_emergency_events_user_started (user_id, started_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
