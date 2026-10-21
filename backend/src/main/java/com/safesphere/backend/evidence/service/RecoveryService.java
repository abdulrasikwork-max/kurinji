package com.safesphere.backend.evidence.service;

import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.common.exception.TooManyAttemptsException;
import com.safesphere.backend.config.EvidenceProperties;
import com.safesphere.backend.evidence.dto.EvidenceDtos.AttachRecoveryKeyRequest;
import com.safesphere.backend.evidence.dto.EvidenceDtos.EvidenceResponse;
import com.safesphere.backend.evidence.dto.EvidenceDtos.RecoverableItem;
import com.safesphere.backend.evidence.dto.EvidenceDtos.RecoverableItemsPage;
import com.safesphere.backend.evidence.dto.EvidenceDtos.RecoveryEnvelopeResponse;
import com.safesphere.backend.evidence.dto.EvidenceDtos.RecoveryStatusResponse;
import com.safesphere.backend.evidence.dto.EvidenceDtos.SetRecoveryRequest;
import com.safesphere.backend.evidence.entity.EvidenceItem;
import com.safesphere.backend.evidence.entity.EvidenceStatus;
import com.safesphere.backend.evidence.entity.RecoveryEnvelope;
import com.safesphere.backend.evidence.entity.RecoveryFailure;
import com.safesphere.backend.evidence.repository.EvidenceItemRepository;
import com.safesphere.backend.evidence.repository.RecoveryEnvelopeRepository;
import com.safesphere.backend.evidence.repository.RecoveryFailureRepository;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Restore my evidence on a new phone" (Phase 5, V11). The phone makes a random recovery key (RK), wraps every item key
 * with it, and wraps the RK with a key derived from a passphrase only the person knows (the "envelope"). This service
 * only STORES those opaque values and hands them back to the owner: it never receives the passphrase, the RK or an
 * item key, so it cannot open evidence (short of guessing the passphrase offline; see docs/PHASE5.md §11).
 *
 * Rules:
 *  - setting/replacing and fetching the envelope re-check the ACCOUNT password (a stolen login token alone is not
 *    enough to obtain guessing material); wrong passwords are counted and lock both after
 *    [EvidenceProperties#deleteMaxFailures] within [EvidenceProperties#deleteFailureWindow] (429);
 *  - formats are pinned (PBKDF2-SHA256, 600 000 to 10 000 000 iterations, 16-32-byte salt, 60-byte GCM wraps);
 *  - the RK generation cannot change yet (a "reset" is deferred), so every item wrap stays openable by the one RK;
 *  - an item's recovery copy is write-once per RK generation (a repeated attach answers the existing state).
 */
@Service
public class RecoveryService {

    public static final int MIN_ITERATIONS = 600_000;
    public static final int MAX_ITERATIONS = 10_000_000;
    public static final int WRAPPED_KEY_BYTES = 60; // 12-byte IV + 32-byte key + 16-byte GCM tag
    public static final int RECOVERY_PAGE_SIZE = 200;
    private static final String NOT_FOUND = "Evidence not found";
    private static final String NO_ENVELOPE = "No recovery passphrase is set up";

    private final RecoveryEnvelopeRepository envelopes;
    private final RecoveryFailureRepository failures;
    private final EvidenceItemRepository items;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final EvidenceProperties properties;
    private final Clock clock;

    public RecoveryService(RecoveryEnvelopeRepository envelopes, RecoveryFailureRepository failures,
                           EvidenceItemRepository items, UserRepository users, PasswordEncoder passwordEncoder,
                           EvidenceProperties properties, Clock clock) {
        this.envelopes = envelopes;
        this.failures = failures;
        this.items = items;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public RecoveryStatusResponse status(long userId) {
        return envelopes.findByUserId(userId)
                .map(e -> new RecoveryStatusResponse(true, e.getRkVersion(), e.getEnvelopeVersion(), utc(e.getUpdatedAt()),
                        utc(e.getLastDownloadedAt())))
                .orElse(new RecoveryStatusResponse(false, null, null, null, null));
    }

    /** Creates or replaces the envelope (a new passphrase for the same RK). */
    @Transactional(noRollbackFor = EvidenceService.WrongPasswordException.class)
    public RecoveryStatusResponse set(long userId, SetRecoveryRequest request) {
        checkPassword(userId, request.password());
        if (!RecoveryEnvelope.KDF_PBKDF2_SHA256.equals(request.kdf())) {
            throw new InvalidRequestException("Unsupported key derivation");
        }
        if (request.kdfIterations() < MIN_ITERATIONS || request.kdfIterations() > MAX_ITERATIONS) {
            throw new InvalidRequestException("Iterations must be between " + MIN_ITERATIONS + " and " + MAX_ITERATIONS);
        }
        byte[] salt = base64(request.kdfSalt(), "Salt");
        if (salt.length < 16 || salt.length > 32) throw new InvalidRequestException("Salt must be 16 to 32 bytes");
        byte[] wrapped = base64(request.wrappedRecoveryKey(), "Wrapped recovery key");
        if (wrapped.length != WRAPPED_KEY_BYTES) {
            throw new InvalidRequestException("Wrapped recovery key has the wrong length");
        }
        LocalDateTime now = now();
        RecoveryEnvelope current = envelopes.findByUserId(userId).orElse(null);
        if (current == null) {
            if (request.expectedEnvelopeVersion() != null) throw new ConflictException(NO_ENVELOPE);
            if (request.rkVersion() != 1) throw new InvalidRequestException("A new recovery key starts at version 1");
            envelopes.saveAndFlush(new RecoveryEnvelope(userId, 1, request.kdf(), request.kdfIterations(), salt, wrapped, now));
        } else {
            if (request.expectedEnvelopeVersion() == null
                    || request.expectedEnvelopeVersion() != current.getEnvelopeVersion()) {
                throw new ConflictException("The recovery passphrase was changed elsewhere; check it again");
            }
            if (request.rkVersion() != current.getRkVersion()) {
                throw new InvalidRequestException("The recovery key cannot be replaced");
            }
            current.replace(current.getRkVersion(), request.kdf(), request.kdfIterations(), salt, wrapped, now);
            envelopes.saveAndFlush(current);
        }
        return status(userId);
    }

    /** The envelope for a phone that knows the passphrase. Records when it was handed out. */
    @Transactional(noRollbackFor = EvidenceService.WrongPasswordException.class)
    public RecoveryEnvelopeResponse envelope(long userId, String password) {
        checkPassword(userId, password);
        RecoveryEnvelope envelope = envelopes.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(NO_ENVELOPE));
        envelope.downloaded(now());
        envelopes.saveAndFlush(envelope);
        Base64.Encoder b64 = Base64.getEncoder();
        return new RecoveryEnvelopeResponse(envelope.getRkVersion(), envelope.getEnvelopeVersion(), envelope.getKdf(),
                envelope.getKdfIterations(), b64.encodeToString(envelope.getKdfSalt()),
                b64.encodeToString(envelope.getWrappedRecoveryKey()), utc(envelope.getUpdatedAt()));
    }

    /** Attaches an item's recovery copy (back-fill of items declared before the passphrase was set). Write-once. */
    @Transactional
    public EvidenceResponse attach(long userId, long evidenceId, AttachRecoveryKeyRequest request) {
        EvidenceItem item = items.findOwnedForUpdate(evidenceId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        if (item.getStatus() == EvidenceStatus.DELETED) throw new ConflictException("This evidence was deleted");
        byte[] wrapped = checkedItemWrap(userId, request.rkVersion(), request.wrappedKey());
        if (!java.util.Objects.equals(item.getRecoveryRkVersion(), request.rkVersion())) {
            item.attachRecoveryKey(request.rkVersion(), wrapped, now());
            items.saveAndFlush(item);
        }
        return EvidenceResponse.from(item, properties.chunkBytes());
    }

    /** Pages of the person's SEALED items that carry a recovery copy, oldest first. */
    @Transactional(readOnly = true)
    public RecoverableItemsPage recoverable(long userId, int page) {
        if (page < 0) throw new InvalidRequestException("Page must not be negative");
        List<EvidenceItem> found = items.findRecoverable(userId, EvidenceStatus.SEALED,
                PageRequest.of(page, RECOVERY_PAGE_SIZE + 1));
        boolean hasMore = found.size() > RECOVERY_PAGE_SIZE;
        Base64.Encoder b64 = Base64.getEncoder();
        List<RecoverableItem> result = found.stream().limit(RECOVERY_PAGE_SIZE)
                .map(i -> new RecoverableItem(EvidenceResponse.from(i, properties.chunkBytes()), i.getRecoveryRkVersion(),
                        b64.encodeToString(i.getRecoveryWrappedKey())))
                .toList();
        return new RecoverableItemsPage(result, page, hasMore);
    }

    /**
     * Checks a recovery copy offered with a declaration or an attach: the person must have an envelope, the RK
     * generation must be the current one (409 so the phone fetches it again), and the length must be a GCM wrap.
     */
    byte[] checkedItemWrap(long userId, int rkVersion, String wrappedBase64) {
        RecoveryEnvelope envelope = envelopes.findByUserId(userId)
                .orElseThrow(() -> new ConflictException(NO_ENVELOPE));
        if (envelope.getRkVersion() != rkVersion) {
            throw new ConflictException("The recovery key has changed; fetch it again");
        }
        byte[] wrapped = base64(wrappedBase64, "Recovery key");
        if (wrapped.length != WRAPPED_KEY_BYTES) throw new InvalidRequestException("Recovery key has the wrong length");
        return wrapped;
    }

    /** Same lock order and policy as evidence deletion: lock the person, refuse while locked, record a wrong one. */
    private void checkPassword(long userId, String password) {
        User user = users.findByIdForUpdate(userId).orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        LocalDateTime now = now();
        long recent = failures.countByUserIdAndOccurredAtAfter(userId, now.minus(properties.deleteFailureWindow()));
        if (recent >= properties.deleteMaxFailures()) {
            throw new TooManyAttemptsException("Too many wrong passwords. Recovery is locked; try again in "
                    + EvidenceService.describe(properties.deleteFailureWindow()));
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            failures.saveAndFlush(new RecoveryFailure(userId, now));
            throw new EvidenceService.WrongPasswordException();
        }
    }

    private static byte[] base64(String value, String what) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(what + " is not valid base64");
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static Instant utc(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}
