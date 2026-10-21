package com.safesphere.backend.moderation.repository;

import com.safesphere.backend.moderation.entity.ModerationCase;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseOrigin;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseOutcome;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ModerationCaseRepository extends JpaRepository<ModerationCase, Long> {

    Optional<ModerationCase> findByPublicId(String publicId);

    /** Locks the case row for one moderator decision (SELECT ... FOR UPDATE). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ModerationCase c where c.publicId = :publicId")
    Optional<ModerationCase> findForUpdateByPublicId(@Param("publicId") String publicId);

    Optional<ModerationCase> findBySignalIdAndStatus(Long signalId, CaseStatus status);

    /** Whether a moderator already closed a case of this origin about this signal with this outcome. */
    boolean existsBySignalIdAndOriginAndOutcome(Long signalId, CaseOrigin origin, CaseOutcome outcome);

    /** Phase 9C: the same for any of a group's signals (a dismissed group is not asked about again). */
    boolean existsBySignalIdInAndOriginAndOutcome(Collection<Long> signalIds, CaseOrigin origin, CaseOutcome outcome);

    /**
     * Phase 9C.1 (S15 Option B): rows (signalId, latest closedAt) of the signals among [signalIds] with a case of this
     * origin closed with this outcome. Read by TrustService on every snapshot; the closed case row is the only mark.
     */
    @Query("select c.signalId, max(c.closedAt) from ModerationCase c where c.signalId in :signalIds "
            + "and c.origin = :origin and c.outcome = :outcome group by c.signalId")
    List<Object[]> lastClosedBySignal(@Param("signalIds") Collection<Long> signalIds, @Param("origin") CaseOrigin origin,
                                      @Param("outcome") CaseOutcome outcome);

    /** Phase 9D: which of these signals have a case in this status (one query for the neighbour join). */
    @Query("select distinct c.signalId from ModerationCase c where c.signalId in :signalIds and c.status = :status")
    List<Long> signalIdsWithStatus(@Param("signalIds") Collection<Long> signalIds, @Param("status") CaseStatus status);

    /** Phase 9B: the cases in this status about these signals (signals about to be deleted: unreviewed cases). */
    List<ModerationCase> findBySignalIdInAndStatus(Collection<Long> signalIds, CaseStatus status);

    /** Phase 9C: whether any of a group's signals is already in front of a moderator. */
    boolean existsBySignalIdInAndStatus(Collection<Long> signalIds, CaseStatus status);

    /** Phase 9C: cases of this origin opened since [since] (the coordination budget). */
    long countByOriginAndOpenedAtAfter(CaseOrigin origin, LocalDateTime since);

    List<ModerationCase> findByStatusOrderByOpenedAtAsc(CaseStatus status, Pageable page);

    /** Cases whose signal is gone. MySQL removes them by ON DELETE CASCADE; this keeps other databases equally tidy. */
    @Modifying
    @Query("delete from ModerationCase c where not exists "
            + "(select 1 from com.safesphere.backend.community.entity.PlaceSignal s where s.id = c.signalId)")
    int deleteOrphans();

    /** Retention: closed cases are kept for a while (re-review, appeals), then deleted. */
    @Modifying
    @Query("delete from ModerationCase c where c.status = :closed and c.closedAt <= :cutoff")
    int deleteClosedBefore(@Param("closed") CaseStatus closed, @Param("cutoff") LocalDateTime cutoff);
}
