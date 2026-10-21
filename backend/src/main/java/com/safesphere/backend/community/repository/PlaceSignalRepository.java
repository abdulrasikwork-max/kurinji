package com.safesphere.backend.community.repository;

import com.safesphere.backend.community.entity.ContributionKind;
import com.safesphere.backend.community.entity.ModerationState;
import com.safesphere.backend.community.entity.PlaceSignal;
import com.safesphere.backend.community.entity.SignalCategory;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Signals carry no owner; who may see or change what is decided in CommunityService. */
public interface PlaceSignalRepository extends JpaRepository<PlaceSignal, Long> {

    Optional<PlaceSignal> findByPublicId(String publicId);

    Optional<PlaceSignal> findByCellAndCategory(String cell, SignalCategory category);

    /** Phase 9D: signals of one category in these blocks (the side neighbours of a new report). */
    List<PlaceSignal> findByCellInAndCategory(Collection<String> cells, SignalCategory category);

    /**
     * Phase 9D: LONE reports, candidates to join the signal next door: unmoderated signals of these categories whose
     * only contribution is a REPORT that already counts (its delay and probation are over), and that were never
     * confirmed (a once-confirmed signal is a possible pattern episode, Phase 8C: it stays where it is).
     */
    @Query("select s from PlaceSignal s where s.moderation = :none and s.reachedConfirmed = false "
            + "and s.category in :categories "
            + "and (select count(c) from SignalContribution c where c.signalId = s.id) = 1 "
            + "and exists (select 1 from SignalContribution c where c.signalId = s.id and c.kind = :report "
            + "and c.countsFrom <= :now)")
    List<PlaceSignal> findLoneReports(@Param("none") ModerationState none,
                                      @Param("categories") Collection<SignalCategory> categories,
                                      @Param("report") ContributionKind report, @Param("now") LocalDateTime now);

    List<PlaceSignal> findByAreaCellIn(Collection<String> areaCells);

    /** Phase 7: removed signals (tombstones) and quarantined ones. */
    List<PlaceSignal> findByModeration(ModerationState moderation);

    /** Phase 8C: visible-able signals not yet marked as confirmed (checked by the clean-up job). */
    List<PlaceSignal> findByModerationAndReachedConfirmedFalse(ModerationState moderation);

    /**
     * Phase 8C: confirmed, unmoderated signals that nobody contributes to any more: their lifetime ended naturally,
     * so each is a pattern episode just before [deleteEmpty] removes it.
     */
    @Query("select s from PlaceSignal s where not exists "
            + "(select 1 from SignalContribution c where c.signalId = s.id) "
            + "and s.moderation = :none and s.reachedConfirmed = true")
    List<PlaceSignal> findEndedConfirmed(@Param("none") ModerationState none);

    /** Phase 9B: exactly the rows [deleteEmpty] is about to delete (read first, to count how they ended). */
    @Query("select s from PlaceSignal s where not exists "
            + "(select 1 from SignalContribution c where c.signalId = s.id) "
            + "and (s.moderation <> :removed or s.moderationUntil <= :now)")
    List<PlaceSignal> findEmpty(@Param("removed") ModerationState removed, @Param("now") LocalDateTime now);

    /**
     * Signals nobody contributes to any more (after their contributions expired or were withdrawn). A removed
     * signal's tombstone (Phase 7) is kept until its hold ends, even though it has no contributions.
     */
    @Modifying
    @Query("delete from PlaceSignal s where not exists "
            + "(select 1 from SignalContribution c where c.signalId = s.id) "
            + "and (s.moderation <> :removed or s.moderationUntil <= :now)")
    int deleteEmpty(@Param("removed") ModerationState removed, @Param("now") LocalDateTime now);
}
