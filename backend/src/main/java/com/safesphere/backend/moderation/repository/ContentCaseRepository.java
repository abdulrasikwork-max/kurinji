package com.safesphere.backend.moderation.repository;

import com.safesphere.backend.moderation.entity.ContentCase;
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

/** Phase 13D: review cases about moments (N4) and people. */
public interface ContentCaseRepository extends JpaRepository<ContentCase, Long> {

    Optional<ContentCase> findByPublicId(String publicId);

    /** Locks the case row for one moderator decision (SELECT ... FOR UPDATE). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ContentCase c where c.publicId = :publicId")
    Optional<ContentCase> findForUpdateByPublicId(@Param("publicId") String publicId);

    Optional<ContentCase> findByMomentIdAndStatus(Long momentId, CaseStatus status);

    boolean existsByMomentIdAndStatus(Long momentId, CaseStatus status);

    Optional<ContentCase> findByMessageIdAndStatus(Long messageId, CaseStatus status);

    boolean existsByMessageIdAndStatus(Long messageId, CaseStatus status);

    /** Phase N5: cases about messages that are going (with their moment, their writer or the response purge). */
    @Modifying
    @Query("delete from ContentCase c where c.messageId in :messageIds")
    int deleteByMessageIds(@Param("messageIds") Collection<Long> messageIds);

    @Query("select c.id from ContentCase c where c.messageId in :messageIds")
    List<Long> idsByMessageIds(@Param("messageIds") Collection<Long> messageIds);

    Optional<ContentCase> findByPersonIdAndStatus(Long personId, CaseStatus status);

    List<ContentCase> findByStatusOrderByOpenedAtAsc(CaseStatus status, Pageable page);

    List<ContentCase> findByIdIn(Collection<Long> ids);

    /** Cases whose target is gone. MySQL removes them by ON DELETE CASCADE; this keeps other databases equally tidy. */
    @Modifying
    @Query("delete from ContentCase c where (c.momentId is not null and not exists "
            + "(select 1 from com.safesphere.backend.social.entity.Moment m where m.id = c.momentId))"
            + " or (c.personId is not null and not exists "
            + "(select 1 from com.safesphere.backend.user.entity.User u where u.id = c.personId))"
            + " or (c.messageId is not null and not exists "
            + "(select 1 from com.safesphere.backend.social.entity.Message g where g.id = c.messageId))")
    int deleteOrphans();

    /** Retention: closed cases are kept for a while (appeals, the reporters' list), then deleted with their reports. */
    @Query("select c.id from ContentCase c where c.status = :closed and c.closedAt <= :cutoff")
    List<Long> closedBefore(@Param("closed") CaseStatus closed, @Param("cutoff") LocalDateTime cutoff);
}
