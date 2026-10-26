package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.Post;
import com.safesphere.backend.social.entity.PostEnums.Audience;
import com.safesphere.backend.social.entity.PostEnums.City;
import com.safesphere.backend.social.entity.PostEnums.ModerationState;
import com.safesphere.backend.social.entity.PostEnums.PostType;
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

/**
 * Phase 13B: posts. Lists are keyset pages (newest first) of ONE author, strictly before a cursor (created_at, id); the
 * first page passes a cursor after everything. Phase 13C adds the lists of many people's posts (Discover).
 */
public interface PostRepository extends JpaRepository<Post, Long> {

    Optional<Post> findByPublicId(String publicId);

    /** Phase 14: locks the row, so that reports about one post are handled one after the other (ReportService). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Post p WHERE p.id = :id")
    Optional<Post> lockById(@Param("id") long id);

    @Query("SELECT p FROM Post p WHERE p.authorId = :author"
            + " AND (p.createdAt < :cursorAt OR (p.createdAt = :cursorAt AND p.id < :cursorId))"
            + " ORDER BY p.createdAt DESC, p.id DESC")
    List<Post> ownPage(@Param("author") long author, @Param("cursorAt") LocalDateTime cursorAt,
                       @Param("cursorId") long cursorId, Pageable page);

    /**
     * Someone's posts another viewer may see: audiences from Visibility.visibleAudiences, moderation states from
     * Visibility.visibleStates (13D), delay over. Phase 14: ordered and paged by visible_from, like every other list
     * of others' posts; ordering by created_at placed a delayed exact-place post below a later post and so told the
     * viewer when it was really written (the Q1 delay hides exactly that).
     */
    @Query("SELECT p FROM Post p WHERE p.authorId = :author AND p.audience IN :audiences AND p.visibleFrom <= :now"
            + " AND p.moderation IN :states"
            + " AND (p.visibleFrom < :cursorAt OR (p.visibleFrom = :cursorAt AND p.id < :cursorId))"
            + " ORDER BY p.visibleFrom DESC, p.id DESC")
    List<Post> visiblePage(@Param("author") long author, @Param("audiences") Collection<Audience> audiences,
                           @Param("states") Collection<ModerationState> states,
                           @Param("now") LocalDateTime now, @Param("cursorAt") LocalDateTime cursorAt,
                           @Param("cursorId") long cursorId, Pageable page);

    /** Phase 13D (M17): exact and area posts of a duplicate place move to the place it is merged into. */
    @Modifying
    @Query("UPDATE Post p SET p.placeId = :into WHERE p.placeId = :from")
    int movePlace(@Param("from") long from, @Param("into") long into);

    // ---- Phase 13C: lists of many people's posts. Each is only a pre-filter: every post still passes Visibility
    // (Relationships.forLists) before it is shown. Others' posts are ordered by when THEY could see them
    // (visible_from), never by the hidden creation time.

    String IN_CITY = "((p.precision = com.safesphere.backend.social.entity.PostEnums.Precision.CITY AND p.city = :city)"
            + " OR (p.precision IN (com.safesphere.backend.social.entity.PostEnums.Precision.EXACT,"
            + " com.safesphere.backend.social.entity.PostEnums.Precision.AREA)"
            + " AND p.placeId IN (SELECT pl.id FROM Place pl WHERE pl.city = :city)))";

    String EVERYONE = "p.audience = com.safesphere.backend.social.entity.PostEnums.Audience.EVERYONE";

    /** Edition candidates: Everyone posts of one type in a city, visible within [from, now], not the viewer's. */
    @Query("SELECT p FROM Post p WHERE " + EVERYONE + " AND p.type = :type AND p.authorId <> :viewer"
            + " AND p.visibleFrom >= :from AND p.visibleFrom <= :now AND " + IN_CITY
            + " ORDER BY p.visibleFrom DESC, p.id DESC")
    List<Post> editionCandidates(@Param("type") PostType type, @Param("city") City city, @Param("viewer") long viewer,
                                 @Param("from") LocalDateTime from, @Param("now") LocalDateTime now, Pageable page);

    /** Theme candidates: as above, one category. */
    @Query("SELECT p FROM Post p WHERE " + EVERYONE + " AND p.type = :type AND p.authorId <> :viewer"
            + " AND p.category = :category AND p.visibleFrom >= :from AND p.visibleFrom <= :now AND " + IN_CITY
            + " ORDER BY p.visibleFrom DESC, p.id DESC")
    List<Post> themeCandidates(@Param("type") PostType type, @Param("city") City city, @Param("viewer") long viewer,
                               @Param("category") String category, @Param("from") LocalDateTime from,
                               @Param("now") LocalDateTime now, Pageable page);

