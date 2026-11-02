package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.SavedPost;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Phase 17E: saved posts. Every list is ONE person's own; nothing counts how often a post was saved. */
public interface SavedPostRepository extends JpaRepository<SavedPost, Long> {

    Optional<SavedPost> findByUserIdAndPostId(long userId, long postId);

    List<SavedPost> findByUserIdAndPostIdIn(long userId, Collection<Long> postIds);

    /** One person's saved posts, newest saved first, strictly before a cursor (created_at, id). */
    @Query("SELECT s FROM SavedPost s WHERE s.userId = :userId"
            + " AND (s.createdAt < :cursorAt OR (s.createdAt = :cursorAt AND s.id < :cursorId))"
            + " ORDER BY s.createdAt DESC, s.id DESC")
    List<SavedPost> page(@Param("userId") long userId, @Param("cursorAt") LocalDateTime cursorAt,
                         @Param("cursorId") long cursorId, Pageable page);

    @Modifying
    @Query("DELETE FROM SavedPost s WHERE s.userId = :userId AND s.postId = :postId")
    int deleteOne(@Param("userId") long userId, @Param("postId") long postId);

    /** A deleted post leaves every saved list (the test database has no foreign keys to cascade). */
    @Modifying
    @Query("DELETE FROM SavedPost s WHERE s.postId = :postId")
    int deleteAllOfPost(@Param("postId") long postId);
}
