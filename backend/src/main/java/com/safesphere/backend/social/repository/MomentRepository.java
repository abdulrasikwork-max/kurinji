package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.Moment;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Phase N4: moments. The Today queries are only pre-filters: every moment still passes the one Visibility rule
 * (Relationships.forToday) before it is shown. Today is ordered by when others could first see a moment (visible_at),
 * never by when it was written, and paged by keyset strictly before a cursor (visible_at, id); nothing is ranked.
 */
public interface MomentRepository extends JpaRepository<Moment, Long> {

    String LIVE = "m.communityId = :community AND m.visibleAt <= :now AND m.expiresAt > :now"
            + " AND m.moderation IN :states AND m.authorId NOT IN :hidden";
    String BEFORE_CURSOR = "(m.visibleAt < :cursorAt OR (m.visibleAt = :cursorAt AND m.id < :cursorId))";
    String NEWEST_FIRST = " ORDER BY m.visibleAt DESC, m.id DESC";

    Optional<Moment> findByPublicId(String publicId);

    /**
     * Today, everyone: live moments of one community that are for the whole community, or by one of [mine] (the
     * viewer and their connections: only they can be in a MY_PEOPLE audience).
     */
    @Query("SELECT m FROM Moment m WHERE " + LIVE
            + " AND (m.audience = com.safesphere.backend.social.entity.MomentEnums.Audience.COMMUNITY"
            + " OR m.authorId IN :mine) AND " + BEFORE_CURSOR + NEWEST_FIRST)
    List<Moment> todayPage(@Param("community") long community, @Param("mine") Collection<Long> mine,
                           @Param("hidden") Collection<Long> hidden,
                           @Param("states") Collection<com.safesphere.backend.social.entity.PostEnums.ModerationState> states,
                           @Param("now") LocalDateTime now, @Param("cursorAt") LocalDateTime cursorAt,
                           @Param("cursorId") long cursorId, Pageable page);

    /** Today, My people: live moments of one community by [authors] (the viewer's connections), either audience. */
    @Query("SELECT m FROM Moment m WHERE " + LIVE + " AND m.authorId IN :authors AND " + BEFORE_CURSOR + NEWEST_FIRST)
    List<Moment> todayPageOf(@Param("community") long community, @Param("authors") Collection<Long> authors,
                             @Param("hidden") Collection<Long> hidden,
                             @Param("states") Collection<com.safesphere.backend.social.entity.PostEnums.ModerationState> states,
                             @Param("now") LocalDateTime now, @Param("cursorAt") LocalDateTime cursorAt,
                             @Param("cursorId") long cursorId, Pageable page);

    /** The author's own moments that have not ended: waiting (never visible yet) and live, newest first. */
    @Query("SELECT m FROM Moment m WHERE m.authorId = :author AND (m.expiresAt IS NULL OR m.expiresAt > :now)"
            + " ORDER BY m.createdAt DESC, m.id DESC")
    List<Moment> ownCurrent(@Param("author") long author, @Param("now") LocalDateTime now);

    /** The moments [author] keeps on their profile, most recently kept first (at most MomentPolicy.MAX_KEPT). */
    @Query("SELECT m FROM Moment m WHERE m.authorId = :author AND m.keptAt IS NOT NULL"
            + " ORDER BY m.keptAt DESC, m.id DESC")
    List<Moment> keptBy(@Param("author") long author);

    @Query("SELECT COUNT(m) FROM Moment m WHERE m.authorId = :author AND m.keptAt IS NOT NULL")
    long countKept(@Param("author") long author);

    /**
     * N-47: moments whose data may go: their 24 hours ended before [endedBefore] (7 days ago), the author does not
     * keep them, no open moderation case needs them, and no moderator removed them (how long removed content is
     * retained is decided in N8, so it is left alone here). A moment that was never visible has no end and is never
     * in this list: content waiting for a human is never deleted automatically (N-57).
     */
    @Query("SELECT m FROM Moment m WHERE m.expiresAt < :endedBefore AND m.keptAt IS NULL AND m.inReview = false"
            + " AND m.moderation <> com.safesphere.backend.social.entity.PostEnums.ModerationState.REMOVED"
            + " ORDER BY m.expiresAt")
    List<Moment> purgeable(@Param("endedBefore") LocalDateTime endedBefore, Pageable page);

    /** An admin deleted a daily question: its answers stay as ordinary moments (V35: ON DELETE SET NULL). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Moment m SET m.questionId = NULL WHERE m.questionId = :questionId")
    int clearQuestion(@Param("questionId") long questionId);
}
