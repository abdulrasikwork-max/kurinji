package com.safesphere.backend.moderation.repository;

import com.safesphere.backend.moderation.entity.ContentReport;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Phase 13D: reports. Nothing here is ever read for anyone but the reporter themself or a moderator. */
public interface ContentReportRepository extends JpaRepository<ContentReport, Long> {

    List<ContentReport> findByCaseIdOrderByCreatedAtAscIdAsc(long caseId);

    List<ContentReport> findByCaseIdIn(Collection<Long> caseIds);

    boolean existsByReporterIdAndPostId(long reporterId, long postId);

    boolean existsByReporterIdAndCommentId(long reporterId, long commentId);

    boolean existsByReporterIdAndPersonId(long reporterId, long personId);

    /** The reporter's own list, newest first. */
    List<ContentReport> findByReporterIdOrderByCreatedAtDescIdDesc(long reporterId, Pageable page);

    /**
     * M5 independence: rows (reporterA, reporterB, shared) for pairs among [ids] that reported the same target (post,
     * perspective or person). Feeds community.service.Independence.fromRows, the Phase 7 rule.
     */
    @Query("select a.reporterId, b.reporterId, count(a) from ContentReport a, ContentReport b"
            + " where a.reporterId in :ids and b.reporterId in :ids and a.reporterId < b.reporterId"
            + " and (a.postId = b.postId or a.commentId = b.commentId or a.personId = b.personId)"
            + " group by a.reporterId, b.reporterId")
    List<Object[]> sharedTargets(@Param("ids") Collection<Long> ids);

    /** Rows (reporter, reports) for [ids]: everyone's live reports, the denominator of the independence rule. */
    @Query("select r.reporterId, count(r) from ContentReport r where r.reporterId in :ids group by r.reporterId")
    List<Object[]> totals(@Param("ids") Collection<Long> ids);

    @Modifying
    @Query("delete from ContentReport r where r.caseId in :caseIds")
    int deleteByCaseIds(@Param("caseIds") Collection<Long> caseIds);

    /** Reports whose case is gone (MySQL cascades; this keeps other databases equally tidy). */
    @Modifying
    @Query("delete from ContentReport r where not exists (select 1 from ContentCase c where c.id = r.caseId)")
    int deleteOrphans();
}
