package com.safesphere.backend.evidence.service;

import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.common.exception.TooManyAttemptsException;
import com.safesphere.backend.config.EvidenceProperties;
import com.safesphere.backend.emergency.entity.EmergencyEvent;
import com.safesphere.backend.emergency.entity.EmergencyStatus;
import com.safesphere.backend.emergency.repository.EmergencyEventRepository;
import com.safesphere.backend.evidence.dto.EvidenceDtos.CustodyEntry;
import com.safesphere.backend.evidence.dto.EvidenceDtos.CustodyReport;
import com.safesphere.backend.evidence.dto.EvidenceDtos.DeclareRequest;
import com.safesphere.backend.evidence.dto.EvidenceDtos.EvidenceResponse;
import com.safesphere.backend.evidence.dto.EvidenceDtos.VaultKeyResponse;
import com.safesphere.backend.evidence.dto.EvidenceDtos.VerifyResponse;
import com.safesphere.backend.evidence.entity.CustodyAction;
import com.safesphere.backend.evidence.entity.EvidenceItem;
import com.safesphere.backend.evidence.entity.EvidenceStatus;
import com.safesphere.backend.evidence.repository.EvidenceItemRepository;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The Evidence Vault (Phase 5). The PHONE records, encrypts and hashes; the server only ever receives ciphertext.
 *
 * Life of an item: declare (metadata + hashes) -> upload the bytes in pieces (resumable, any order of requests is
 * serialised by a row lock) -> seal (the server recomputes SHA-256 over every byte and compares it with what the phone
 * declared) -> optionally download (owner) -> optionally delete (owner, password, never during an ACTIVE emergency).
 * Every step is appended to the hash-chained custody ledger in the same transaction.
 *
 * Rules (all checked against the SERVER clock): the emergency must belong to the caller (otherwise 404, like every
 * other SafeSphere record); evidence must have been captured during that emergency (5-minute clock tolerance); the
 * plaintext type must match the kind (allow-list); per-item size, per-emergency count and per-person quota limits.
 * Uploading is allowed at any time after the emergency (an offline phone may come back days later).
 */
@Service
public class EvidenceService {

    public static final String ENCRYPTION_FORMAT = "SSEV1";
    static final Duration MAX_CAPTURE_DURATION = Duration.ofHours(3);
    private static final String NOT_FOUND = "Evidence not found";
    private static final String EMERGENCY_NOT_FOUND = "Emergency not found";
    private static final Logger log = LoggerFactory.getLogger(EvidenceService.class);

    private final EvidenceItemRepository repository;
    private final EmergencyEventRepository emergencyRepository;
    private final UserRepository userRepository;
    private final CustodyLedger ledger;
    private final EvidenceStorage storage;
    private final VaultKey vaultKey;
    private final EvidenceProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final RecoveryService recovery;

