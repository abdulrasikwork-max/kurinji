package com.safesphere.backend.evidence.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.safesphere.backend.evidence.entity.CustodyEvent;
import com.safesphere.backend.evidence.entity.EvidenceItem;
import com.safesphere.backend.evidence.entity.EvidenceKind;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Request / response bodies of the Evidence Vault API (Phase 5). Records = explicit field allow-lists. */
public final class EvidenceDtos {

    private static final String UUID = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
    private static final String SHA256 = "^[0-9a-f]{64}$";

    private EvidenceDtos() {
    }

    /**
     * Body of POST /api/emergencies/{id}/evidence. Metadata only: the bytes follow with PUT .../content.
     * [clientRef] is a UUID made by the phone; declaring the same item again returns the existing record.
     * [escrowKeyId] + [escrowWrappedKey] (both or neither): the item key sealed for the vault recovery key.
     * No location, no file name, no device details are accepted.
     */
    public record DeclareRequest(
            @NotNull(message = "Reference is required")
            @Pattern(regexp = UUID, message = "Reference must be a UUID")
            String clientRef,

            @NotNull(message = "Kind is required") EvidenceKind kind,

            @NotNull(message = "Media type is required") String mediaType,

            @NotNull(message = "Sequence number is required")
            @Min(value = 0, message = "Sequence number must not be negative")
            @Max(value = 1_000_000, message = "Sequence number is too large")
            Integer sequenceNo,

            @Pattern(regexp = UUID, message = "Recording reference must be a UUID")
            String recordingRef,

            @NotNull(message = "Size is required")
            @Min(value = 1, message = "Size must be positive")
            Long sizeBytes,

            @NotNull(message = "Ciphertext hash is required")
            @Pattern(regexp = SHA256, message = "Ciphertext hash must be 64 lower-case hex characters")
            String ciphertextSha256,

            @NotNull(message = "Plaintext hash is required")
            @Pattern(regexp = SHA256, message = "Plaintext hash must be 64 lower-case hex characters")
            String plaintextSha256,

            @NotNull(message = "Encryption format is required") String encryption,

            @NotNull(message = "Capture start is required") Instant capturedStartedAt,

            @NotNull(message = "Capture end is required") Instant capturedEndedAt,

            @Pattern(regexp = SHA256, message = "Escrow key id must be 64 lower-case hex characters")
            String escrowKeyId,

            @Size(max = 1400, message = "Escrow key is too long")
            String escrowWrappedKey,

            @Min(value = 1, message = "Recovery key version must be positive")
            Integer recoveryRkVersion,

            @Size(max = 200, message = "Recovery key is too long")
            String recoveryWrappedKey
    ) {
        @Override
        public String toString() {
            return "DeclareRequest{kind=" + kind + ", sequenceNo=" + sequenceNo + ", sizeBytes=" + sizeBytes
                    + ", escrow=" + (escrowWrappedKey != null) + ", recovery=" + (recoveryWrappedKey != null) + "}";
        }
    }

    /** Body of PUT /api/evidence/{id}/recovery-key: the item key wrapped with the person's recovery key. */
    public record AttachRecoveryKeyRequest(
            @NotNull(message = "Recovery key version is required")
            @Min(value = 1, message = "Recovery key version must be positive")
            Integer rkVersion,

            @NotBlank(message = "Recovery key is required")
            @Size(max = 200, message = "Recovery key is too long")
            String wrappedKey
    ) {
        @Override
        public String toString() {
            return "AttachRecoveryKeyRequest{rkVersion=" + rkVersion + ", wrappedKey=<redacted>}";
        }
    }

    /**
     * Body of PUT /api/evidence/recovery: a new or replaced recovery envelope. The account password is re-checked.
     * [expectedEnvelopeVersion] null = "there is none yet"; otherwise it must be the current one (no lost updates).
     */
    public record SetRecoveryRequest(
            @NotBlank(message = "Password is required")
            @Size(max = 200, message = "Password is too long")
            String password,

            @Min(value = 1, message = "Envelope version must be positive")
            Integer expectedEnvelopeVersion,

            @NotNull(message = "Recovery key version is required")
            @Min(value = 1, message = "Recovery key version must be positive")
            Integer rkVersion,

            @NotNull(message = "Key derivation is required") String kdf,

            @NotNull(message = "Iterations are required") Integer kdfIterations,

            @NotBlank(message = "Salt is required")
            @Size(max = 100, message = "Salt is too long")
            String kdfSalt,

            @NotBlank(message = "Wrapped recovery key is required")
            @Size(max = 200, message = "Wrapped recovery key is too long")
            String wrappedRecoveryKey
    ) {
        @Override
        public String toString() {
            return "SetRecoveryRequest{password=<redacted>, rkVersion=" + rkVersion + ", kdf=" + kdf
                    + ", kdfIterations=" + kdfIterations + "}";
        }
    }

