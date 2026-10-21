-- Phase 4C: Safety Bubble and unexpected-stop checks.
-- 1. The walk's destination as coordinates (found by the phone's map lookup from the text the person typed).
--    The phone needs them to draw the "bubble" (a corridor around the straight start -> destination line) and,
--    after a restart, gets them back from here instead of keeping them on the phone. Optional: NULL when the
--    person typed no destination, switched the checks off, or the lookup failed.
--    Cleared together with the last location when the walk ends normally (COMPLETED / CANCELLED); kept when it
--    ended in an EMERGENCY (the emergency record may need it).
ALTER TABLE safe_journeys
    ADD COLUMN destination_latitude  DECIMAL(9, 6) NULL AFTER destination_label,
    ADD COLUMN destination_longitude DECIMAL(9, 6) NULL AFTER destination_latitude,
    ADD CONSTRAINT ck_safe_journeys_destination_latitude
        CHECK (destination_latitude IS NULL OR destination_latitude BETWEEN -90 AND 90),
    ADD CONSTRAINT ck_safe_journeys_destination_longitude
        CHECK (destination_longitude IS NULL OR destination_longitude BETWEEN -180 AND 180),
    -- Both or neither.
    ADD CONSTRAINT ck_safe_journeys_destination_complete
        CHECK ((destination_latitude IS NULL) = (destination_longitude IS NULL));

-- 2. Two new reasons for level 1 ("minor concern -> prompt the person"): the phone saw a sustained departure
--    from the Safety Bubble, or no movement for a long time. Applied migrations are never edited, so the V6
--    CHECK is replaced here.
ALTER TABLE escalation_events DROP CHECK ck_escalation_events_reason;
ALTER TABLE escalation_events
    ADD CONSTRAINT ck_escalation_events_reason CHECK (reason IN (
        'CHECKED_IN', 'CHECK_IN_MISSED', 'NO_RESPONSE', 'SOS_CONFIRMED', 'ROUTE_DEVIATION', 'UNEXPECTED_STOP'));
