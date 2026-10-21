package com.safesphere.app.feature.evidence.data.remote

// Mirrors the JSON of the Phase 5 Evidence Vault API (docs/API.md). No user id anywhere: the server takes the person
// from the JWT. No location, file name or device detail is ever sent with evidence.

data class VaultKeyDto(val available: Boolean, val keyId: String?, val algorithm: String?, val publicKey: String?)

data class DeclareEvidenceDto(
    val clientRef: String,
    val kind: String,
    val mediaType: String,
    val sequenceNo: Int,
    val recordingRef: String?,
    val sizeBytes: Long,
    val ciphertextSha256: String,
    val plaintextSha256: String,
    val encryption: String,
    val capturedStartedAt: String,
    val capturedEndedAt: String,
    val escrowKeyId: String?,
    val escrowWrappedKey: String?,
    val recoveryRkVersion: Int? = null,
    val recoveryWrappedKey: String? = null
) {
    override fun toString(): String = "DeclareEvidenceDto(kind=$kind, sequenceNo=$sequenceNo, escrow=<redacted>, recovery=<redacted>)"
}

// ---- restore on a new phone (V11) ----

data class RecoveryStatusDto(
    val configured: Boolean,
    val rkVersion: Int?,
    val envelopeVersion: Int?,
    val updatedAt: String?,
    val lastDownloadedAt: String?
)

data class SetRecoveryDto(
    val password: String,
    val expectedEnvelopeVersion: Int?,
    val rkVersion: Int,
    val kdf: String,
    val kdfIterations: Int,
    val kdfSalt: String,
    val wrappedRecoveryKey: String
) {
    override fun toString(): String = "SetRecoveryDto(password=<redacted>, rkVersion=$rkVersion, kdf=$kdf)"
}

data class RecoveryPasswordDto(val password: String) {
    override fun toString(): String = "RecoveryPasswordDto(password=<redacted>)"
}

data class RecoveryEnvelopeDto(
    val rkVersion: Int,
    val envelopeVersion: Int,
    val kdf: String,
    val kdfIterations: Int,
    val kdfSalt: String,
    val wrappedRecoveryKey: String,
    val updatedAt: String?
) {
    override fun toString(): String = "RecoveryEnvelopeDto(rkVersion=$rkVersion, envelopeVersion=$envelopeVersion)"
}

data class RecoverableItemDto(val item: EvidenceDto, val recoveryRkVersion: Int, val recoveryWrappedKey: String) {
    override fun toString(): String = "RecoverableItemDto(id=${item.id}, key=<redacted>)"
}

data class RecoverableItemsPageDto(val items: List<RecoverableItemDto>?, val page: Int, val hasMore: Boolean)

data class AttachRecoveryKeyDto(val rkVersion: Int, val wrappedKey: String) {
    override fun toString(): String = "AttachRecoveryKeyDto(rkVersion=$rkVersion, wrappedKey=<redacted>)"
}

data class DeleteEvidenceDto(val password: String) {
    override fun toString(): String = "DeleteEvidenceDto(password=<redacted>)"
}

data class EvidenceDto(
    val id: Long,
    val clientRef: String,
    val emergencyId: Long,
    val kind: String?,
    val mediaType: String?,
    val sequenceNo: Int,
    val recordingRef: String?,
    val status: String,
    val sizeBytes: Long,
    val receivedBytes: Long,
    val ciphertextSha256: String,
    val plaintextSha256: String?,
    val escrowed: Boolean,
    val recoverable: Boolean,
    val capturedStartedAt: String,
    val capturedEndedAt: String,
    val receivedAt: String?,
    val sealedAt: String?,
    val deletedAt: String?,
    val uploadChunkBytes: Int
)

data class VerifyEvidenceDto(val intact: Boolean, val verifiedAt: String, val item: EvidenceDto)

data class CustodyEntryDto(
    val sequenceNo: Long,
    val evidenceId: Long,
    val action: String,
    val ciphertextSha256: String?,
    val occurredAt: String,
    val entryHash: String?
)

data class CustodyReportDto(
    val chainIntact: Boolean,
    val brokenAtSequence: Long?,
    val chainLength: Long,
    val latestEntryHash: String?,
    val verifiedAt: String,
    val entries: List<CustodyEntryDto>?
)
