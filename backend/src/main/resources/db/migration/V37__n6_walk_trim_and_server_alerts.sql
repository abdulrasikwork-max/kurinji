-- Phase N6 (docs/PHASE_N6.md, decision D23 = N-107): the walk keeps no location, and the server texts the Safety Circle.
--
-- 1. safe_journeys ("On my way", D22): the destination coordinates go (their CHECKs first); old last-location values
--    are cleared, the columns STAY (a possible later walk-alert phase may need them; nothing writes them now).
-- 2. circle_alerts becomes the server's alert queue as well as the log of what the phone reported (D20): channel
--    SERVER_SMS, statuses QUEUED / SKIPPED, attempts, next attempt, the provider's reference, a skip reason, whether the
--    text carried a location, the owner (user_id; also for the "Test my alerts" row, which has no emergency), and the
--    recipient's number ONLY as an HMAC (recipient_key: the per-number daily limit and the stop list compare keys).
--    STILL no message text and no readable number. The closed lists move to binary collation (they are re-checked).
--    MySQL forbids CHECKs on columns with a cascading foreign key, so these rules live in code: a SERVER_SMS row has
--    a user_id; a TEST row has no emergency and no journey; every other row has exactly one of them.
-- 3. alert_settings: the person's one explicit choice (no row = not chosen yet = nothing is sent automatically).
-- 4. alert_stops: numbers that asked to receive no IRL alerts, ONLY as an HMAC (key ALERT_SECRET, outside the database).
-- Nothing else changes; no existing alert row changes meaning (old rows are all phone rows: SMS_AUTO / SMS_COMPOSER).

-- 1. safe_journeys ----------------------------------------------------------------------------------------------------
ALTER TABLE safe_journeys DROP CHECK ck_safe_journeys_destination_complete;
ALTER TABLE safe_journeys DROP CHECK ck_safe_journeys_destination_latitude;
ALTER TABLE safe_journeys DROP CHECK ck_safe_journeys_destination_longitude;
ALTER TABLE safe_journeys DROP COLUMN destination_latitude, DROP COLUMN destination_longitude;
UPDATE safe_journeys
   SET last_latitude = NULL, last_longitude = NULL, last_accuracy_meters = NULL, last_location_at = NULL
 WHERE last_latitude IS NOT NULL OR last_longitude IS NOT NULL OR last_accuracy_meters IS NOT NULL
    OR last_location_at IS NOT NULL;

-- 2. circle_alerts ----------------------------------------------------------------------------------------------------
ALTER TABLE circle_alerts DROP CHECK ck_circle_alerts_kind;
ALTER TABLE circle_alerts DROP CHECK ck_circle_alerts_channel;
ALTER TABLE circle_alerts DROP CHECK ck_circle_alerts_status;