    /**
     * Phase 16 (U2) "Talking now" candidates: Everyone posts (either type) in a city, not the viewer's, visible within
     * [from, now], with a perspective written since [since] by someone other than the post's author. A pre-filter only:
     * every perspective still passes the one rule before it can order anything.
     */
    @Query("SELECT p FROM Post p WHERE " + EVERYONE + " AND p.authorId <> :viewer"
            + " AND p.visibleFrom >= :from AND p.visibleFrom <= :now AND " + IN_CITY
            + " AND EXISTS (SELECT c.id FROM Comment c WHERE c.postId = p.id AND c.authorId <> p.authorId"
            + " AND c.createdAt >= :since)"
            + " ORDER BY p.visibleFrom DESC, p.id DESC")
    List<Post> talkedAbout(@Param("city") City city, @Param("viewer") long viewer, @Param("from") LocalDateTime from,
                           @Param("since") LocalDateTime since, @Param("now") LocalDateTime now, Pageable page);

    /** Open Asks: Everyone Asks in a city without an answer the asker marked "This helped". */
    @Query("SELECT p FROM Post p WHERE " + EVERYONE
            + " AND p.type = com.safesphere.backend.social.entity.PostEnums.PostType.ASK AND p.authorId <> :viewer"
            + " AND p.visibleFrom >= :from AND p.visibleFrom <= :now AND " + IN_CITY
            + " AND NOT EXISTS (SELECT c.id FROM Comment c WHERE c.postId = p.id AND c.helpedAt IS NOT NULL)"
            + " ORDER BY p.visibleFrom DESC, p.id DESC")
    List<Post> openAsks(@Param("city") City city, @Param("viewer") long viewer, @Param("from") LocalDateTime from,
                        @Param("now") LocalDateTime now, Pageable page);

    /** C9: connections' posts the viewer's audiences allow, newest visible first, within [from, now]. */
    @Query("SELECT p FROM Post p WHERE p.authorId IN :authors AND p.audience IN :audiences"
            + " AND p.visibleFrom >= :from AND p.visibleFrom <= :now"
            + " AND (p.visibleFrom < :cursorAt OR (p.visibleFrom = :cursorAt AND p.id < :cursorId))"
            + " ORDER BY p.visibleFrom DESC, p.id DESC")
    List<Post> connectionsPage(@Param("authors") Collection<Long> authors,
                               @Param("audiences") Collection<Audience> audiences, @Param("from") LocalDateTime from,
                               @Param("now") LocalDateTime now, @Param("cursorAt") LocalDateTime cursorAt,
                               @Param("cursorId") long cursorId, Pageable page);

    /** C10: Everyone posts that name this exact place. AREA posts never appear: they promise not to reveal it. */
    @Query("SELECT p FROM Post p WHERE " + EVERYONE + " AND p.placeId = :placeId"
            + " AND p.precision = com.safesphere.backend.social.entity.PostEnums.Precision.EXACT"
            + " AND p.visibleFrom <= :now"
            + " AND (p.visibleFrom < :cursorAt OR (p.visibleFrom = :cursorAt AND p.id < :cursorId))"
            + " ORDER BY p.visibleFrom DESC, p.id DESC")
    List<Post> placePage(@Param("placeId") long placeId, @Param("now") LocalDateTime now,
                         @Param("cursorAt") LocalDateTime cursorAt, @Param("cursorId") long cursorId, Pageable page);

    /** C11: Everyone posts of one category. */
    @Query("SELECT p FROM Post p WHERE " + EVERYONE + " AND p.category = :category AND p.visibleFrom <= :now"
            + " AND (p.visibleFrom < :cursorAt OR (p.visibleFrom = :cursorAt AND p.id < :cursorId))"
            + " ORDER BY p.visibleFrom DESC, p.id DESC")
    List<Post> categoryPage(@Param("category") String category, @Param("now") LocalDateTime now,
                            @Param("cursorAt") LocalDateTime cursorAt, @Param("cursorId") long cursorId,
                            Pageable page);

    /** C11: Everyone posts of one category in one city. */
    @Query("SELECT p FROM Post p WHERE " + EVERYONE + " AND p.category = :category AND p.visibleFrom <= :now"
            + " AND " + IN_CITY
            + " AND (p.visibleFrom < :cursorAt OR (p.visibleFrom = :cursorAt AND p.id < :cursorId))"
            + " ORDER BY p.visibleFrom DESC, p.id DESC")
    List<Post> categoryCityPage(@Param("category") String category, @Param("city") City city,
                                @Param("now") LocalDateTime now, @Param("cursorAt") LocalDateTime cursorAt,
                                @Param("cursorId") long cursorId, Pageable page);

    List<Post> findByIdIn(Collection<Long> ids);
}
