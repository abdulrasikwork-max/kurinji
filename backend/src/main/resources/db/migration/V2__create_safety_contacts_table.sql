-- Phase 3: a user's Safety Circle (trusted contacts).
CREATE TABLE safety_contacts (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    name         VARCHAR(100) NOT NULL,
    phone_number VARCHAR(20)  NOT NULL,            -- normalised: optional leading +, then 7-15 digits
    email        VARCHAR(255) NULL,
    relationship VARCHAR(20)  NOT NULL,            -- FAMILY, PARTNER, FRIEND, COLLEAGUE, NEIGHBOR, OTHER
    priority     INT          NOT NULL,            -- 1 (contact first) to 5
    is_primary   BOOLEAN      NOT NULL DEFAULT FALSE,
    is_enabled   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   DATETIME(6)  NOT NULL,            -- UTC
    updated_at   DATETIME(6)  NOT NULL,            -- UTC

    -- Lets the database itself enforce "at most ONE primary contact per user":
    -- the value is 1 for a primary contact and NULL otherwise, and a UNIQUE index ignores NULLs.
    primary_marker TINYINT GENERATED ALWAYS AS (CASE WHEN is_primary THEN 1 ELSE NULL END) STORED,

    PRIMARY KEY (id),
    -- Same person cannot be added twice to one user's circle. Different users may share a number.
    CONSTRAINT uk_safety_contacts_user_phone UNIQUE (user_id, phone_number),
    CONSTRAINT uk_safety_contacts_one_primary UNIQUE (user_id, primary_marker),
    -- No orphans: deleting a user deletes their contacts. (The user_id prefix of the unique keys also serves lookups by user.)
    CONSTRAINT fk_safety_contacts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_safety_contacts_priority CHECK (priority BETWEEN 1 AND 5)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