ALTER TABLE circle_alerts
    MODIFY kind    VARCHAR(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY channel VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY status  VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    ADD COLUMN user_id           BIGINT      NULL AFTER journey_id,
    ADD COLUMN recipient_key     CHAR(64)    CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL AFTER contact_id,
    ADD COLUMN included_location TINYINT(1)  NOT NULL DEFAULT 0,
    ADD COLUMN attempts          TINYINT     NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at   DATETIME(6) NULL,                      -- UTC; set exactly while QUEUED
    ADD COLUMN sent_at           DATETIME(6) NULL,                      -- UTC; the provider accepted it (not: delivered)
    ADD COLUMN provider_ref      VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN skip_reason       VARCHAR(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL;

ALTER TABLE circle_alerts
    ADD CONSTRAINT fk_circle_alerts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    ADD INDEX idx_circle_alerts_queue (status, next_attempt_at),
    ADD INDEX idx_circle_alerts_recipient (recipient_key, sent_at),
    ADD INDEX idx_circle_alerts_user_kind (user_id, kind, occurred_at),
    ADD CONSTRAINT ck_circle_alerts_kind CHECK (kind IN (
        'WALK_NO_RESPONSE', 'SOS_STARTED', 'SOS_LOCATION', 'ALL_CLEAR', 'SOS_RESOLVED', 'SOS_CANCELLED', 'TEST')),
    ADD CONSTRAINT ck_circle_alerts_channel CHECK (channel IN ('SMS_AUTO', 'SMS_COMPOSER', 'SERVER_SMS')),
    ADD CONSTRAINT ck_circle_alerts_status CHECK (status IN (
        'SENT', 'DELIVERED', 'FAILED', 'COMPOSER_OPENED', 'QUEUED', 'SKIPPED')),
    -- What the phone reports never uses the queue's statuses or fields; what the server queues never "opens" an app.
    ADD CONSTRAINT ck_circle_alerts_phone_row CHECK (channel = 'SERVER_SMS' OR (
        status IN ('SENT', 'DELIVERED', 'FAILED', 'COMPOSER_OPENED') AND kind <> 'TEST'
        AND recipient_key IS NULL AND included_location = 0 AND attempts = 0 AND next_attempt_at IS NULL
        AND sent_at IS NULL AND provider_ref IS NULL AND skip_reason IS NULL)),
    ADD CONSTRAINT ck_circle_alerts_server_row CHECK (channel <> 'SERVER_SMS'
        OR status IN ('QUEUED', 'SENT', 'DELIVERED', 'FAILED', 'SKIPPED')),
    ADD CONSTRAINT ck_circle_alerts_queued CHECK ((status = 'QUEUED') = (next_attempt_at IS NOT NULL)),
    ADD CONSTRAINT ck_circle_alerts_skipped CHECK ((status = 'SKIPPED') = (skip_reason IS NOT NULL)),
    ADD CONSTRAINT ck_circle_alerts_skip_reason CHECK (COALESCE(skip_reason, '-') IN ('-',
        'NOT_INDIAN_MOBILE', 'NOT_IN_FIRST_FIVE', 'DUPLICATE_NUMBER', 'STOPPED', 'NUMBER_LIMIT', 'PERSON_LIMIT',
        'SERVER_LIMIT', 'TOO_LATE', 'ENDED', 'CONTACT_GONE')),
    ADD CONSTRAINT ck_circle_alerts_sent CHECK (sent_at IS NULL OR status IN ('SENT', 'DELIVERED')),
    ADD CONSTRAINT ck_circle_alerts_server_sent CHECK (channel <> 'SERVER_SMS'
        OR (status IN ('SENT', 'DELIVERED')) = (sent_at IS NOT NULL)),
    ADD CONSTRAINT ck_circle_alerts_attempts CHECK (attempts BETWEEN 0 AND 20),
    ADD CONSTRAINT ck_circle_alerts_included_location CHECK (included_location IN (0, 1)),
    ADD CONSTRAINT ck_circle_alerts_recipient_key CHECK (
        recipient_key IS NULL OR REGEXP_LIKE(recipient_key, '^[0-9a-f]{64}$', 'c')),
    ADD CONSTRAINT ck_circle_alerts_provider_ref CHECK (
        provider_ref IS NULL OR REGEXP_LIKE(provider_ref, '^[A-Za-z0-9_.:-]{1,64}$', 'c'));

-- 3. alert_settings ---------------------------------------------------------------------------------------------------
CREATE TABLE alert_settings (
    user_id     BIGINT      NOT NULL,
    server_sms  TINYINT(1)  NOT NULL,                    -- 1 = IRL texts the Safety Circle when an SOS reaches it
    decided_at  DATETIME(6) NOT NULL,                    -- UTC, the first explicit choice
    updated_at  DATETIME(6) NOT NULL,                    -- UTC, the latest change

    PRIMARY KEY (user_id),
    CONSTRAINT fk_alert_settings_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_alert_settings_server_sms CHECK (server_sms IN (0, 1)),
    CONSTRAINT ck_alert_settings_times CHECK (updated_at >= decided_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 4. alert_stops ------------------------------------------------------------------------------------------------------
CREATE TABLE alert_stops (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    number_key  CHAR(64)    CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,   -- HMAC of "+91XXXXXXXXXX", hex
    added_by    BIGINT      NULL,                                                  -- the admin; cleared if erased
    created_at  DATETIME(6) NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT uk_alert_stops_number UNIQUE (number_key),
    CONSTRAINT fk_alert_stops_added_by FOREIGN KEY (added_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_alert_stops_number_key CHECK (REGEXP_LIKE(number_key, '^[0-9a-f]{64}$', 'c'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
