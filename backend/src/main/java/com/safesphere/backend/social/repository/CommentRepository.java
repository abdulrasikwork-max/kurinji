package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.Comment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Phase 13B: perspectives. Read per post only, always through the one Visibility rule in CommentService. */
public interface CommentRepository extends JpaRepository<Comment, Long> {

    Optional<Comment> findByPublicId(String publicId);

    List<Comment> findByPostIdOrderByCreatedAtAscIdAsc(long postId);

    /** Replies first, then top-level perspectives (the test database has no foreign keys to cascade). */
    @Modifying
    @Query("DELETE FROM Comment c WHERE c.postId = :postId AND c.parentId IS NOT NULL")
    int deleteRepliesOfPost(@Param("postId") long postId);

    @Modifying
    @Query("DELETE FROM Comment c WHERE c.postId = :postId")
    int deleteAllOfPost(@Param("postId") long postId);

    @Modifying
    @Query("DELETE FROM Comment c WHERE c.parentId = :parentId")
    int deleteReplies(@Param("parentId") long parentId);
}
