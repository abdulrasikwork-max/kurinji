-- Phase 5 hardening: two new chain-of-custody actions.
--
--   VERIFIED        the owner asked the server to re-hash the stored ciphertext ("is my vault copy still intact?")
--                   and every byte still matched. A mismatch is recorded as INTEGRITY_FAILED (already allowed).
--   DELETE_REFUSED  someone tried to delete the item with a WRONG account password. Recorded so that an attempt to
--                   destroy evidence (for example by a person holding the victim's unlocked phone) leaves a trace,
--                   and counted to lock deletion after repeated failures.
--
-- No column changes: only the allow-list CHECK is replaced. Existing rows are unaffected (their actions stay valid).
ALTER TABLE evidence_custody_events DROP CHECK ck_evidence_custody_action;

ALTER TABLE evidence_custody_events
    ADD CONSTRAINT ck_evidence_custody_action CHECK (action IN
        ('DECLARED', 'SEALED', 'INTEGRITY_FAILED', 'DOWNLOADED', 'DELETED', 'VERIFIED', 'DELETE_REFUSED'));

-- Counting recent refused deletions per person reads (user_id, action, occurred_at).
CREATE INDEX idx_evidence_custody_user_action_time ON evidence_custody_events (user_id, action, occurred_at);
