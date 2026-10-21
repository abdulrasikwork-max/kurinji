-- Phase 10A.2 / 10A.3: account security and coercion-aware account deletion (docs/PHASE10.md).
-- Design rules:
--   * credentials_changed_at is the account's "credentials stamp". Every token carries the stamp that was current when it
--     was issued (claim "cca"); a token whose stamp differs from this column is refused on its next request
--     (ActiveAccountCheck). A password change therefore ends every other session, without refresh tokens or a token list.
--   * A deletion request never deletes at once: deletion_due_at is at least 7 days later (grace period, cancellable with
--     the password; signing in does NOT cancel it). The phone shows a banner for the whole period.
--   * An account is either deleted (the row and, through the existing cascades, everything it owns) or, when it took
--     moderation decisions that the audit log must keep (V13 actor_id / decided_by have no cascade), ERASED: the row stays
--     as an anonymous, disabled tombstone and every piece of its own safety data is deleted.
ALTER TABLE users
    ADD COLUMN credentials_changed_at DATETIME(6) NULL,   -- UTC; null = never changed since the account was created
    ADD COLUMN deletion_requested_at  DATETIME(6) NULL,   -- UTC; when the pending request was made (shown in the banner)
    ADD COLUMN deletion_due_at        DATETIME(6) NULL,   -- UTC; not before this time; later while an emergency is recent
    ADD COLUMN erased_at              DATETIME(6) NULL,   -- UTC; set only on an anonymised tombstone
    ADD CONSTRAINT ck_users_deletion_pair CHECK ((deletion_requested_at IS NULL) = (deletion_due_at IS NULL)),
    ADD CONSTRAINT ck_users_deletion_order CHECK (deletion_due_at IS NULL OR deletion_due_at > deletion_requested_at),
    ADD CONSTRAINT ck_users_erased_disabled CHECK (erased_at IS NULL OR enabled = FALSE),
    ADD CONSTRAINT ck_users_erased_no_deletion CHECK (erased_at IS NULL OR deletion_due_at IS NULL),
    ADD CONSTRAINT ck_users_erased_role CHECK (erased_at IS NULL OR role = 'USER'),
    ADD CONSTRAINT ck_users_erased_email CHECK (erased_at IS NULL OR email LIKE 'erased-%@erased.invalid');

-- The deletion job looks for due requests only.
CREATE INDEX idx_users_deletion_due ON users (deletion_due_at);
