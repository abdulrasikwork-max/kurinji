package com.safesphere.backend.evidence.entity;

/**
 * UPLOADING: declared, bytes arriving (resumable). SEALED: every byte arrived and the server verified the SHA-256 the
 * phone declared. DELETED: removed by the owner; only a tombstone (hashes, sizes, times) remains.
 */
public enum EvidenceStatus {
    UPLOADING,
    SEALED,
    DELETED
}
