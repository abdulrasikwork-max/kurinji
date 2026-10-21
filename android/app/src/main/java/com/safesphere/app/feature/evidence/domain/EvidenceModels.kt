package com.safesphere.app.feature.evidence.domain

import java.time.Instant

/** The evidence allow-list (same as the server's): what can be captured and how its plaintext is encoded. */
enum class EvidenceKind(val mediaType: String, val extension: String) {
    AUDIO("audio/aac", "aac"),   // AAC in an ADTS stream: a recording cut off by a crash is still playable
    PHOTO("image/jpeg", "jpg"),
    VIDEO("video/mp4", "mp4")
}

/** Where the cloud copy stands. */
enum class CloudState {
    /** Only on this phone so far (offline, waiting, or upload not started). */
    NOT_UPLOADED,
    /** Declared to the server, bytes on their way (resumable). */
    UPLOADING,
    /** Every byte arrived and the SERVER verified the SHA-256: the vault copy is intact. */
    SEALED,
    /** The person deleted it everywhere; only a tombstone (kind, time, hashes) remains. */
    DELETED
}

/**
 * One evidence item as this phone knows it (the local vault index). Holds NO plaintext and NO location: only metadata,
 * hashes, and the item key sealed by the Android Keystore ([wrappedKey], base64). The ciphertext file is
 * vault/<clientRef>.ssev while [onPhone] is true.
 */
data class VaultItem(
    val clientRef: String,
    val userId: Long,
    val emergencyId: Long,
    val kind: EvidenceKind,
    val sequenceNo: Int,
    val recordingRef: String?,
    val capturedStartedAt: Instant,
    val capturedEndedAt: Instant,
    val plaintextBytes: Long,
    val ciphertextBytes: Long,
    val plaintextSha256: String,
    val ciphertextSha256: String,
    /** Null once the item was deleted everywhere (crypto-shredded on this phone). */
    val wrappedKey: String?,
    val onPhone: Boolean,
    val cloud: CloudState = CloudState.NOT_UPLOADED,
    val serverId: Long? = null,
    val uploadedBytes: Long = 0,
    val escrowed: Boolean = false,
    val sealedAt: Instant? = null,
    val deletedAt: Instant? = null,
    /** The server refused this item for good (e.g. a capture time outside the emergency): kept, never retried. */
    val uploadRefused: Boolean = false,
    /** The server holds a copy of the item key wrapped with the person's recovery key (restorable on a new phone). */
    val recoverable: Boolean = false
) {
    val isDeleted: Boolean get() = cloud == CloudState.DELETED
    val needsUpload: Boolean get() = onPhone && !uploadRefused && (cloud == CloudState.NOT_UPLOADED || cloud == CloudState.UPLOADING)

    override fun toString(): String =
        "VaultItem(kind=$kind, sequenceNo=$sequenceNo, cloud=$cloud, onPhone=$onPhone, key=<redacted>)"
}

/** What the server says about an item (see backend EvidenceDtos.EvidenceResponse). */
data class RemoteEvidence(
    val id: Long,
    val clientRef: String,
    val emergencyId: Long,
    val kind: EvidenceKind?,
    val status: String,
    val sizeBytes: Long,
    val receivedBytes: Long,
    val ciphertextSha256: String,
    val escrowed: Boolean,
    val capturedStartedAt: Instant,
    val capturedEndedAt: Instant,
    val sealedAt: Instant?,
    val deletedAt: Instant?,
    val uploadChunkBytes: Int,
    val sequenceNo: Int = 0,
    val recordingRef: String? = null,
    val plaintextSha256: String? = null,
    val recoverable: Boolean = false
)

/** Body of a declaration (metadata only; the bytes follow). */
data class EvidenceDeclaration(
    val clientRef: String,
    val kind: EvidenceKind,
    val sequenceNo: Int,
    val recordingRef: String?,
    val sizeBytes: Long,
    val ciphertextSha256: String,
    val plaintextSha256: String,
    val capturedStartedAt: Instant,
    val capturedEndedAt: Instant,
    val escrowKeyId: String?,
    val escrowWrappedKey: String?,
    val recoveryRkVersion: Int? = null,
    val recoveryWrappedKey: String? = null
) {
    override fun toString(): String =
        "EvidenceDeclaration(kind=$kind, sequenceNo=$sequenceNo, escrow=${escrowKeyId != null}, recovery=${recoveryWrappedKey != null})"
}

/** GET /api/evidence/recovery: whether a recovery passphrase is set up (no key material). */
data class RecoveryStatus(
    val configured: Boolean,
    val rkVersion: Int?,
    val envelopeVersion: Int?,
    val updatedAt: Instant?,
    val lastDownloadedAt: Instant?
)

/** The recovery envelope: the recovery key wrapped with a key derived from the passphrase. Opaque without it. */
class RecoveryEnvelope(
    val rkVersion: Int,
    val envelopeVersion: Int,
    val kdf: String,
    val kdfIterations: Int,
    val kdfSalt: ByteArray,
    val wrappedRecoveryKey: ByteArray
) {
    override fun toString(): String = "RecoveryEnvelope(rkVersion=$rkVersion, envelopeVersion=$envelopeVersion)"
}

/** One item a new phone can restore, with its key wrapped by the recovery key. */
class RecoverableEvidence(val item: RemoteEvidence, val rkVersion: Int, val wrappedKey: ByteArray) {
    override fun toString(): String = "RecoverableEvidence(id=${item.id}, key=<redacted>)"
}

data class RecoverablePage(val items: List<RecoverableEvidence>, val hasMore: Boolean)

/** The public half of the vault recovery key, as published by the server. */
data class VaultKeyInfo(val available: Boolean, val keyId: String?, val publicKey: String?)

/** The server re-hashed its stored copy ([intact] false = damaged; [item] is then UPLOADING again). */
data class RemoteVerification(val intact: Boolean, val verifiedAt: Instant, val item: RemoteEvidence)

/** One custody ledger entry + the server's verification of the person's whole chain. */
data class CustodyEntry(
    val sequenceNo: Long,
    val evidenceId: Long,
    val action: String,
    val occurredAt: Instant,
    val entryHash: String? = null
)

data class CustodyReport(
    val chainIntact: Boolean,
    val chainLength: Long,
    val latestEntryHash: String?,
    val verifiedAt: Instant,
    val entries: List<CustodyEntry>
)
