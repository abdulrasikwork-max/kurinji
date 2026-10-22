-- Phase 11: an SOS that starts while the phone cannot reach the server (docs/PHASE11.md).
-- Design rules:
--   * The phone starts the SOS by itself (Safety Circle SMS and location work without internet) and records it here
--     once the server can be reached. client_ref = a random UUID made by the phone for that one SOS: re-sending the
--     same request (a lost answer, a retry after hours offline) returns the same row instead of a second emergency.
--     Unique per person, so one account can never learn anything about another account's references.
--   * started_offline = the start (and possibly the end) time came from the PHONE, because the SOS began before the
--     server heard of it. The server clamps such times (never in the future, at most 24 h back, end >= start), and the
--     history says the times are the phone's.
--   * No device id, no place, no text: the reference is random and means nothing outside this row.
ALTER TABLE emergency_events
    ADD COLUMN client_ref CHAR(36) COLLATE utf8mb4_bin NULL,
    ADD COLUMN started_offline BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT uk_emergency_events_client_ref UNIQUE (user_id, client_ref),
    ADD CONSTRAINT ck_emergency_events_client_ref_format CHECK (client_ref IS NULL
        OR REGEXP_LIKE(client_ref, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    ADD CONSTRAINT ck_emergency_events_offline_ref CHECK (started_offline = FALSE OR client_ref IS NOT NULL),
    ADD CONSTRAINT ck_emergency_events_ended_order CHECK (ended_at IS NULL OR ended_at >= started_at);
