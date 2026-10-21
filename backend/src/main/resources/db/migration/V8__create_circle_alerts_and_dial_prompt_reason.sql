-- Phase 4D: escalation communication.
-- 1. circle_alerts: the log of every attempt to tell a Safety Circle contact something (SOS started, a Safe Walk
--    check-in got no response, all clear...). The PHONE sends the SMS (automatically only if the person opted in,
--    otherwise they send it themselves from the SMS app); the server only RECORDS what the phone reports, so the
--    person (and later the Emergency Context Packet) can see who was told what, and when.
--    Honest statuses: SENT = handed to the mobile network (NOT delivered), DELIVERED = the network's delivery
--    report arrived, FAILED = could not be sent, COMPOSER_OPENED = the SMS app was opened for the person to send.
--    No phone number and no message text is stored: the contact is referenced by id (SET NULL if it is deleted).
--    "At least one of emergency_id / journey_id" is enforced by the service: MySQL does not allow a CHECK on
--    columns with an ON DELETE CASCADE foreign key (same as V6).
CREATE TABLE circle_alerts (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    emergency_id BIGINT      NULL,
    journey_id   BIGINT      NULL,
    contact_id   BIGINT      NULL,
    kind         VARCHAR(30) NOT NULL,
    channel      VARCHAR(20) NOT NULL,
    status       VARCHAR(20) NOT NULL,
    client_ref   CHAR(36)    NOT NULL,                   -- UUID made by the phone: re-sending the same report is safe
    occurred_at  DATETIME(6) NOT NULL,                   -- UTC, when the phone sent / opened it
    received_at  DATETIME(6) NOT NULL,                   -- UTC, server time of the first report
    updated_at   DATETIME(6) NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT uk_circle_alerts_client_ref UNIQUE (client_ref),
    CONSTRAINT fk_circle_alerts_emergency FOREIGN KEY (emergency_id) REFERENCES emergency_events (id) ON DELETE CASCADE,
    CONSTRAINT fk_circle_alerts_journey FOREIGN KEY (journey_id) REFERENCES safe_journeys (id) ON DELETE CASCADE,
    CONSTRAINT fk_circle_alerts_contact FOREIGN KEY (contact_id) REFERENCES safety_contacts (id) ON DELETE SET NULL,
    INDEX idx_circle_alerts_emergency_time (emergency_id, occurred_at),
    INDEX idx_circle_alerts_journey_time (journey_id, occurred_at),
    CONSTRAINT ck_circle_alerts_kind CHECK (kind IN (
        'WALK_NO_RESPONSE', 'SOS_STARTED', 'SOS_LOCATION', 'ALL_CLEAR', 'SOS_RESOLVED', 'SOS_CANCELLED')),
    CONSTRAINT ck_circle_alerts_channel CHECK (channel IN ('SMS_AUTO', 'SMS_COMPOSER')),
    CONSTRAINT ck_circle_alerts_status CHECK (status IN ('SENT', 'DELIVERED', 'FAILED', 'COMPOSER_OPENED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 2. Level 4 (emergency pathway) is a USER-TAPPED dial prompt to 112: the reason records that the dialer was
--    opened. Whether a call was actually made is unknown to the app and never claimed.
ALTER TABLE escalation_events DROP CHECK ck_escalation_events_reason;
ALTER TABLE escalation_events
    ADD CONSTRAINT ck_escalation_events_reason CHECK (reason IN (
        'CHECKED_IN', 'CHECK_IN_MISSED', 'NO_RESPONSE', 'SOS_CONFIRMED', 'ROUTE_DEVIATION', 'UNEXPECTED_STOP',
        'DIAL_PROMPT_OPENED'));
