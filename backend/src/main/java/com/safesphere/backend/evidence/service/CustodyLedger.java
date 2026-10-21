package com.safesphere.backend.evidence.service;

import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.evidence.entity.CustodyAction;
import com.safesphere.backend.evidence.entity.CustodyEvent;
import com.safesphere.backend.evidence.entity.EvidenceItem;
import com.safesphere.backend.evidence.repository.CustodyEventRepository;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The chain of custody (Phase 5): an append-only, hash-chained ledger per person.
 *
 * Each entry's hash covers its own fields AND the previous entry's hash, like the blocks of a blockchain but with a
 * single writer (this server) and no mining. Changing, deleting or re-ordering any past entry changes its hash, so
 * every later "previous_hash" no longer matches and {@link #verify} reports the first broken entry. It does not stop
 * someone with full database access from rewriting the WHOLE chain; it makes silent partial edits detectable, and the
 * newest entry hash can be noted outside the system (for example in a report) to pin the history up to that point.
 */
@Component
public class CustodyLedger {

    private static final DateTimeFormatter MICROS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    private final CustodyEventRepository repository;
    private final UserRepository userRepository;

    public CustodyLedger(CustodyEventRepository repository, UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    /** Joins the caller's transaction (MANDATORY): the custody entry is written together with the change it records. */
    @Transactional(propagation = Propagation.MANDATORY)
    public CustodyEvent append(long userId, EvidenceItem item, CustodyAction action, LocalDateTime time) {
        // The database keeps microseconds: hash exactly what is stored, or verification would fail after a reload.
        LocalDateTime now = time.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        // Serialises appends per person, so sequence numbers have no gaps and the chain never forks.
        userRepository.findByIdForUpdate(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        CustodyEvent last = repository.findFirstByUserIdOrderBySequenceNoDesc(userId).orElse(null);
        long sequence = last == null ? 1 : last.getSequenceNo() + 1;
        String previous = last == null ? Sha256.ZERO : last.getEntryHash();
        String hash = hashOf(previous, userId, sequence, item.getId(), action, item.getCiphertextSha256(), now);
        return repository.save(new CustodyEvent(userId, item.getId(), sequence, action, item.getCiphertextSha256(), now,
                previous, hash));
    }

    /** How many entries of [action] the person has since [since] (call with the person's row locked to count exactly). */
    @Transactional(propagation = Propagation.MANDATORY)
    public long countSince(long userId, CustodyAction action, LocalDateTime since) {
        return repository.countByUserIdAndActionAndOccurredAtAfter(userId, action, since);
    }

    /** Recomputes the whole chain of one person. */
    @Transactional(readOnly = true)
    public Verification verify(long userId) {
        List<CustodyEvent> entries = repository.findByUserIdOrderBySequenceNoAsc(userId);
        String previous = Sha256.ZERO;
        long expectedSequence = 1;
        for (CustodyEvent entry : entries) {
            String recomputed = hashOf(entry.getPreviousHash(), userId, entry.getSequenceNo(), entry.getEvidenceId(),
                    entry.getAction(), entry.getCiphertextSha256(), entry.getOccurredAt());
            if (entry.getSequenceNo() != expectedSequence || !entry.getPreviousHash().equals(previous)
                    || !entry.getEntryHash().equals(recomputed)) {
                return new Verification(false, entries, entry.getSequenceNo());
            }
            previous = entry.getEntryHash();
            expectedSequence++;
        }
        return new Verification(true, entries, null);
    }

    /** The exact text that is hashed. Field order and formats are part of the ledger format: never change them. */
    static String hashOf(String previousHash, long userId, long sequence, long evidenceId, CustodyAction action,
                         String ciphertextSha256, LocalDateTime occurredAt) {
        return Sha256.ofText(String.join("|", "SSCL1", previousHash, Long.toString(userId), Long.toString(sequence),
                Long.toString(evidenceId), action.name(), ciphertextSha256, occurredAt.format(MICROS)));
    }

    /** [brokenAtSequence] is the first entry that does not verify (null when intact). */
    public record Verification(boolean intact, List<CustodyEvent> entries, Long brokenAtSequence) {
    }
}
