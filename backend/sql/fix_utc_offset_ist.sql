-- ONE-TIME data correction for rows written BEFORE the UTC timestamp fix (docs/TIMESTAMPS.md).
--
-- Background: while the backend ran on a PC in India (IST, UTC+05:30) without the fix, every DATETIME(6) was stored
-- 5 h 30 m behind true UTC. The fixed backend stores true UTC, so old rows must move forward by 330 minutes or they
-- would look 5 h 30 m too early (history, walk deadlines, location times).
--
-- HOW TO USE (Windows PowerShell, from the backend folder; ORDER MATTERS):
--   1. STOP the backend.
--   2. Back up:  mysqldump -u safesphere -p safesphere > backup-before-utc-fix.sql
--   3. Run ONCE: mysql -u safesphere -p safesphere < sql\fix_utc_offset_ist.sql
--   4. START the fixed backend.
-- Do NOT run it twice, and do NOT run it on a database written by a UTC machine (it would shift correct rows).
-- Fresh databases (created after the fix) need nothing.
--
-- Safety: all-or-nothing transaction; refuses (changes nothing) if the newest row already looks like true UTC,
-- which would mean the data is already corrected or was not written by the IST backend.

SET @offset_minutes = 330;                       -- IST = UTC+05:30. Change only if another zone wrote the data.

-- Newest event-style timestamp in the database (deadlines and ended_at are ignored: they may lie in the future).
SET @newest = (SELECT MAX(t) FROM (
    SELECT MAX(updated_at)  AS t FROM users
    UNION ALL SELECT MAX(updated_at)  FROM safety_contacts
    UNION ALL SELECT MAX(updated_at)  FROM emergency_events
    UNION ALL SELECT MAX(received_at) FROM emergency_locations
    UNION ALL SELECT MAX(updated_at)  FROM safe_journeys
    UNION ALL SELECT MAX(occurred_at) FROM journey_events
    UNION ALL SELECT MAX(created_at)  FROM escalation_events
    UNION ALL SELECT MAX(updated_at)  FROM circle_alerts
) newest_rows);

-- 1 = safe to shift (newest row, once shifted, is not in the future); NULL/0 = refuse.
SET @go = IF(@newest IS NULL, 1,
             IF(DATE_ADD(@newest, INTERVAL @offset_minutes MINUTE) <= DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 5 MINUTE), 1, 0));
SELECT @newest AS newest_row_before, UTC_TIMESTAMP(6) AS utc_now,
       IF(@go = 1, 'OK: shifting', 'REFUSED: data already looks like true UTC, nothing changed') AS decision;

START TRANSACTION;

UPDATE users SET
    created_at = DATE_ADD(created_at, INTERVAL @offset_minutes MINUTE),
    updated_at = DATE_ADD(updated_at, INTERVAL @offset_minutes MINUTE)
WHERE @go = 1;

UPDATE safety_contacts SET
    created_at = DATE_ADD(created_at, INTERVAL @offset_minutes MINUTE),
    updated_at = DATE_ADD(updated_at, INTERVAL @offset_minutes MINUTE)
WHERE @go = 1;

UPDATE emergency_events SET
    started_at = DATE_ADD(started_at, INTERVAL @offset_minutes MINUTE),
    ended_at   = DATE_ADD(ended_at,   INTERVAL @offset_minutes MINUTE),   -- stays NULL while ACTIVE
    created_at = DATE_ADD(created_at, INTERVAL @offset_minutes MINUTE),
    updated_at = DATE_ADD(updated_at, INTERVAL @offset_minutes MINUTE)
WHERE @go = 1;

-- Newest first: UNIQUE (emergency_id, recorded_at) must never see two equal values part-way through.
UPDATE emergency_locations SET
    recorded_at = DATE_ADD(recorded_at, INTERVAL @offset_minutes MINUTE),
    received_at = DATE_ADD(received_at, INTERVAL @offset_minutes MINUTE)
WHERE @go = 1
ORDER BY recorded_at DESC;

UPDATE safe_journeys SET
    next_check_in_due_at = DATE_ADD(next_check_in_due_at, INTERVAL @offset_minutes MINUTE),  -- NULL once ended
    started_at           = DATE_ADD(started_at,           INTERVAL @offset_minutes MINUTE),
    ended_at             = DATE_ADD(ended_at,             INTERVAL @offset_minutes MINUTE),
    last_location_at     = DATE_ADD(last_location_at,     INTERVAL @offset_minutes MINUTE),
    created_at           = DATE_ADD(created_at,           INTERVAL @offset_minutes MINUTE),
    updated_at           = DATE_ADD(updated_at,           INTERVAL @offset_minutes MINUTE)
WHERE @go = 1;

UPDATE journey_events SET
    occurred_at = DATE_ADD(occurred_at, INTERVAL @offset_minutes MINUTE)
WHERE @go = 1;

UPDATE escalation_events SET
    created_at = DATE_ADD(created_at, INTERVAL @offset_minutes MINUTE)
WHERE @go = 1;

UPDATE circle_alerts SET
    occurred_at = DATE_ADD(occurred_at, INTERVAL @offset_minutes MINUTE),
    received_at = DATE_ADD(received_at, INTERVAL @offset_minutes MINUTE),
    updated_at  = DATE_ADD(updated_at,  INTERVAL @offset_minutes MINUTE)
WHERE @go = 1;

-- Review the result; if it looks wrong, run ROLLBACK instead of COMMIT.
SELECT 'users' AS tbl, COUNT(*) AS n, MAX(created_at) AS newest FROM users
UNION ALL SELECT 'emergency_events', COUNT(*), MAX(started_at) FROM emergency_events
UNION ALL SELECT 'safe_journeys', COUNT(*), MAX(started_at) FROM safe_journeys
UNION ALL SELECT 'journey_events', COUNT(*), MAX(occurred_at) FROM journey_events
UNION ALL SELECT 'escalation_events', COUNT(*), MAX(created_at) FROM escalation_events;

COMMIT;
