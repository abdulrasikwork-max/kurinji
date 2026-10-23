package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.Post;
import com.safesphere.backend.social.entity.PostEnums.Audience;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Phase 13B: posts. Lists are keyset pages (newest first) of ONE author, strictly before a cursor (created_at, id); the
 * first page passes a cursor after everything. Nothing lists everyone's posts in 13B.
 */
public interface PostRepository extends JpaRepository<Post, Long> {

    Optional<Post> findByPublicId(String publicId);

    @Query("SELECT p FROM Post p WHERE p.authorId = :author"
            + " AND (p.createdAt < :cursorAt OR (p.createdAt = :cursorAt AND p.id < :cursorId))"
            + " ORDER BY p.createdAt DESC, p.id DESC")
    List<Post> ownPage(@Param("author") long author, @Param("cursorAt") LocalDateTime cursorAt,
                       @Param("cursorId") long cursorId, Pageable page);

    /** Someone's posts another viewer may see: audiences from Visibility.visibleAudiences, delay over. */
    @Query("SELECT p FROM Post p WHERE p.authorId = :author AND p.audience IN :audiences AND p.visibleFrom <= :now"
            + " AND (p.createdAt < :cursorAt OR (p.createdAt = :cursorAt AND p.id < :cursorId))"
            + " ORDER BY p.createdAt DESC, p.id DESC")
    List<Post> visiblePage(@Param("author") long author, @Param("audiences") Collection<Audience> audiences,
                           @Param("now") LocalDateTime now, @Param("cursorAt") LocalDateTime cursorAt,
                           @Param("cursorId") long cursorId, Pageable page);
}
