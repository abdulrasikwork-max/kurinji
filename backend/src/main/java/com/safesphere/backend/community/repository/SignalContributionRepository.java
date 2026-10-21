package com.safesphere.backend.community.repository;

import com.safesphere.backend.community.entity.ContributionKind;
import com.safesphere.backend.community.entity.SignalCategory;
import com.safesphere.backend.community.entity.SignalContribution;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SignalContributionRepository extends JpaRepository<SignalContribution, Long> {

    List<SignalContribution> findBySignalIdIn(Collection<Long> signalIds);

    List<SignalContribution> findBySignalId(Long signalId);

    Optional<SignalContribution> findBySignalIdAndUserId(Long signalId, Long userId);

    List<SignalContribution> findByUserId(Long userId);

    long countByUserIdAndCountsFromAfter(Long userId, LocalDateTime since);

    long countByUserIdAndKindAndCountsFromAfter(Long userId, ContributionKind kind, LocalDateTime since);

    @Query("select count(c) from SignalContribution c, PlaceSignal s where c.signalId = s.id and c.userId = :userId "
            + "and c.kind = :kind and s.areaCell = :areaCell and c.countsFrom > :since")
    long countInArea(@Param("userId") Long userId, @Param("kind") ContributionKind kind,
                     @Param("areaCell") String areaCell, @Param("since") LocalDateTime since);

    @Query("select distinct c.signalId from SignalContribution c where c.kind = :kind")
    List<Long> signalIdsWithKind(@Param("kind") ContributionKind kind);

    /** Removes contributions of one category whose lifetime is over (minimum retention). */
    @Modifying
    @Query("delete from SignalContribution c where c.countsFrom <= :cutoff and c.signalId in "
            + "(select s.id from PlaceSignal s where s.category = :category)")
    int deleteExpired(@Param("category") SignalCategory category, @Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Query("delete from SignalContribution c where c.signalId in :signalIds")
    int deleteBySignalIds(@Param("signalIds") Collection<Long> signalIds);

    // ---- Phase 7: independence (who keeps acting together) ----------------------------------------------------------

    /**
     * Pairs among [userIds] that stood on the SAME side ("it is like this" = REPORT/CONFIRM) of at least [min] live
     * signals. Rows: [userA, userB, shared], userA < userB. Uses only live rows, so it fades with normal retention.
     */
    @Query("select c1.userId, c2.userId, count(c1) from SignalContribution c1, SignalContribution c2 "
            + "where c1.signalId = c2.signalId and c1.userId < c2.userId "
            + "and c1.userId in :userIds and c2.userId in :userIds "
            + "and c1.kind <> :disputeKind and c2.kind <> :disputeKind "
            + "group by c1.userId, c2.userId having count(c1) >= :min")
    List<Object[]> sharedSupportPairs(@Param("userIds") Collection<Long> userIds,
                                      @Param("disputeKind") ContributionKind disputeKind, @Param("min") long min);

    /** Same, for "no longer true" answers (coordinated retirement). */
    @Query("select c1.userId, c2.userId, count(c1) from SignalContribution c1, SignalContribution c2 "
            + "where c1.signalId = c2.signalId and c1.userId < c2.userId "
            + "and c1.userId in :userIds and c2.userId in :userIds "
            + "and c1.kind = :disputeKind and c2.kind = :disputeKind "
            + "group by c1.userId, c2.userId having count(c1) >= :min")
    List<Object[]> sharedDisputePairs(@Param("userIds") Collection<Long> userIds,
                                      @Param("disputeKind") ContributionKind disputeKind, @Param("min") long min);

    /** Live supporting contributions per user. Rows: [userId, count]. */
    @Query("select c.userId, count(c) from SignalContribution c where c.userId in :userIds and c.kind <> :disputeKind "
            + "group by c.userId")
    List<Object[]> supportTotals(@Param("userIds") Collection<Long> userIds,
                                 @Param("disputeKind") ContributionKind disputeKind);

    /** Live "no longer true" answers per user. Rows: [userId, count]. */
    @Query("select c.userId, count(c) from SignalContribution c where c.userId in :userIds and c.kind = :disputeKind "
            + "group by c.userId")
    List<Object[]> disputeTotals(@Param("userIds") Collection<Long> userIds,
                                 @Param("disputeKind") ContributionKind disputeKind);

    /** Signals with at least one REPORT not yet credited (the clean-up checks them for independent support). */
    @Query("select distinct c.signalId from SignalContribution c where c.kind = :kind and c.credited = false")
    List<Long> signalIdsWithUncreditedReports(@Param("kind") ContributionKind kind);

    /** Signals with at least two contributions (candidates for the coordination check). */
    @Query("select c.signalId from SignalContribution c group by c.signalId having count(c) >= 2")
    List<Long> signalIdsWithSeveralContributions();
}