    public EvidenceService(EvidenceItemRepository repository, EmergencyEventRepository emergencyRepository,
                           UserRepository userRepository, CustodyLedger ledger, EvidenceStorage storage,
                           VaultKey vaultKey, EvidenceProperties properties, PasswordEncoder passwordEncoder,
                           Clock clock, RecoveryService recovery) {
        this.recovery = recovery;
        this.repository = repository;
        this.emergencyRepository = emergencyRepository;
        this.userRepository = userRepository;
        this.ledger = ledger;
        this.storage = storage;
        this.vaultKey = vaultKey;
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    public VaultKeyResponse vaultKey() {
        return vaultKey.current()
                .map(k -> new VaultKeyResponse(true, k.keyId(), VaultKey.ALGORITHM, k.publicKeyBase64()))
                .orElse(new VaultKeyResponse(false, null, null, null));
    }

    // ---- declare ---------------------------------------------------------------------------------

    /** Returns the item and whether it was created now (false = the same declaration was already known). */
    @Transactional
    public Declared declare(long userId, long emergencyId, DeclareRequest request) {
        // Locked: the count / sequence / quota checks stay correct when the phone sends two declarations at once.
        EmergencyEvent emergency = emergencyRepository.findByIdAndUserIdForUpdate(emergencyId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(EMERGENCY_NOT_FOUND));
        String clientRef = request.clientRef().toLowerCase();

        EvidenceItem known = repository.findByClientRef(clientRef).orElse(null);
        if (known != null) {
            boolean same = known.getEmergencyId().equals(emergencyId)
                    && known.getCiphertextSha256().equals(request.ciphertextSha256())
                    && known.getSizeBytes() == request.sizeBytes();
            if (!same) throw new ConflictException("This evidence reference is already used");
            return new Declared(response(known), false);
        }

        if (!request.kind().mediaType().equals(request.mediaType())) {
            throw new InvalidRequestException("Media type does not match the kind of evidence");
        }
        if (!ENCRYPTION_FORMAT.equals(request.encryption())) {
            throw new InvalidRequestException("Unsupported encryption format");
        }
        if (request.sizeBytes() > properties.maxItemBytes()) {
            throw new InvalidRequestException("Evidence item is too large");
        }
        LocalDateTime now = now();
        LocalDateTime started = utcMicros(request.capturedStartedAt());
        LocalDateTime ended = utcMicros(request.capturedEndedAt());
        checkCaptureTimes(started, ended, emergency, now);
        byte[] escrow = checkedEscrow(request);
        byte[] recovery = checkedRecovery(userId, request);

        if (repository.countByEmergencyId(emergencyId) >= properties.maxItemsPerEmergency()) {
            throw new ConflictException("This emergency already holds the maximum number of evidence items");
        }
        // The quota spans all of the person's emergencies: lock the person too (same order as the custody ledger).
        userRepository.findByIdForUpdate(userId).orElseThrow(() -> new ResourceNotFoundException(EMERGENCY_NOT_FOUND));
        if (repository.storedBytes(userId, EvidenceStatus.DELETED) + request.sizeBytes() > properties.userQuotaBytes()) {
            throw new ConflictException("Your evidence vault is full");
        }

        String storageKey = EvidenceStorage.newKey();
        EvidenceItem item = repository.saveAndFlush(new EvidenceItem(emergencyId, clientRef, request.kind(),
                request.sequenceNo(), request.recordingRef() == null ? null : request.recordingRef().toLowerCase(),
                request.sizeBytes(), request.ciphertextSha256(), request.plaintextSha256(), ENCRYPTION_FORMAT,
                escrow == null ? null : request.escrowKeyId(), escrow, storageKey, started, ended, now));
        if (recovery != null) {
            item.attachRecoveryKey(request.recoveryRkVersion(), recovery, now);
            repository.saveAndFlush(item);
        }
        storage.create(storageKey); // after the insert: a failed insert leaves no file; a failed commit leaves an orphan
        ledger.append(userId, item, CustodyAction.DECLARED, now);
        return new Declared(response(item), true);
    }

    private void checkCaptureTimes(LocalDateTime started, LocalDateTime ended, EmergencyEvent emergency,
                                   LocalDateTime now) {
        Duration tolerance = properties.clockTolerance();
        if (ended.isBefore(started)) {
            throw new InvalidRequestException("Capture end is before its start");
        }
        if (ended.isAfter(now.plus(tolerance))) {
            throw new InvalidRequestException("Capture time cannot be in the future");
        }
        if (started.isBefore(emergency.getStartedAt().minus(tolerance))) {
            throw new InvalidRequestException("Evidence was captured before the emergency started");
        }
        if (emergency.getEndedAt() != null && started.isAfter(emergency.getEndedAt().plus(tolerance))) {
            throw new InvalidRequestException("Evidence was captured after the emergency ended");
        }
        if (Duration.between(started, ended).compareTo(MAX_CAPTURE_DURATION) > 0) {
            throw new InvalidRequestException("Capture is too long");
        }
    }

    /** Both or neither; the key id must be the server's CURRENT vault key, and the length must fit that key. */
    private byte[] checkedEscrow(DeclareRequest request) {
        boolean hasId = request.escrowKeyId() != null;
        boolean hasKey = request.escrowWrappedKey() != null;
        if (hasId != hasKey) throw new InvalidRequestException("Escrow key id and escrow key go together");
        if (!hasId) return null;
        VaultKey.Loaded current = vaultKey.current()
                .orElseThrow(() -> new ConflictException("This server has no vault recovery key"));
        if (!current.keyId().equals(request.escrowKeyId())) {
            throw new ConflictException("The vault recovery key has changed; fetch it again");
        }
        byte[] wrapped;
        try {
            wrapped = Base64.getDecoder().decode(request.escrowWrappedKey());
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Escrow key is not valid base64");
        }
        if (wrapped.length != current.wrappedKeyBytes()) {
            throw new InvalidRequestException("Escrow key has the wrong length for the vault key");
        }
        return wrapped;
    }

    /** Recovery copy of the item key (V11): both fields or neither; checked against the person's current RK. */
    private byte[] checkedRecovery(long userId, DeclareRequest request) {
        boolean hasVersion = request.recoveryRkVersion() != null;
        boolean hasKey = request.recoveryWrappedKey() != null;
        if (hasVersion != hasKey) throw new InvalidRequestException("Recovery key version and recovery key go together");
        if (!hasVersion) return null;
        return recovery.checkedItemWrap(userId, request.recoveryRkVersion(), request.recoveryWrappedKey());
    }

    // ---- upload and seal -------------------------------------------------------------------------

    /**
     * Appends one piece at [offset], which must equal the bytes already received (otherwise 409: the phone asks for
     * the item again and continues from receivedBytes). Never accepts more than the declared size.
     */
    @Transactional
    public EvidenceResponse upload(long userId, long evidenceId, long offset, long contentLength, InputStream body) {
        EvidenceItem item = repository.findOwnedForUpdate(evidenceId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        requireUploading(item);
        if (offset != item.getReceivedBytes()) {
            throw new ConflictException("Upload must continue at byte " + item.getReceivedBytes());
        }
        long remaining = item.getSizeBytes() - item.getReceivedBytes();
        int limit = (int) Math.min(properties.chunkBytes(), remaining);
        if (contentLength > limit) {
            throw new InvalidRequestException("Upload piece is larger than " + limit + " bytes");
        }
        long written = storage.write(item.getStorageKey(), offset, body, limit);
        if (written == 0) throw new InvalidRequestException("Upload piece is empty");
        item.received(offset + written, now());
        return response(repository.saveAndFlush(item));
    }

    /**
     * Verifies size and SHA-256 over every received byte. A mismatch is recorded (custody INTEGRITY_FAILED), the bytes
     * are discarded and 409 is returned so the phone uploads again; that state is committed despite the error.
     */
    @Transactional(noRollbackFor = IntegrityMismatchException.class)
    public EvidenceResponse seal(long userId, long evidenceId) {
        EvidenceItem item = repository.findOwnedForUpdate(evidenceId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        if (item.getStatus() == EvidenceStatus.SEALED) return response(item); // repeating the request is harmless
        requireUploading(item);
        if (item.getReceivedBytes() != item.getSizeBytes()) {
            throw new ConflictException("Upload is not complete: " + item.getReceivedBytes() + " of "
                    + item.getSizeBytes() + " bytes received");
        }
        LocalDateTime now = now();
        EvidenceStorage.Digest digest = storage.digestForSeal(item.getStorageKey());
        if (digest.size() != item.getSizeBytes() || !digest.sha256().equals(item.getCiphertextSha256())) {
            storage.resetPartial(item.getStorageKey());
            item.restartUpload(now);
            repository.saveAndFlush(item);
            ledger.append(userId, item, CustodyAction.INTEGRITY_FAILED, now);
            throw new IntegrityMismatchException();
        }
        storage.seal(item.getStorageKey());
        item.seal(now);
        repository.saveAndFlush(item);
        ledger.append(userId, item, CustodyAction.SEALED, now);
        return response(item);
    }

    // ---- read --------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<EvidenceResponse> list(long userId, long emergencyId) {
        emergencyRepository.findByIdAndUserId(emergencyId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(EMERGENCY_NOT_FOUND));
        return repository.findByEmergencyIdOrderByCapturedStartedAtAscSequenceNoAsc(emergencyId).stream()
                .map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public EvidenceResponse get(long userId, long evidenceId) {
        return response(repository.findOwned(evidenceId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND)));
    }

    /** The sealed ciphertext for the owner. Every download is recorded in the custody ledger. */
    @Transactional
    public Download download(long userId, long evidenceId) {
        EvidenceItem item = repository.findOwnedForUpdate(evidenceId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        if (item.getStatus() != EvidenceStatus.SEALED) {
            throw new ConflictException(item.getStatus() == EvidenceStatus.DELETED
                    ? "This evidence was deleted" : "This evidence is not sealed yet");
        }
        if (!storage.sealedExists(item.getStorageKey())) {
            throw new IllegalStateException("Sealed evidence file is missing");
        }
        ledger.append(userId, item, CustodyAction.DOWNLOADED, now());
        return new Download(storage.sealedFile(item.getStorageKey()), item.getSizeBytes(), item.getCiphertextSha256());
    }

    /**
     * Re-checks the STORED copy of a sealed item: SHA-256 over every byte on disk, compared with the declared hash.
     * Intact: custody VERIFIED. Damaged or missing (disk fault, tampering): custody INTEGRITY_FAILED, the bytes are
     * discarded and the item goes back to UPLOADING, so a phone that still holds it uploads it again. Answers 200 in
     * both cases (the effects must commit); [VerifyResponse.intact] says which. The phone asks for this before it
     * removes its own copy, so a damaged vault copy can never cause the last good copy to be thrown away.
     */
    @Transactional
    public VerifyResponse verify(long userId, long evidenceId) {
        EvidenceItem item = repository.findOwnedForUpdate(evidenceId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        if (item.getStatus() != EvidenceStatus.SEALED) {
            throw new ConflictException(item.getStatus() == EvidenceStatus.DELETED
                    ? "This evidence was deleted" : "This evidence is not sealed yet");
        }
        LocalDateTime now = now();
        boolean intact = storage.digestOfSealed(item.getStorageKey())
                .map(d -> d.size() == item.getSizeBytes() && d.sha256().equals(item.getCiphertextSha256()))
                .orElse(false);
        if (intact) {
            ledger.append(userId, item, CustodyAction.VERIFIED, now);
        } else {
            log.warn("Stored evidence item {} failed its integrity re-check; reopened for upload", item.getId());
            storage.resetPartial(item.getStorageKey());
            item.reopenAfterDamage(now);
            repository.saveAndFlush(item);
            ledger.append(userId, item, CustodyAction.INTEGRITY_FAILED, now);
        }
        return new VerifyResponse(intact, now.toInstant(ZoneOffset.UTC), response(item));
    }

    /** Custody entries of one emergency's items, plus the verification of the person's WHOLE chain. */
    @Transactional(readOnly = true)
    public CustodyReport custody(long userId, long emergencyId) {
        emergencyRepository.findByIdAndUserId(emergencyId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(EMERGENCY_NOT_FOUND));
        Set<Long> items = repository.findByEmergencyIdOrderByCapturedStartedAtAscSequenceNoAsc(emergencyId).stream()
                .map(EvidenceItem::getId).collect(Collectors.toSet());
        CustodyLedger.Verification verification = ledger.verify(userId);
        List<CustodyEntry> entries = verification.entries().stream()
                .filter(e -> items.contains(e.getEvidenceId()))
                .map(CustodyEntry::from)
                .toList();
        String latest = verification.entries().isEmpty() ? null
                : verification.entries().get(verification.entries().size() - 1).getEntryHash();
        return new CustodyReport(verification.intact(), verification.brokenAtSequence(), verification.entries().size(),
                latest, Instant.now(clock), entries);
    }

    // ---- delete ------------------------------------------------------------------------------------

    /**
     * Owner-only and guarded in this order, so nothing about the password is revealed before the rules allow deletion:
     *  1. refused while the emergency is ACTIVE (panic or forced deletion during the incident);
     *  2. refused until [deleteProtection] (24 h) after the emergency ended: whoever forced the person to press
     *     "I'm safe" cannot make them destroy the evidence on the spot, and the uploads have time to finish;
     *  3. locked after [deleteMaxFailures] wrong passwords within [deleteFailureWindow] (429): no online guessing;
     *  4. the password is checked; a wrong one is RECORDED in the chain of custody (DELETE_REFUSED), committed even
     *     though the request fails, so an attempt to destroy evidence leaves a trace.
     * Then a tombstone is kept; the stored file is removed after the commit and the escrow key copy is erased
     * (crypto-shredding: even a backup of the file could no longer be opened through escrow).
     */
    @Transactional(noRollbackFor = WrongPasswordException.class)
    public EvidenceResponse delete(long userId, long evidenceId, String password) {
        EvidenceItem item = repository.findOwnedForUpdate(evidenceId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        if (item.getStatus() == EvidenceStatus.DELETED) return response(item);
        EmergencyEvent emergency = emergencyRepository.findByIdAndUserId(item.getEmergencyId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        if (emergency.getStatus() == EmergencyStatus.ACTIVE) {
            throw new ConflictException("Evidence cannot be deleted while the emergency is active");
        }
        LocalDateTime now = now();
        LocalDateTime ended = emergency.getEndedAt() == null ? now : emergency.getEndedAt();
        if (now.isBefore(ended.plus(properties.deleteProtection()))) {
            throw new ConflictException("Evidence can only be deleted " + describe(properties.deleteProtection())
                    + " after the emergency ended");
        }
        // Locked: concurrent guesses are counted one after the other (same lock order as the custody ledger).
        User user = userRepository.findByIdForUpdate(userId).orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        long recentFailures = ledger.countSince(userId, CustodyAction.DELETE_REFUSED,
                now.minus(properties.deleteFailureWindow()));
        if (recentFailures >= properties.deleteMaxFailures()) {
            throw new TooManyAttemptsException("Too many wrong passwords. Deleting evidence is locked; try again in "
                    + describe(properties.deleteFailureWindow()));
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            ledger.append(userId, item, CustodyAction.DELETE_REFUSED, now);
            throw new WrongPasswordException();
        }
        String storageKey = item.getStorageKey();
        item.markDeleted(now);
        repository.saveAndFlush(item);
        ledger.append(userId, item, CustodyAction.DELETED, now);
        afterCommit(() -> storage.delete(storageKey));
        return response(item);
    }

    // ---- context packet ------------------------------------------------------------------------------

    /** Counts for the OWNER projection of the Emergency Context Packet. The caller has already checked ownership. */
    @Transactional(readOnly = true)
    public Summary summary(long emergencyId) {
        List<EvidenceItem> items = repository.findByEmergencyIdOrderByCapturedStartedAtAscSequenceNoAsc(emergencyId);
        long sealed = items.stream().filter(i -> i.getStatus() == EvidenceStatus.SEALED).count();
        long uploading = items.stream().filter(i -> i.getStatus() == EvidenceStatus.UPLOADING).count();
        long deleted = items.stream().filter(i -> i.getStatus() == EvidenceStatus.DELETED).count();
        return new Summary(items.size(), sealed, uploading, deleted);
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private static void requireUploading(EvidenceItem item) {
        switch (item.getStatus()) {
            case SEALED -> throw new ConflictException("This evidence is already sealed");
            case DELETED -> throw new ConflictException("This evidence was deleted");
            default -> { }
        }
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private EvidenceResponse response(EvidenceItem item) {
        return EvidenceResponse.from(item, properties.chunkBytes());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static LocalDateTime utcMicros(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    public record Declared(EvidenceResponse item, boolean created) {
    }

    public record Download(Path file, long sizeBytes, String ciphertextSha256) {
    }

    public record Summary(long items, long sealed, long uploading, long deleted) {
    }

    /** "24 hours", "15 minutes": for messages the phone shows as they are. */
    static String describe(Duration duration) {
        long minutes = duration.toMinutes();
        if (minutes == 0) {
            long seconds = duration.toSeconds();
            return seconds + (seconds == 1 ? " second" : " seconds");
        }
        if (minutes >= 60 && minutes % 60 == 0) {
            long hours = minutes / 60;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }

    /** 409 for a wrong deletion password; the custody entry recording the attempt is committed. */
    public static class WrongPasswordException extends ConflictException {
        public WrongPasswordException() {
            super("Password is incorrect");
        }
    }

    /** 409 whose effects (reset + custody entry) are committed. */
    public static class IntegrityMismatchException extends ConflictException {
        public IntegrityMismatchException() {
            super("The uploaded bytes do not match the evidence hash; upload it again");
        }
    }
}
