package com.safesphere.backend.community.repository;

import com.safesphere.backend.community.entity.SignalFlag;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Abuse flags (Phase 7). Never returned to anyone as rows; moderators see counts per reason. */
public interface SignalFlagRepository extends JpaRepository<SignalFlag, Long> {

    List<SignalFlag> findBySignalId(Long signalId);

    List<SignalFlag> findBySignalIdIn(Collection<Long> signalIds);

    Optional<SignalFlag> findBySignalIdAndUserId(Long signalId, Long userId);

    long countByUserIdAndCountsFromAfter(Long userId, LocalDateTime since);

    /** Signals with unresolved flags (the clean-up re-evaluates them when flags of new accounts start counting). */
    @Query("select distinct f.signalId from SignalFlag f where f.resolved = false")
    List<Long> signalIdsWithOpenFlags();

    /**
     * How often two flaggers flagged the SAME signals (for the independence rule): pairs among [userIds] that share at
     * least [min] live flags. Rows: [userA, userB, shared] with userA < userB.
     */
    @Query("select f1.userId, f2.userId, count(f1) from SignalFlag f1, SignalFlag f2 "
            + "where f1.signalId = f2.signalId and f1.userId < f2.userId "
            + "and f1.userId in :userIds and f2.userId in :userIds "
            + "group by f1.userId, f2.userId having count(f1) >= :min")
    List<Object[]> sharedFlagPairs(@Param("userIds") Collection<Long> userIds, @Param("min") long min);

    /** Flags whose signal is gone (MySQL cascades; this keeps other databases equally tidy). */
    @Modifying
    @Query("delete from SignalFlag f where not exists (select 1 from PlaceSignal s where s.id = f.signalId)")
    int deleteOrphans();

    /** Live flags per user, for the independence rule's "share of all their activity". Rows: [userId, count]. */
    @Query("select f.userId, count(f) from SignalFlag f where f.userId in :userIds group by f.userId")
    List<Object[]> flagTotals(@Param("userIds") Collection<Long> userIds);
}
