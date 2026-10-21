package com.safesphere.backend.evidence.entity;

/** One step in the chain of custody of an evidence item (migrations V9, V10: evidence_custody_events). */
public enum CustodyAction {
    DECLARED,
    SEALED,
    INTEGRITY_FAILED,
    DOWNLOADED,
    DELETED,
    /** V10: the owner asked the server to re-hash the stored copy and it still matched. */
    VERIFIED,
    /** V10: a deletion was attempted with a wrong password (kept as evidence of the attempt; counted for lockout). */
    DELETE_REFUSED
}
