-- Phase 10A.4: who may end an ACTIVE emergency (docs/PHASE10.md §12).
-- Design rules:
--   * started_session = the random id of the sign-in (token "sid") that started the SOS. It is kept ONLY while the
--     emergency is ACTIVE and cleared when it ends (data minimisation; the CHECK enforces it). Ending the SOS from any
--     OTHER sign-in needs the account password: someone who signs in elsewhere cannot silently end a person's SOS.
--   * ended_elsewhere records that it WAS ended from another sign-in (with the password), so the first phone and the
--     history can say so. Only possible for an ended emergency.
--   * No device id, no IP, no name: the session id is random and means nothing outside this row.
ALTER TABLE emergency_events
    ADD COLUMN started_session CHAR(36) COLLATE utf8mb4_bin NULL,
    ADD COLUMN ended_elsewhere BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT ck_emergency_events_session_format CHECK (started_session IS NULL
        OR REGEXP_LIKE(started_session, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    ADD CONSTRAINT ck_emergency_events_session_active CHECK (status = 'ACTIVE' OR started_session IS NULL),
    ADD CONSTRAINT ck_emergency_events_ended_elsewhere CHECK (ended_elsewhere = FALSE OR status <> 'ACTIVE');
