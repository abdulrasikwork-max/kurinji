package com.safesphere.backend.evidence.entity;

/**
 * What a piece of evidence is, and the ONE plaintext media type accepted for it. A fixed allow-list keeps the vault
 * from becoming general file storage, and tells a future reviewer exactly how to open a decrypted item.
 */
public enum EvidenceKind {
    AUDIO("audio/aac"),   // AAC in an ADTS stream: still playable if the recording was cut off
    PHOTO("image/jpeg"),
    VIDEO("video/mp4");

    private final String mediaType;

    EvidenceKind(String mediaType) {
        this.mediaType = mediaType;
    }

    public String mediaType() {
        return mediaType;
    }
}
