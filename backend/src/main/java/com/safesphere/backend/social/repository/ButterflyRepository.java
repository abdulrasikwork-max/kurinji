package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.Butterfly;
import com.safesphere.backend.social.entity.PostEnums.ButterflyState;
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
 * Phase 13C: butterflies (Cocoon items). Every list is ONE person's own Cocoon; the only question asked about a post's
 * butterflies is how many there are, and only the author's answer uses it (thresholded, ButterflyTally).
 */
public interface ButterflyRepository extends JpaRepository<Butterfly, Long> {

    Optional<Butterfly> findByUserIdAndPostId(long userId, long postId);

    List<Butterfly> findByUserIdAndPostIdIn(long userId, Collection<Long> postIds);

    long countByPostId(long postId);

    @Query("SELECT b.postId, COUNT(b) FROM Butterfly b WHERE b.postId IN :postIds GROUP BY b.postId")
    List<Object[]> countByPostIds(@Param("postIds") Collection<Long> postIds);

    /** One person's Cocoon in one state, newest saved first, strictly before a cursor (created_at, id). */
    @Query("SELECT b FROM Butterfly b WHERE b.userId = :userId AND b.state = :state"
            + " AND (b.createdAt < :cursorAt OR (b.createdAt = :cursorAt AND b.id < :cursorId))"
            + " ORDER BY b.createdAt DESC, b.id DESC")
    List<Butterfly> cocoonPage(@Param("userId") long userId, @Param("state") ButterflyState state,
                               @Param("cursorAt") LocalDateTime cursorAt, @Param("cursorId") long cursorId,
                               Pageable page);

    /** A deleted post takes its butterflies with it (the test database has no foreign keys to cascade). */
    @Modifying
    @Query("DELETE FROM Butterfly b WHERE b.postId = :postId")
    int deleteAllOfPost(@Param("postId") long postId);
}
