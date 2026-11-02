package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.Butterfly;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Phase 17E: butterfly reactions. The only questions asked are "did THIS person react" (their own answer) and "how many
 * people reacted" (only the author's answer uses it, thresholded: ButterflyTally). No list of who reacted exists.
 */
public interface ButterflyRepository extends JpaRepository<Butterfly, Long> {

    Optional<Butterfly> findByUserIdAndPostId(long userId, long postId);

    List<Butterfly> findByUserIdAndPostIdIn(long userId, Collection<Long> postIds);

    @Query("SELECT b.postId, COUNT(b) FROM Butterfly b WHERE b.postId IN :postIds GROUP BY b.postId")
    List<Object[]> countByPostIds(@Param("postIds") Collection<Long> postIds);

    @Modifying
    @Query("DELETE FROM Butterfly b WHERE b.userId = :userId AND b.postId = :postId")
    int deleteOne(@Param("userId") long userId, @Param("postId") long postId);

    /** A deleted post takes its reactions with it (the test database has no foreign keys to cascade). */
    @Modifying
    @Query("DELETE FROM Butterfly b WHERE b.postId = :postId")
    int deleteAllOfPost(@Param("postId") long postId);
}
