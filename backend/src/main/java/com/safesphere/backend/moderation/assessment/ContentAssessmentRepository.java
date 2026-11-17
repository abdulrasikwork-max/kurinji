package com.safesphere.backend.moderation.assessment;

import com.safesphere.backend.moderation.assessment.ContentAssessment.Status;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Phase 13E: the automatic checks (one per moment). */
public interface ContentAssessmentRepository extends JpaRepository<ContentAssessment, Long> {

    Optional<ContentAssessment> findByMomentId(Long momentId);

    /** Checks whose moment is gone (MySQL cascades; this keeps other databases equally tidy). */
    @Modifying
    @Query("delete from ContentAssessment a where not exists "
            + "(select 1 from com.safesphere.backend.social.entity.Moment m where m.id = a.momentId)")
    int deleteOrphans();

    /** Checks to try again now, oldest first. */
    @Query("select a.id from ContentAssessment a where a.status = :pending and a.nextAttemptAt <= :now "
            + "order by a.nextAttemptAt asc, a.id asc")
    List<Long> dueForRetry(@Param("pending") Status pending, @Param("now") LocalDateTime now, Pageable page);

    /**
     * Retention (E25): finished checks older than [cutoff], except while their content has an OPEN case, or a decision
     * on a case about it is waiting for an appeal. Deleted with their content anyway (cascade).
     */
    @Modifying
    @Query("delete from ContentAssessment a where a.status <> :pending and a.createdAt < :cutoff"
            + " and not exists (select c.id from ContentCase c where c.status = com.safesphere.backend.moderation.entity"
            + ".ModerationEnums.CaseStatus.OPEN and c.momentId = a.momentId)"
            + " and not exists (select p.id from ModerationAppeal p, ModerationAction x, ContentCase c"
            + " where p.status = com.safesphere.backend.moderation.entity.ModerationEnums.AppealStatus.OPEN"
            + " and p.actionId = x.id and x.caseRef = c.publicId and c.momentId = a.momentId)")
    int deleteExpired(@Param("pending") Status pending, @Param("cutoff") LocalDateTime cutoff);
}