    /** Body of POST /api/evidence/recovery/envelope. */
    public record RecoveryEnvelopeRequest(
            @NotBlank(message = "Password is required")
            @Size(max = 200, message = "Password is too long")
            String password
    ) {
        @Override
        public String toString() {
            return "RecoveryEnvelopeRequest{password=<redacted>}";
        }
    }

    /** GET /api/evidence/recovery: status only, never key material. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RecoveryStatusResponse(boolean configured, Integer rkVersion, Integer envelopeVersion,
                                         Instant updatedAt, Instant lastDownloadedAt) {
    }

    /** The envelope, handed out only after the account password was re-checked. Opaque without the passphrase. */
    public record RecoveryEnvelopeResponse(int rkVersion, int envelopeVersion, String kdf, int kdfIterations,
                                           String kdfSalt, String wrappedRecoveryKey, Instant updatedAt) {
        @Override
        public String toString() {
            return "RecoveryEnvelopeResponse{rkVersion=" + rkVersion + ", envelopeVersion=" + envelopeVersion + "}";
        }
    }

    /** One item a new phone can restore: its metadata plus the item key wrapped with the recovery key. */
    public record RecoverableItem(EvidenceResponse item, int recoveryRkVersion, String recoveryWrappedKey) {
        @Override
        public String toString() {
            return "RecoverableItem{id=" + item.id() + ", recoveryWrappedKey=<redacted>}";
        }
    }

    /** GET /api/evidence/recovery/items?page=N (pages of [RECOVERY_PAGE_SIZE], oldest first). */
    public record RecoverableItemsPage(List<RecoverableItem> items, int page, boolean hasMore) {
    }

    /** Body of POST /api/evidence/{id}/delete. The account password is re-checked (an unlocked phone is not enough). */
    public record DeleteRequest(
            @NotBlank(message = "Password is required")
            @Size(max = 200, message = "Password is too long")
            String password
    ) {
        @Override
        public String toString() {
            return "DeleteRequest{password=<redacted>}";
        }
    }

    /**
     * What the owner sees about one item. Never includes the escrow or recovery key copies or the storage file name
     * ([recoverable] only says that a recovery copy exists).
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record EvidenceResponse(
            Long id,
            String clientRef,
            Long emergencyId,
            String kind,
            String mediaType,
            int sequenceNo,
            String recordingRef,
            String status,
            long sizeBytes,
            long receivedBytes,
            String ciphertextSha256,
            String plaintextSha256,
            boolean escrowed,
            boolean recoverable,
            Instant capturedStartedAt,
            Instant capturedEndedAt,
            Instant receivedAt,
            Instant sealedAt,
            Instant deletedAt,
            int uploadChunkBytes
    ) {
        public static EvidenceResponse from(EvidenceItem item, int chunkBytes) {
            return new EvidenceResponse(item.getId(), item.getClientRef(), item.getEmergencyId(), item.getKind().name(),
                    item.getMediaType(), item.getSequenceNo(), item.getRecordingRef(), item.getStatus().name(),
                    item.getSizeBytes(), item.getReceivedBytes(), item.getCiphertextSha256(), item.getPlaintextSha256(),
                    item.getEscrowKeyId() != null, item.getRecoveryWrappedKey() != null, utc(item.getCapturedStartedAt()), utc(item.getCapturedEndedAt()),
                    utc(item.getReceivedAt()), utc(item.getSealedAt()), utc(item.getDeletedAt()), chunkBytes);
        }
    }

    /**
     * POST /api/evidence/{id}/verify: the server re-hashed its stored copy at [verifiedAt]. [intact] false = the copy
     * was damaged; [item] then shows UPLOADING (the phone uploads its copy again if it still has one).
     */
    public record VerifyResponse(boolean intact, Instant verifiedAt, EvidenceResponse item) {
    }

    /** GET /api/evidence/vault-key. [available] false = no escrow on this server (the other fields are null). */
    public record VaultKeyResponse(boolean available, String keyId, String algorithm, String publicKey) {
    }

    /** GET /api/emergencies/{id}/evidence/custody: the custody entries of this emergency's items + chain check. */
    public record CustodyReport(
            boolean chainIntact,
            Long brokenAtSequence,
            long chainLength,
            String latestEntryHash,
            Instant verifiedAt,
            List<CustodyEntry> entries
    ) {
    }

    public record CustodyEntry(long sequenceNo, long evidenceId, String action, String ciphertextSha256,
                               Instant occurredAt, String entryHash) {
        public static CustodyEntry from(CustodyEvent event) {
            return new CustodyEntry(event.getSequenceNo(), event.getEvidenceId(), event.getAction().name(),
                    event.getCiphertextSha256(), utc(event.getOccurredAt()), event.getEntryHash());
        }
    }

    private static Instant utc(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}
