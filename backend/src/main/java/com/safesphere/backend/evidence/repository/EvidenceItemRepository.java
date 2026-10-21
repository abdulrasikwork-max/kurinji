package com.safesphere.backend.evidence.repository;

import com.safesphere.backend.evidence.entity.EvidenceItem;
import com.safesphere.backend.evidence.entity.EvidenceStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Evidence has no user_id: every lookup joins the emergency and filters by its owner, so another person's item is
 * simply "not found" (404), exactly like a missing one.
 */
public interface EvidenceItemRepository extends JpaRepository<EvidenceItem, Long> {

    String OWNED = "select i from EvidenceItem i where i.id = :id and i.emergencyId in "
            + "(select e.id from EmergencyEvent e where e.userId = :userId)";

    @Query(OWNED)
    Optional<EvidenceItem> findOwned(@Param("id") Long id, @Param("userId") Long userId);

    /** Same lookup, locked until the transaction ends: two chunk uploads for one item run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(OWNED)
    Optional<EvidenceItem> findOwnedForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    Optional<EvidenceItem> findByClientRef(String clientRef);

    long countByEmergencyId(Long emergencyId);

    List<EvidenceItem> findByEmergencyIdOrderByCapturedStartedAtAscSequenceNoAsc(Long emergencyId);

    /** Bytes the person keeps in the vault (deleted items do not count), for the per-user quota. */
    @Query("select coalesce(sum(i.sizeBytes), 0) from EvidenceItem i where i.status <> :deleted and i.emergencyId in "
            + "(select e.id from EmergencyEvent e where e.userId = :userId)")
    long storedBytes(@Param("userId") Long userId, @Param("deleted") EvidenceStatus deleted);

    /** Storage keys still referenced (used by the start-up reconciliation of the storage folder). */
    @Query("select i.storageKey from EvidenceItem i where i.storageKey is not null")
    List<String> findAllStorageKeys();

    List<EvidenceItem> findByStatus(EvidenceStatus status);

    /** Phase 10A.3: storage keys of every file a person's evidence still has (account deletion removes them first). */
    @Query("select i.storageKey from EvidenceItem i where i.storageKey is not null and i.emergencyId in "
            + "(select e.id from EmergencyEvent e where e.userId = :userId)")
    List<String> storageKeysOf(@Param("userId") Long userId);

    /** The person's items that a new phone can restore: in [status] (SEALED) and holding a recovery-wrapped key. */
    @Query("select i from EvidenceItem i where i.status = :status and i.recoveryWrappedKey is not null and i.emergencyId in "
            + "(select e.id from EmergencyEvent e where e.userId = :userId) order by i.id asc")
    List<EvidenceItem> findRecoverable(@Param("userId") Long userId, @Param("status") EvidenceStatus status,
                                       org.springframework.data.domain.Pageable page);
}
